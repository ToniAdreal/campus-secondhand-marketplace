# Observability guide

How to tell whether this marketplace is healthy, using only what the
repo actually exposes: Spring Boot Actuator endpoints, the Micrometer
counters and timers on the Prometheus export, the alert rules in
[docs/alert-rules.yml](alert-rules.yml), and the frontend error
boundary + telemetry beacon. Nothing here needs a third-party service.

Honest scope up front: this repo provides the endpoints, the metrics,
the alert rules, and an operator runbook
([docs/runbook.md](runbook.md)). It does **not** deploy or bundle
Prometheus, Alertmanager, Grafana, or any log aggregator — those run in
the operator's own infrastructure. Every counter and timer below is
JVM-local and resets when the backend restarts, so none of them is a
durable aggregate; durable history lives in the database rows (orders,
audit log, webhook ledger) and in the operator's own Prometheus.

## Endpoints

| Endpoint | Auth | What it answers |
|---|---|---|
| `GET /actuator/health` | Public | Overall `UP`/`DOWN`. Component names + statuses are shown (`show-components: always`); component details (DB vendor, disk paths) stay hidden. |
| `GET /actuator/health/liveness` | Public | Liveness only — never the database, so a DB outage cannot restart-loop the app. Point an orchestrator's liveness probe here. |
| `GET /actuator/health/readiness` | Public | `readinessState` + `db` + `uploadStorage` (backlog #154 — a full or unwritable upload directory stops routing, never restarts). Point a load balancer's readiness probe here. |
| `GET /actuator/info` | Public | App name + description from `info.app.*`. |
| `GET /actuator/metrics` | Bearer token | Per-metric values in JSON (same registry as the Prometheus export). |
| `GET /actuator/prometheus` | Bearer token | The whole Micrometer registry in Prometheus text format (`jvm_*`, `http.server.requests`, `hikaricp_*` plus every counter and timer below). |

Only `health`, `info`, `metrics`, and `prometheus` are exposed on the
web (`management.endpoints.web.exposure.include`); nothing else under
`/actuator/**` is registered. Every other API route needs a Bearer token
too — see [docs/smoke-test.md](smoke-test.md) for the curl examples
(the compose stack's `docker-compose-smoke` CI job curls
`/actuator/health` after boot as its liveness check).

### Getting a token for the metrics endpoints

Log in with a real account (`POST /api/auth/login`) and use the
returned access token as `Authorization: Bearer <token>`. Access
tokens live 15 minutes and refresh through the httpOnly
`refresh_token` cookie (`POST /api/auth/refresh`), so a Prometheus
scraper cannot use a single static token: the operator must rotate the
token (login + refresh on a schedule) or terminate scraping behind
their own reverse proxy. There is no service account or long-lived API
token in this repo — that is a deliberate gap, stated here rather than
papered over. The scrape target shape (for an external Prometheus) is
documented in [docs/smoke-test.md](smoke-test.md).

## Counters and timers on `/actuator/prometheus`

Counter names encode the event; no counter carries a username, user
id, or IP tag, by design.

### Auth

| Counter | Fires when |
|---|---|
| `auth.login.success` / `auth.login.failure` | A login attempt completes / is rejected for an unknown identifier or a wrong password. Locked-account attempts do not feed `auth.login.failure`. |
| `auth.lockout.triggered` | An account crosses the failed-login threshold and locks (once per lock transition, not per locked attempt). |
| `auth.refresh.theft_detected` | A consumed refresh token is replayed after the rotation grace window; the whole token family is revoked. |
| `auth.password.changed` | A password change completes. |
| `auth.second_factor.totp.success` / `.failure`, `auth.second_factor.passkey.success` / `.failure` | One increment per completed second-factor exchange (backlog #336). Only genuine code/assertion verification failures move a failure counter — disabled accounts, unenrolled factors, proofs for a factor the account does not hold, unknown credential ids, and undecryptable stored secrets never count. |

### Orders, payments, webhooks, payouts

| Counter | Fires when |
|---|---|
| `orders.created`, `orders.paid`, `orders.cancelled`, `orders.completed`, `orders.refunded`, `orders.expired`, `orders.shipped`, `orders.disputed`, `orders.handover_confirmed` | The named transition happens (backlog #348). Transitions only: idempotent replays — same Idempotency-Key, re-pay, re-cancel, re-complete, re-refund, re-expire, re-ship, re-open — do not count. Expiry is counted separately from buyer cancels; a dispute resolve counts on `orders.refunded`, never on `orders.disputed`; an ADMIN-override completion or a shipped-path receipt confirmation counts on `orders.completed`, never on `orders.handover_confirmed`. |
| `payments.declined` | A mock-PSP capture attempt is declined. An attempt outcome, not a transition: the order stays PENDING and each declined attempt counts. |
| `payments.webhook.matched` / `.mismatched` / `.replayed` | A mock-PSP webhook delivery reconciles / fails to reconcile against an order in the matching state / replays an already-seen event id. |
| `payouts.promoted`, `payouts.paid`, `payouts.skipped`, `payouts.held` | One increment per row outcome in the hourly payout sweep (backlog #341), mirroring the sweep's own counts and run log. A sweep that never acquires its single-instance lease moves nothing. |
| `scheduler.jobs.skipped` | A scheduled job run is skipped (e.g. the lease is held elsewhere). |

### Latency timers (backlog #153)

`auth.login.duration`, `auth.refresh.duration`,
`orders.create.duration`, `orders.pay.duration` — each with a single
`outcome` tag (`success`/`failure`) and no username/id/IP tags. Login
and refresh record exactly one sample per call; the order timers mirror
the counters' rule — they time attempted creations/captures only, so
idempotent replays (same Idempotency-Key, re-pay of a PAID order) and
pre-attempt rejections record no sample.

### Built-ins

`http.server.requests` (per-route latency + status counts — the 5xx
rate alert reads this), `jvm_*` (memory, GC, threads), and
`hikaricp_*` (connection-pool use) come from Micrometer/Spring Boot
with no app code.

## Alerts

[docs/alert-rules.yml](alert-rules.yml) defines ten alerts over the
counters above plus the `http.server.requests` 5xx rate:

- `AuthLoginFailureRateHigh`, `AuthLockoutSurge`,
  `AuthRefreshTheftDetected`
- `PaymentDeclineRateHigh`, `WebhookMismatchRateHigh`
- `Http5xxRateHigh`
- `PayoutHeldGrowing`, `PayoutSweepPaysNothing`
- `OrderDisputeRateHigh`, `PaidOrdersNeverHandoverConfirmed`

[docs/runbook.md](runbook.md) gives each alert a short section: what
it means, the first three checks, and what it is **not**. The alerts
concern the **mock** PSP — no real money moves in this project, so a
payment alert is a code/integration signal, never a financial
incident. The referenced-metric list is pinned by
`AlertRulesContractTest`: adding a counter the rules do not know, or a
rule over a counter that does not exist, fails the build.

## Frontend error boundaries and telemetry

A render error in any route must not blank the whole app:

- `frontend/src/components/ErrorBoundary.tsx` wraps the route outlet
  in `App.tsx`. On a caught render/lifecycle error it renders a
  fallback inside the app shell ("Something went wrong", header and
  nav stay usable, "Back to home" resets the boundary) and sends one
  best-effort telemetry beacon. It catches render/lifecycle errors in
  the route subtree only — errors in event handlers, async code, and
  the shell itself are not caught by any React error boundary.
- `frontend/src/api/telemetry.ts` installs `window` `error` and
  `unhandledrejection` hooks in `main.tsx`, so uncaught errors outside
  the boundary are still counted once each even though they render no
  fallback.

The beacon goes to `POST /api/telemetry/errors` (backlog #223),
public by necessity — a crash can happen on an anonymous page — with
its own per-IP rate-limit bucket (60/min) and a 4 KB body cap (413
over it, 400 on a malformed/empty/missing name, 204 on accept). Only
three fields are ever logged or counted: the sanitized error name
(known JS error names, anything else collapses to `other`), the route
template (`:id` for concrete segments, `unknown` for unmatched paths),
and the app version. The message, the stack, stack URLs' queries, and
any storage/cookie data are never logged, counted, or persisted, and
nothing is stored at all — the beacon only increments
`frontend_errors_total` (tagged by sanitized error name) on the
Prometheus export and writes one `WARN` log line. A beacon is a
client's claim, not proof: any client can POST a fabricated beacon to
this public endpoint, so treat the counter as a signal for spotting a
broken release, never as evidence about a user. No alert fires from
inside the app; an operator's own rules can watch
`frontend_errors_total`.

## Request correlation and logs

- `X-Request-ID`: an incoming id is echoed on the response; a UUID is
  generated when missing, and the id is logged via MDC on every
  request line. Quote it when reporting a problem — it is the join key
  between a client-visible failure and the server log.
- W3C trace context (backlog #214): a strictly validated inbound
  `traceparent` contributes its trace-id (malformed values are ignored
  and never echoed), a fresh span-id is minted per request, both join
  the MDC, and the response echoes `traceparent` for the caller to
  continue the trace. This is correlation only — no OpenTelemetry
  exporter, span reporting, or outgoing-call propagation is bundled,
  so this is not distributed tracing.
- Structured JSON logging (backlog #137): the opt-in `json-logs`
  Spring profile (`logback-spring.xml` + logstash-logback-encoder)
  emits one JSON object per console line (`@timestamp` / `level` /
  `logger_name` / `message`, plus `requestId` from the MDC as a
  top-level field); the default stays the plain-text pattern. Enable it
  in the compose stack with
  `SPRING_PROFILES_ACTIVE=mysql,json-logs`. The profile shapes log
  lines only — no log shipping, aggregation, or retention is bundled.

## What observability deliberately does not cover

- No Prometheus/Alertmanager/Grafana deployed or bundled; no log
  shipping, aggregation, or retention.
- Counters and timers are JVM-local and reset on restart; the
  per-order declines an operator needs are the durable
  `payment_attempt` rows behind the `/admin/payments` attempts viewer,
  not an aggregate counter.
- Trace context is correlation only (see above), not distributed
  tracing.
- The ADMIN dashboard (`GET /api/admin/dashboard`, backlog #196) is a
  counts-only aggregate over existing stores — it is the
  "is the marketplace healthy today" view, not a replacement for the
  Prometheus export.
