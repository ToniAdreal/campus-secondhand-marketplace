#!/usr/bin/env bash
# Restore drill for the compose stack (backlog #225).
#
# Restores a backup directory made by scripts/backup.sh into a SCRATCH
# compose project — never over the running stack — boots it, and checks
# it: actuator /health UP, the nginx /api proxy answering the 401
# envelope, and row counts for app_user / item / orders matching the
# source (live source counts when the source stack is up, otherwise the
# counts recorded in manifest.txt at backup time). Any mismatch fails
# loudly (non-zero exit) instead of printing a reassuring summary.
#
#   bash scripts/restore-drill.sh <BACKUP_DIR> [SCRATCH_PROJECT]
#
# SCRATCH_PROJECT defaults to marketplace-restore-drill. Run from the
# repo root. The scratch stack's host ports are remapped by rewriting
# the three published ports in a generated copy of docker-compose.yml
# (mysql 13306, backend 18080, frontend 180) — a compose override file
# would MERGE ports with the base file and could re-publish the source
# stack's ports, so the drill rewrites them instead and verifies each
# replacement landed exactly once before anything starts.
#
# The scratch stack is torn down (down -v) on exit, success or
# failure, unless KEEP_SCRATCH=1. The source project is never passed
# to any down command: every compose invocation in this script uses
# -p "$SCRATCH", and the script refuses to run when SCRATCH equals
# the source project.
#
# A drill against an empty stack (all counts 0) passes with a WARNING:
# 0 == 0 proves the plumbing, not a restore of real data. Seed the
# source stack first (register + create a listing, as the CI drill job
# does) for a drill that means something.
set -euo pipefail

BACKUP_DIR="${1:?usage: bash scripts/restore-drill.sh <BACKUP_DIR> [SCRATCH_PROJECT]}"
SCRATCH="${2:-${SCRATCH_PROJECT:-marketplace-restore-drill}}"
SOURCE="${COMPOSE_PROJECT_NAME:-$(basename "$PWD")}"

die() { echo "restore-drill: error: $*" >&2; exit 1; }

# All scratch-stack compose invocations go through here: the generated
# scratch file lives in a temp dir, so --project-directory pins build
# contexts and the .env lookup back to the repo root (otherwise compose
# would resolve ./backend relative to the temp dir and fail).
scompose() {
  docker compose --project-directory "$PWD" -p "$SCRATCH" -f "$SCRATCH_COMPOSE" "$@"
}

# --- Fast failures that need no Docker (checked before any container
# --- is touched, so a typo can never become a partial restore).
[ -d "$BACKUP_DIR" ] || die "backup directory $BACKUP_DIR does not exist"
for f in marketdb.sql uploads.tar.gz manifest.txt; do
  [ -f "$BACKUP_DIR/$f" ] || die "backup directory $BACKUP_DIR is missing $f (not a scripts/backup.sh output?)"
done
[ "$SCRATCH" != "$SOURCE" ] \
  || die "refusing: scratch project '$SCRATCH' equals the source project '$SOURCE' — a drill must never target the running stack"

case "$BACKUP_DIR" in
  /*) BACKUP_ABS="$BACKUP_DIR" ;;
  *) BACKUP_ABS="$PWD/$BACKUP_DIR" ;;
esac

command -v docker >/dev/null 2>&1 || die "docker is not on PATH"
docker compose version >/dev/null 2>&1 || die "docker compose v2 is not available"
command -v curl >/dev/null 2>&1 || die "curl is not on PATH"

TMP="$(mktemp -d)"
SCRATCH_COMPOSE="$TMP/docker-compose.yml"
cleanup() {
  if [ "${KEEP_SCRATCH:-0}" != "1" ] && [ -f "$SCRATCH_COMPOSE" ]; then
    scompose down -v >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

# --- Scratch compose file: port rewrite, verified, not merged.
sed -e 's/- "3306:3306"/- "13306:3306"/' \
    -e 's/- "8080:8080"/- "18080:8080"/' \
    -e 's/- "80:80"/- "180:80"/' \
    docker-compose.yml > "$SCRATCH_COMPOSE"
for pair in '"13306:3306"' '"18080:8080"' '"180:80"'; do
  [ "$(grep -c -F "$pair" "$SCRATCH_COMPOSE")" = "1" ] \
    || die "port rewrite failed for $pair in the scratch compose file"
done
for pair in '"3306:3306"' '"8080:8080"' '"80:80"'; do
  grep -q -F "$pair" "$SCRATCH_COMPOSE" \
    && die "original port $pair still present in the scratch compose file"
done

scratch_mysql() {
  # $1: SQL; runs inside the scratch mysql container.
  scompose exec -T mysql sh -c \
    "mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"$1\" \"\$MYSQL_DATABASE\""
}

echo "restore-drill: starting scratch mysql (project $SCRATCH)"
scompose up -d mysql
ok=""
for _ in $(seq 1 60); do
  if scratch_mysql "SELECT 1" >/dev/null 2>&1; then ok=1; break; fi
  sleep 5
done
[ -n "$ok" ] || die "scratch mysql never became ready"

echo "restore-drill: importing $BACKUP_DIR/marketdb.sql into scratch mysql"
scompose exec -T mysql sh -c \
  'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < "$BACKUP_ABS/marketdb.sql"

echo "restore-drill: restoring uploads into ${SCRATCH}_uploads-data"
docker volume create "${SCRATCH}_uploads-data" >/dev/null
docker run --rm \
  -v "${SCRATCH}_uploads-data:/data" \
  -v "$BACKUP_ABS:/backup:ro" \
  alpine:3.20 tar xzf /backup/uploads.tar.gz -C /data

echo "restore-drill: starting scratch backend + frontend"
scompose up -d backend frontend
ok=""
for _ in $(seq 1 60); do
  body="$(curl -fsS http://localhost:18080/actuator/health 2>/dev/null || true)"
  if [ -n "$body" ] && echo "$body" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin)["status"]=="UP" else 1)' 2>/dev/null; then
    ok=1; break
  fi
  sleep 5
done
[ -n "$ok" ] || die "scratch backend never reported actuator /health UP on :18080"

code="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:180/api/items || true)"
[ "$code" = "401" ] \
  || die "scratch frontend proxy did not answer /api/items with the 401 envelope (got [$code]) — nginx -> backend -> security chain is broken on the restored stack"
echo "restore-drill: scratch proxy check OK (401 envelope through nginx)"

# --- Row-count comparison: scratch vs source.
S_USERS="$(scratch_mysql 'SELECT COUNT(*) FROM app_user')"
S_ITEMS="$(scratch_mysql 'SELECT COUNT(*) FROM item')"
S_ORDERS="$(scratch_mysql 'SELECT COUNT(*) FROM orders')"

if SOURCE_COUNTS="$(docker compose -p "$SOURCE" exec -T mysql sh -c \
    'mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT COUNT(*) FROM app_user; SELECT COUNT(*) FROM item; SELECT COUNT(*) FROM orders;" "$MYSQL_DATABASE"' 2>/dev/null)"; then
  echo "restore-drill: comparing against live source stack ($SOURCE)"
  O_USERS="$(echo "$SOURCE_COUNTS" | sed -n '1p')"
  O_ITEMS="$(echo "$SOURCE_COUNTS" | sed -n '2p')"
  O_ORDERS="$(echo "$SOURCE_COUNTS" | sed -n '3p')"
else
  echo "restore-drill: source stack ($SOURCE) not reachable — comparing against manifest.txt counts from backup time"
  O_USERS="$(grep '^app_user=' "$BACKUP_ABS/manifest.txt" | cut -d= -f2)"
  O_ITEMS="$(grep '^item=' "$BACKUP_ABS/manifest.txt" | cut -d= -f2)"
  O_ORDERS="$(grep '^orders=' "$BACKUP_ABS/manifest.txt" | cut -d= -f2)"
fi

fail=""
[ "$S_USERS" = "$O_USERS" ] || fail="${fail} app_user scratch=${S_USERS} source=${O_USERS};"
[ "$S_ITEMS" = "$O_ITEMS" ] || fail="${fail} item scratch=${S_ITEMS} source=${O_ITEMS};"
[ "$S_ORDERS" = "$O_ORDERS" ] || fail="${fail} orders scratch=${S_ORDERS} source=${O_ORDERS};"
[ -z "$fail" ] || die "row-count MISMATCH:${fail} the restore does not reproduce the source data"

if [ "$S_USERS" = "0" ] && [ "$S_ITEMS" = "0" ] && [ "$S_ORDERS" = "0" ]; then
  echo "restore-drill: WARNING — all counts are 0; this drill proved the plumbing only, not a restore of real data (seed the source stack before the next drill)"
fi

echo "restore-drill: PASSED — app_user=${S_USERS} item=${S_ITEMS} orders=${S_ORDERS} on the scratch stack"
echo "restore-drill: scratch stack will be torn down on exit (KEEP_SCRATCH=1 to keep it for inspection)"
