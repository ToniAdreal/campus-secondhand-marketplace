#!/usr/bin/env bash
# Back up the running compose stack (backlog #225).
#
# What it writes into BACKUP_DIR (default backups/<timestamp>):
#   marketdb.sql    — mysqldump of the compose MySQL database
#                     (--single-transaction, so a running stack dumps a
#                     consistent InnoDB snapshot; no table locks)
#   uploads.tar.gz  — tar of the uploads-data volume (listing photos)
#   manifest.txt    — timestamp, source project, row counts for
#                     app_user / item / orders, and sha256 checksums
#
# How it authenticates: it never asks the operator for a password. The
# dump and the row counts run inside the mysql container, where the
# compose file already sets MYSQL_ROOT_PASSWORD / MYSQL_DATABASE, so
# `docker compose exec mysql sh -c 'mysql -p"$MYSQL_ROOT_PASSWORD" …'`
# expands those variables in the container, not on the host.
#
# Run from the repo root, with the stack already up:
#   docker compose up -d
#   bash scripts/backup.sh [BACKUP_DIR]
#
# The compose project defaults to the current directory's basename
# (the directory a real checkout is named after); override with
# COMPOSE_PROJECT_NAME when the checkout directory differs.
#
# The backup directory holds a database dump that includes password
# hashes — treat it like a credential, never commit it (backups/ is
# gitignored), never upload it anywhere. Restore with
# scripts/restore-drill.sh, never by hand over the running stack.
set -euo pipefail

PROJECT="${COMPOSE_PROJECT_NAME:-$(basename "$PWD")}"
OUT="${1:-backups/$(date +%Y-%m-%d-%H%M%S)}"

die() { echo "backup: error: $*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker is not on PATH"
docker compose version >/dev/null 2>&1 || die "docker compose v2 is not available"

# The volume-inspect check exists for a reason: `docker run -v` silently
# CREATES a named volume that does not exist, which would turn a typo'd
# project name into an empty "backup" instead of an error.
docker volume inspect "${PROJECT}_uploads-data" >/dev/null 2>&1 \
  || die "volume ${PROJECT}_uploads-data not found — is the ${PROJECT} stack up? (docker compose up -d)"

# Reachability probe: fails fast when the stack is down, before any
# directory is created.
docker compose -p "$PROJECT" exec -T mysql sh -c \
  'mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "SELECT 1" "$MYSQL_DATABASE" >/dev/null' \
  || die "cannot reach the ${PROJECT} mysql service — is the stack up?"

case "$OUT" in
  /*) ;;
  *) OUT="$PWD/$OUT" ;;
esac
if [ -e "$OUT" ] && [ -n "$(ls -A "$OUT" 2>/dev/null)" ]; then
  die "backup directory $OUT already exists and is not empty — pass a fresh directory"
fi
mkdir -p "$OUT"

mysql_exec() {
  # $1: SQL to run inside the mysql container; prints stdout.
  docker compose -p "$PROJECT" exec -T mysql sh -c \
    "mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"$1\" \"\$MYSQL_DATABASE\""
}

echo "backup: dumping ${PROJECT} database to $OUT/marketdb.sql"
docker compose -p "$PROJECT" exec -T mysql sh -c \
  'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines "$MYSQL_DATABASE"' \
  > "$OUT/marketdb.sql"
[ -s "$OUT/marketdb.sql" ] || die "marketdb.sql is empty"
grep -q "CREATE TABLE" "$OUT/marketdb.sql" \
  || die "marketdb.sql contains no CREATE TABLE — the dump is not a database backup"

echo "backup: archiving ${PROJECT}_uploads-data to $OUT/uploads.tar.gz"
docker run --rm \
  -v "${PROJECT}_uploads-data:/data:ro" \
  -v "$OUT:/backup" \
  alpine:3.20 tar czf /backup/uploads.tar.gz -C /data .
[ -s "$OUT/uploads.tar.gz" ] || die "uploads.tar.gz is empty"

USERS="$(mysql_exec 'SELECT COUNT(*) FROM app_user')"
ITEMS="$(mysql_exec 'SELECT COUNT(*) FROM item')"
ORDERS="$(mysql_exec 'SELECT COUNT(*) FROM orders')"
UPLOAD_FILES="$(tar tzf "$OUT/uploads.tar.gz" | wc -l)"

{
  echo "# backup manifest (backlog #225)"
  echo "created_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "source_project=${PROJECT}"
  echo "app_user=${USERS}"
  echo "item=${ITEMS}"
  echo "orders=${ORDERS}"
  echo "uploads_files=${UPLOAD_FILES}"
  (cd "$OUT" && sha256sum marketdb.sql uploads.tar.gz)
} > "$OUT/manifest.txt"

echo "backup: done — app_user=${USERS} item=${ITEMS} orders=${ORDERS} uploads_files=${UPLOAD_FILES}"
echo "backup: directory: $OUT"
echo "backup: verify it with: bash scripts/restore-drill.sh $OUT"
