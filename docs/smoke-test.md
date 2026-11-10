# Docker Compose smoke test

Manual end-to-end verification of the full stack on real MySQL 8:

- `mysql` — mysql:8.0, database `marketdb`
- `backend` — Spring Boot jar built in-container, running with the **`mysql` Spring profile**
  (`SPRING_PROFILES_ACTIVE=mysql` → `jdbc:mysql://mysql:3306/marketdb`, Flyway V1–V74
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

The same fail-fast applies to the mock-PSP webhook secret (backlog #366):
the backend will not boot on the `mysql` profile with the committed dev
placeholder for `app.payments.webhook-secret` — `WebhookSecretStartupCheck`
fails fast so anyone who can read the repository cannot forge a
signature-valid PSP callback. Export a third, independent random secret
the same way (the compose file errors out early with a reminder if you
forget). The smoke below signs its webhook deliveries with this value —
never the committed dev placeholder, which the `mysql` profile refuses
to boot with:

```bash
export APP_PAYMENTS_WEBHOOK_SECRET=$(openssl rand -hex 32)
```

The ledger-viewer step of the smoke below needs an ADMIN login, and
registration only ever mints USER accounts — the first admin comes from
the opt-in bootstrap (backlog #213). Export all three together before
`docker compose up` (a partial set fails the boot fast; all unset is a
strict no-op and no admin is seeded):

```bash
export APP_BOOTSTRAP_ADMIN_USERNAME=smokeadmin
export APP_BOOTSTRAP_ADMIN_EMAIL=smokeadmin@example.com
export APP_BOOTSTRAP_ADMIN_PASSWORD=SmokeAdmin1234
```

## Prerequisites

- Docker Engine + Compose v2 (`docker compose`)
- Ports 80, 8080, 3306 free on the host (a local MySQL on 3306 will collide)

## Run

The curls below need a session, so start the stack with the
email-verification gate stubbed off via its documented env knob (backlog
#325 — the same knob the CI `docker-compose-smoke` job exports, and the
same one the e2e job uses for the jar):

```bash
APP_AUTH_EMAIL_VERIFICATION_ENABLED=false docker compose up --build -d
```

With the gate left on (the plain `docker compose up` default), register
does NOT return a session: it answers 201 with
`{"code":0,"message":"ok","data":{"user":{…},"emailVerificationRequired":true}}`
and no access token, and login before confirmation answers 403 — that is
the production confirm-then-login flow (`POST
/api/auth/email-verification/confirm {token}` flips the flag, then login
issues the session), not a smoke failure. The compose smoke cannot run
that flow end to end because no mail server exists here: the default
sender logs the verification request with the token redacted, so no
operator could read a confirmation link out of it. A real deployment
injects an SMTP/SES sender for that step; the smoke shortcut above
stands in for it and changes nothing about the production default.

First boot takes a few minutes: the backend build stage (`maven:3.9.9`) downloads
dependencies, MySQL initializes `marketdb`, and the backend replays the Flyway
migrations. The backend waits for MySQL's healthcheck (`service_healthy`) before
starting, so a cold-boot race is not expected.

Wait for the API to answer **through the nginx `/api` proxy** (not directly on 8080 —
the proxy wiring is part of what this verifies):

```bash
# register through the proxy (username 3-60 chars, valid email, password 12-72 chars — the strength rule's 12 minimum, on top of the DTO's 8-72 range)
# With the gate off (see Run above) register issues a session:
curl -s -X POST http://localhost/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"smoke","email":"smoke@example.com","password":"Sm0kePass123"}'
# → {"code":0,"message":"ok","data":{"accessToken":"…","tokenType":"Bearer",…}}
#    the refresh token is Set-Cookie'd as httpOnly `refresh_token`, never in the body
#    (with the gate on, the same call answers the pending-verification 201
#    described in Run above — no token, by design)
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

### Mock-PSP webhook path (backlog #366)

Pay for the listing, then drive a signed mock-PSP webhook delivery
through the same proxy. A buyer cannot order their own listing (422),
so the buyer is a second user — register one, then create the order
(`Idempotency-Key` is mandatory, a missing header is a 400) and pay it.
The pay response carries the mock capture id, which is the reference
the webhook delivery must carry to reconcile:

```bash
BUYER_TOKEN=$(curl -s -X POST http://localhost/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"smokebuyer","email":"smokebuyer@example.com","password":"Sm0kePass123"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])')

ITEM_ID=1  # the id from the create-listing response above
KEY=$(python3 -c 'import uuid; print(uuid.uuid4())')
ORDER_ID=$(curl -s -X POST http://localhost/api/orders -H "Authorization: Bearer $BUYER_TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"itemId\":$ITEM_ID}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')

CAPTURE_ID=$(curl -s -X POST "http://localhost/api/orders/$ORDER_ID/pay" \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H 'Content-Type: application/json' -d '{}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["captureId"], d; print(d["captureId"])')
# → e.g. cap_mock_… (the mock PSP's reference for this capture)
```

Sign a `capture.succeeded` delivery for the paid order. The signature
is HMAC-SHA256 over `"<t>.<raw body>"` (backlog #149), keyed with the
`APP_PAYMENTS_WEBHOOK_SECRET` value the compose backend booted with.
The body is written to a file first so the signed bytes and the sent
bytes are identical:

```bash
EVENT_ID="evt_manual_$(date +%s)"
SIG=$(EVENT_ID="$EVENT_ID" CAPTURE_ID="$CAPTURE_ID" python3 -c 'import hashlib,hmac,json,os,time; body=json.dumps({"id":os.environ["EVENT_ID"],"type":"capture.succeeded","captureId":os.environ["CAPTURE_ID"]},separators=(",",":")).encode(); open("/tmp/webhook-body.json","wb").write(body); t=str(int(time.time())); print("t="+t+",v1="+hmac.new(os.environ["APP_PAYMENTS_WEBHOOK_SECRET"].encode(),(t+".").encode()+body,hashlib.sha256).hexdigest())')
```

First delivery — the order already carries the capture id and sits
PAID, so the ledger reconciles it as MATCHED:

```bash
curl -s -X POST http://localhost/api/payments/webhooks/mock-psp \
  -H 'Content-Type: application/json' \
  -H "X-Mock-Psp-Signature: $SIG" \
  --data-binary @/tmp/webhook-body.json
# → {"code":0,"message":"ok","data":{"eventId":"evt_manual_…","outcome":"MATCHED","duplicate":false}}
```

Replay the same delivery — same event id, no second ledger row, the
stored outcome returned with `duplicate:true`:

```bash
curl -s -X POST http://localhost/api/payments/webhooks/mock-psp \
  -H 'Content-Type: application/json' \
  -H "X-Mock-Psp-Signature: $SIG" \
  --data-binary @/tmp/webhook-body.json
# → {"code":0,"message":"ok","data":{"eventId":"evt_manual_…","outcome":"MATCHED","duplicate":true}}
```

Tampered signature — the same body under a bogus header answers the
401 envelope and writes no ledger row (a stale timestamp answers the
same 401; the freshness window is 5 minutes):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost/api/payments/webhooks/mock-psp \
  -H 'Content-Type: application/json' \
  -H 'X-Mock-Psp-Signature: t=1,v1=0000000000000000000000000000000000000000000000000000000000000000' \
  --data-binary @/tmp/webhook-body.json
# → 401
```

Finally, read the ledger row back through the ADMIN webhook-events
viewer (backlog #156) — log in as the bootstrap admin exported above
and filter on the MATCHED outcome. Exactly one row carries this event
id, keyed on the capture id:

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"smokeadmin","password":"SmokeAdmin1234"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])')

curl -s 'http://localhost/api/admin/payments/webhooks?outcome=MATCHED' \
  -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -m json.tool | head -30
# → data.content holds one row with eventId "evt_manual_…",
#   outcome "MATCHED", pspReference "<the capture id>"
```

A webhook never moves money state by itself: the MATCHED verdict only
confirms the order was already PAID with that capture id. A delivery
for an unknown reference, an order in the wrong state, or an unknown
event type is a MISMATCH (still 200, so the PSP stops retrying) — the
synchronous pay path stays the source of truth.

Everything except `/api/auth/**`, `/uploads/**`, `/actuator/health`,
`/actuator/health/liveness`, `/actuator/health/readiness`,
`/actuator/info`, and `/error` requires a Bearer token —
an unauthenticated `curl http://localhost/api/items` must return the JSON 401 envelope,
which itself proves the proxy → security filter chain path works.

### Security headers on the SPA (backlog #363)

`frontend/nginx.conf` mirrors the backend's security headers onto the
SPA's HTML documents (the backend flag covers the API + `/uploads/**`
only): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, and the same strict CSP as the backend's
opt-in policy (`default-src 'self'; img-src 'self' data:; script-src
'self'; connect-src 'self' ws:`) with the sha256 hash of the pre-paint
theme script in `frontend/index.html` added to `script-src`, so the
first frame still applies the stored theme. HSTS stays off — local HTTP
dev; it is opt-in on the backend for HTTPS deployments only.

Verify against the running stack:

```bash
curl -s -D - http://localhost/ -o /dev/null
# → HTTP/1.1 200 OK, plus:
#   X-Content-Type-Options: nosniff
#   X-Frame-Options: DENY
#   Referrer-Policy: no-referrer
#   Content-Security-Policy: default-src 'self'; img-src 'self' data:; script-src 'self' 'sha256-…'; connect-src 'self' ws:
```

If `index.html`'s inline theme script ever changes, recompute its hash
(sha256 over the script element's exact text content, base64) and update
`nginx.conf` — a stale hash blocks the script, so the theme degrades to
the main.tsx re-apply with a brief flash, never a broken theme. CI pins
this: the `docker-compose-smoke` job asserts all four headers on the SPA
HTML and cross-checks the hash against the checked-out `index.html`.

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
- Exactly twelve flows (plus the color-contrast pass, described below): (1) register lands on browse signed in;
  (2) browse → a listing's detail page renders; (3) login → create a
  listing → it appears in My listings; (4) two-user buy-now → pay → the
  buyer sees the one-time handover code on the confirmation page and
  the order as PAID in My orders (backlog #194); (5) the seller
  completes the order with the buyer's handover code → the buyer sees
  COMPLETED; (6) offer → seller counters → buyer counter-accepts → the
  order is pinned via the API at the counter price → it pays (backlog
  #213); (7) pay → buyer disputes → the ADMIN resolves with a refund →
  the buyer sees REFUNDED (backlog #213); (8) cart → cart checkout →
  pay → seller completes with the handover code → buyer reviews, with
  the review pinned via the API on the seller's profile (backlog #253);
  (9) buyer messages → seller replies → buyer sees the reply, and a
  third user who never participated opens the same detail page and gets
  the privacy note instead of the thread (backlog #342);
  (10) buyer reports a listing → the ADMIN acts on it in /admin/reports
  → the listing shows WITHDRAWN and stops offering purchase, and a
  dismissed report leaves its listing AVAILABLE (backlog #346);
  (11) buyer favorites a listing from its detail page → it appears on
  /favorites → the buyer unfavorites it back off, then follows the
  listing's seller from the seller profile and the follow state
  survives a reload; a guest sees no favorite control on the detail
  page and /favorites bounces to /login (backlog #352);
  (12) buyer filters the browse page by a keyword → saves the search
  → it appears in the saved-searches section on /favorites with its
  match count → running it lands back on the exact browse URL →
  deleting it removes it, and a browse page with no filter active
  offers no save control (backlog #359).
  Alongside the twelve flows, the color-contrast pass (backlog #369,
  `frontend/e2e/colorContrast.e2e.ts`) runs axe with the
  `color-contrast` rule only over nine key pages in both themes — the
  jsdom axe suite cannot compute contrast, so this pass is where
  contrast is actually audited. It is a pass, not a thirteenth flow.
  Its first CI run is the measurement; genuine failures are fixed or
  filed as follow-ups, never worked around by disabling the rule.
  Flows 5 and 7 seed their PAID orders through the real API (the pay
  response is where the code is disclosed); the completions/resolutions
  themselves are driven in the browser. Selectors are role/label based.
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
