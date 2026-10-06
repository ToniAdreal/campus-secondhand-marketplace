# Docker Compose smoke test

Manual end-to-end verification of the full stack on real MySQL 8:

- `mysql` — mysql:8.0, database `marketdb`
- `backend` — Spring Boot jar built in-container, running with the **`mysql` Spring profile**
  (`SPRING_PROFILES_ACTIVE=mysql` → `jdbc:mysql://mysql:3306/marketdb`, Flyway V1–V7
  migrations run against real MySQL)
- `frontend` — React build served by nginx on port 80; **`/api/` is reverse-proxied to
  `backend:8080`** (`frontend/nginx.conf`), so the browser app and API share one origin

This is a portfolio/reconstruction project, not production: the compose stack uses the
dev JWT-signing placeholder baked into `application.yml`. Set `APP_JWT_SECRET` on the
backend service for anything beyond a smoke run.

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

Everything except `/api/auth/**`, `/uploads/**`, and `/error` requires a Bearer token —
an unauthenticated `curl http://localhost/api/items` must return the JSON 401 envelope,
which itself proves the proxy → security filter chain path works.

Optionally open http://localhost/ in a browser: the React app, login as `smoke`, and
walk the create-listing / buy-now flow in the UI.

## Teardown

```bash
docker compose down -v
```

`-v` drops the `mysql-data` volume so the next run replays Flyway from V1 — the smoke
is only meaningful against a clean database.

## Troubleshooting

- `docker compose logs backend` — if the backend exits, look for datasource/Flyway
  errors; on a healthy run the last lines are the Spring Boot startup banner.
- `docker compose ps` — `mysql` should reach `healthy`; if it never does, check
  `docker compose logs mysql` (usually a port-3306 collision with a host MySQL).
- Listing photos land in the backend container's `/app/uploads` (`APP_UPLOADS_DIR`
  override); not persisted across rebuilds by design in this smoke.
- CI runs the same sequence automatically on every push — see the
  `docker-compose-smoke` job in `.github/workflows/ci.yml`.
