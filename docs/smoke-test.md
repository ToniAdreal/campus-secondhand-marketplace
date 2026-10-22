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
`/actuator/health/liveness`, `/actuator/health/readiness`,
`/actuator/info`, and `/error` requires a Bearer token —
an unauthenticated `curl http://localhost/api/items` must return the JSON 401 envelope,
which itself proves the proxy → security filter chain path works.

### Actuator probes

The backend exposes Spring Boot Actuator:

- `curl http://localhost:8080/actuator/health` → `{"status":"UP"}` — public,
  unauthenticated (CI curls it after the backend is up — see the
  `docker-compose-smoke` job).
- Liveness probe: `curl http://localhost:8080/actuator/health/liveness` →
  `{"status":"UP"}` — public, unauthenticated; reports `livenessState` only
  (never the database, so a DB outage cannot restart-loop the app). Point
  the orchestrator's liveness probe here.
- Readiness probe: `curl http://localhost:8080/actuator/health/readiness` →
  `{"status":"UP"}` — public, unauthenticated; reports `readinessState` +
  `db`, so it goes DOWN while the database is unreachable. Point the
  orchestrator's / load balancer's readiness probe here.
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

  Honest scope: alert rules and an operator runbook are provided in
  docs/alert-rules.yml and docs/runbook.md, for loading into an operator's
  own Prometheus — no Prometheus or Alertmanager is deployed or bundled
  with this repo.

Optionally open http://localhost/ in a browser: the React app, login as `smoke`, and
walk the create-listing / buy-now flow in the UI.

## Browser e2e smoke (Playwright, CI)

The compose smoke above proves the API answers through the nginx proxy —
with curl, no browser, and no SPA behaviour. The browser layer is a
separate Playwright suite (backlog #181, `frontend/e2e/smoke.e2e.ts`,
config in `frontend/playwright.config.ts`) that runs in the CI `e2e` job:

- Real Chromium drives the **built** SPA (`npm run build`, served by
  `vite preview` on :4173, whose `preview.proxy` mirrors the dev-server
  and nginx `/api` proxy shapes) against the real backend jar on its
  default H2 profile (:8080) — no Docker, no MySQL.
- Exactly seven flows: (1) register lands on browse signed in;
  (2) browse → a listing's detail page renders; (3) login → create a
  listing → it appears in My listings; (4) two-user buy-now → pay → the
  buyer sees the one-time handover code on the confirmation page and
  the order as PAID in My orders (backlog #194); (5) the seller
  completes the order with the buyer's handover code → the buyer sees
  COMPLETED; (6) offer → seller counters → buyer counter-accepts → the
  order is pinned via the API at the counter price → it pays (backlog
  #213); (7) pay → buyer disputes → the ADMIN resolves with a refund →
  the buyer sees REFUNDED (backlog #213). Flows 5 and 7 seed their PAID
  orders through the real API (the pay response is where the code is
  disclosed); the completions/resolutions themselves are driven in the
  browser. Selectors are role/label based.
- The backend starts with the email-verification gate stubbed off via
  its documented env knob (`APP_AUTH_EMAIL_VERIFICATION_ENABLED=false`):
  the default mail sender is log-only with the token redacted, so no
  test could read a verification link — the suite does not pretend a
  mailbox exists. With the gate off, registration returns a session.
  Flow 7's ADMIN is seeded through the backend's opt-in first-admin
  bootstrap (`APP_BOOTSTRAP_ADMIN_*`, backlog #213): registration only
  mints USER accounts, and the bootstrap is the supported way to create
  a deployment's first admin — the e2e stack points it at a throwaway
  account that lives only in the job's in-memory H2.
- `retries: 0`, `workers: 1` (the flows share one backend and its
  per-IP auth rate-limit buckets). A flaky flow is fixed or deleted,
  never retried into green; traces/screenshots are kept on failure and
  uploaded by the CI job.

Which layer proves what: backend behaviour — unit + Testcontainers IT;
SPA components/routes in isolation — Vitest + RTL; API-through-proxy —
the compose curl smoke above; SPA-in-a-browser behaviour (routing,
forms, session nav) against a live backend — this Playwright suite.
Locally (no browsers/Docker in the dev sandbox) only the config and
test list are checked: `cd frontend && npx playwright test --list`.

## Logging

By default the backend logs the plain console pattern defined in
`backend/src/main/resources/logback-spring.xml` (timestamp, thread, level,
`[requestId]`, logger, message — the `requestId` is the X-Request-ID MDC
value from `RequestIdFilter`).

For a log aggregator, opt into structured JSON logging with the
`json-logs` Spring profile — one JSON object per line (`@timestamp`,
`level`, `logger_name`, `thread_name`, `message`, and `requestId` as a
top-level field whenever the MDC carries one), encoded by
logstash-logback-encoder:

```bash
# local dev (H2)
SPRING_PROFILES_ACTIVE=json-logs mvn -f backend/pom.xml spring-boot:run

# compose stack — layer the profile onto `mysql` (the compose file
# forwards SPRING_PROFILES_ACTIVE, defaulting to plain `mysql`)
SPRING_PROFILES_ACTIVE=mysql,json-logs docker compose up
```

Verify: `docker compose logs backend | head -1 | python3 -m json.tool`
parses, and request-handling lines carry the `"requestId"` field.
Honest scope: the profile only shapes the lines — shipping them to an
aggregator (collector, retention, dashboards) is the operator's setup;
nothing of that kind is bundled with this repo.

## Shutdown

`docker compose down` (without `-v`) stops the backend gracefully:

- Compose sends SIGTERM to the backend container and waits
  `stop_grace_period: 40s` (backend service in `docker-compose.yml`)
  before escalating to SIGKILL.
- The backend runs with `server.shutdown=graceful` and
  `spring.lifecycle.timeout-per-shutdown-phase: 30s`
  (`application.yml`): the web server stops accepting new requests
  and in-flight HTTP requests (a pay, an upload) get up to 30 s to
  finish instead of being cut off by an abrupt JVM exit. The 40 s
  grace period sits just above the 30 s drain window so the drain
  can complete first.

Honest scope: this drains HTTP requests only. The `@Scheduled`
sweeps (nightly retention purge, pending-order expiry) are not
transactional with shutdown — they are idempotent and batch-tolerant
by design, so a sweep interrupted mid-batch simply re-runs its
remaining work on the next start/cadence.

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
