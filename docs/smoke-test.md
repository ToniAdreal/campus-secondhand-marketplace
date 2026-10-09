# Docker Compose smoke test

Manual end-to-end verification of the full stack on real MySQL 8:

- `mysql` — mysql:8.0, database `marketdb`
- `backend` — Spring Boot jar built in-container, running with the **`mysql` Spring profile**
  (`SPRING_PROFILES_ACTIVE=mysql` → `jdbc:mysql://mysql:3306/marketdb`, Flyway V1–V7
  migrations run against real MySQL)
- `frontend` — React build served by nginx on port 80; **`/api/` is reverse-proxied to
  `backend:8080`** (`frontend/nginx.conf`), so the browser app and API share one origin

This is a portfolio/reconstruction project, not production, but the backend will not
boot on the `mysql` profile with the dev JWT-signing placeholder baked into
`application.yml` — `JwtSecretStartupCheck` fails fast so a forgotten secret can
never sign tokens with a public key. Export a random 256-bit secret before the
first `docker compose up` (the compose file errors out early with a reminder if
you forget):

```bash
export APP_JWT_SECRET=$(openssl rand -base64 32)
```

The same fail-fast applies to the TOTP secret encryption key (backlog #89):
the backend will not boot on the `mysql` profile with the committed dev
placeholder for `app.auth.totp.encryption-key` — `TotpEncryptionKeyStartupCheck`
fails fast so TOTP secrets are never encrypted under a public key. Export a
second, independent random key the same way (the compose file errors out early
with a reminder if you forget):

```bash
export APP_TOTP_ENCRYPTION_KEY=$(openssl rand -base64 32)
```

## Prerequisites

- Docker Engine + Compose v2 (`docker compose`)
- Ports 80, 8080, 3306 free on the host (a local MySQL on 3306 will collide)

## Run

```bash
docker compose up --build -d
```

First boot takes a few minutes: the backend build stage (`maven:3.9.9`) downloads
dependencies, MySQL initializes `marketdb`, and the backend replays the Flyway
migrations. The backend waits for MySQL's healthcheck (`service_healthy`) before
starting, so a cold-boot race is not expected.

Wait for the API to answer **through the nginx `/api` proxy** (not directly on 8080 —
the proxy wiring is part of what this verifies):

```bash
# register through the proxy (username 3-60 chars, valid email, password 8-72 chars)
curl -s -X POST http://localhost/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"smoke","email":"smoke@example.com","password":"Sm0kePass123"}'
# → {"code":0,"message":"ok","data":{"accessToken":"…","tokenType":"Bearer",…}}
#    the refresh token is Set-Cookie'd as httpOnly `refresh_token`, never in the body
```

Then exercise an authenticated endpoint through the proxy:

```bash
TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"smoke","password":"Sm0kePass123"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])')

curl -s http://localhost/api/items -H "Authorization: Bearer $TOKEN" | head -c 300
# → {"code":0,"message":"ok","data":{"content":[],…}}  (empty page envelope)

curl -s -X POST http://localhost/api/items -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"smoke listing","priceCents":1999}' | head -c 300
# → {"code":0,"message":"ok","data":{"id":1,"title":"smoke listing",…}}
```

Everything except `/api/auth/**`, `/uploads/**`, `/actuator/health`,
`/actuator/info`, and `/error` requires a Bearer token —
an unauthenticated `curl http://localhost/api/items` must return the JSON 401 envelope,
which itself proves the proxy → security filter chain path works.

### Actuator probes

The backend exposes Spring Boot Actuator:

- `curl http://localhost:8080/actuator/health` → `{"status":"UP"}` — public,
  unauthenticated; this is the liveness probe for the compose stack (CI curls
  it after the backend is up — see the `docker-compose-smoke` job).
- `curl http://localhost:8080/actuator/info` — public, app name/version.
- `/actuator/metrics` (and anything else under `/actuator/**`) — requires a
  valid Bearer token like any API endpoint.
- `/actuator/prometheus` — also behind auth; exports the Micrometer registry in
  Prometheus text format (`jvm_`, `http.server.requests`, `hikaricp_` series).
  Scrape target for an external Prometheus server:

  ```yaml
  scrape_configs:
    - job_name: campus-marketplace-backend
      static_configs:
        - targets: ['backend:8080']   # compose service name; host dev: localhost:8080
      metrics_path: /actuator/prometheus
      # bearer_token_file / basic auth required — the endpoint needs a Bearer token
      authorization:
        credentials: <backend-api-token>
  ```

  Honest scope: metrics exist, alerting does not — no alert rules are defined
  or bundled with this repo.

Optionally open http://localhost/ in a browser: the React app, login as `smoke`, and
walk the create-listing / buy-now flow in the UI.

## Teardown

```bash
docker compose down -v
```

`-v` drops the named volumes (`mysql-data` and `uploads-data`) so the next run
replays Flyway from V1 — the smoke is only meaningful against a clean database.

## Troubleshooting

- `docker compose logs backend` — if the backend exits, look for datasource/Flyway
  errors; on a healthy run the last lines are the Spring Boot startup banner.
- `docker compose ps` — `mysql` should reach `healthy`; if it never does, check
  `docker compose logs mysql` (usually a port-3306 collision with a host MySQL).
- Listing photos land in `/app/uploads` inside the backend container, which is
  the `uploads-data` named volume (compose pins `APP_UPLOADS_DIR=/app/uploads`):
  photos survive a container recreate and a plain `docker compose down`, and are
  only dropped by `docker compose down -v` (with `mysql-data`).
- CI runs the same sequence automatically on every push — see the
  `docker-compose-smoke` job in `.github/workflows/ci.yml`.
