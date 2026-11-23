#!/bin/sh
# Metrics-token refresher (backlog #430) — keeps Prometheus's scrape
# credential fresh without a long-lived token (there is none in this
# repo by design; see docs/observability.md).
#
# How it works: log in once as the scrape account (the bootstrap
# ADMIN — see docker-compose.yml; any confirmed account works), keep
# the httpOnly refresh_token cookie in a jar file, and write the
# 15-minute access token to the shared metrics-token volume, where
# Prometheus reads it via bearer_token_file on every scrape. Every
# 10 minutes it rotates via POST /api/auth/refresh; a failed refresh
# (revoked/expired family) falls back to a fresh login. The token file
# is written atomically (temp file + rename) so Prometheus never reads
# a half-written token, and the token is never printed to the logs.
#
# Env:
#   BACKEND_URL (default http://backend:8080) — compose service name
#   METRICS_USERNAME / METRICS_PASSWORD (required) — scrape account
#   TOKEN_FILE (default /run/metrics-token/access-token)
#   COOKIE_JAR (default /run/metrics-token/cookies.txt)
#   REFRESH_INTERVAL_SECONDS (default 600 — inside the 15-minute
#     access-token lifetime with margin; a refresh is one request,
#     far under the refresh endpoint's rate limit)
#   LOGIN_RETRY_SECONDS (default 5 — the backend starts after us)
set -eu

BACKEND_URL="${BACKEND_URL:-http://backend:8080}"
METRICS_USERNAME="${METRICS_USERNAME:-}"
METRICS_PASSWORD="${METRICS_PASSWORD:-}"
TOKEN_FILE="${TOKEN_FILE:-/run/metrics-token/access-token}"
COOKIE_JAR="${COOKIE_JAR:-/run/metrics-token/cookies.txt}"
REFRESH_INTERVAL_SECONDS="${REFRESH_INTERVAL_SECONDS:-600}"
LOGIN_RETRY_SECONDS="${LOGIN_RETRY_SECONDS:-5}"

if [ -z "$METRICS_USERNAME" ] || [ -z "$METRICS_PASSWORD" ]; then
  echo "metrics-token-refresher: METRICS_USERNAME and METRICS_PASSWORD are required — set the bootstrap ADMIN credentials (see docs/observability.md)" >&2
  exit 1
fi

# write_token TOKEN — atomic write, 0600, never logs the value.
write_token() {
  token="$1"
  mkdir -p "$(dirname "$TOKEN_FILE")"
  tmp="$TOKEN_FILE.tmp.$$"
  printf '%s' "$token" > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$TOKEN_FILE"
}

# extract_token — reads a login/refresh response body on stdin and
# prints the envelope's data.accessToken, or nothing when absent.
extract_token() {
  jq -r '.data.accessToken // empty' 2>/dev/null || true
}

# do_login — POST /api/auth/login, cookie jar keeps the refresh token.
do_login() {
  body=$(jq -nc --arg u "$METRICS_USERNAME" --arg p "$METRICS_PASSWORD" \
    '{usernameOrEmail: $u, password: $p}')
  resp=$(curl -fsS -c "$COOKIE_JAR" -X POST "$BACKEND_URL/api/auth/login" \
    -H 'Content-Type: application/json' -d "$body") || return 1
  token=$(printf '%s' "$resp" | extract_token)
  [ -n "$token" ] || return 1
  write_token "$token"
}

# do_refresh — POST /api/auth/refresh with the cookie jar; rotation
# replaces the cookie, so the jar is both read and rewritten.
do_refresh() {
  resp=$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST \
    "$BACKEND_URL/api/auth/refresh") || return 1
  token=$(printf '%s' "$resp" | extract_token)
  [ -n "$token" ] || return 1
  write_token "$token"
}

# Log in, retrying until the backend answers (it starts after us and
# the first attempts fail at TCP level, before any rate limit applies).
until do_login; do
  echo "metrics-token-refresher: login failed, retrying in ${LOGIN_RETRY_SECONDS}s"
  sleep "$LOGIN_RETRY_SECONDS"
done
echo "metrics-token-refresher: token written, refreshing every ${REFRESH_INTERVAL_SECONDS}s"

while true; do
  sleep "$REFRESH_INTERVAL_SECONDS"
  if do_refresh; then
    echo "metrics-token-refresher: token refreshed"
  else
    echo "metrics-token-refresher: refresh failed, re-logging in"
    until do_login; do
      sleep "$LOGIN_RETRY_SECONDS"
    done
    echo "metrics-token-refresher: token re-issued via login"
  fi
done
