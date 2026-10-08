# Campus Second-hand Marketplace

A full-stack campus second-hand marketplace: students post used items, search and filter,
message sellers, and mark items sold. Monorepo with a Spring Boot backend and a React frontend.

> **Portfolio project.** This is a personal reconstruction/portfolio build, developed in the
> open to demonstrate full-stack work with Spring Boot and React. It is not production code
> and has no real users. Roadmap items are tracked as GitHub issues in this repo.

## Stack (pinned versions)

| Layer    | Technology |
|----------|------------|
| Backend  | Java 17, Spring Boot 3.2.5 (Maven), Spring Data JPA (Hibernate 6), Spring Security 6, Flyway, MapStruct 1.5.5, jjwt 0.12.6 |
| Database | MySQL 8 (Flyway-managed schema); H2 for local slice tests |
| Frontend | React 18.3, TypeScript 5.6, Vite 5, React Router 6, TanStack Query 4, Zustand 4, Axios 1.7, Tailwind CSS 3 |
| Testing  | JUnit 5 + Mockito (unit), Spring Boot `@DataJpaTest` slice tests, Testcontainers + MySQL 8 (integration, runs in CI), Vitest + React Testing Library |
| Delivery | GitHub Actions CI, Docker, docker-compose (jar + MySQL + Nginx) |

## Repo layout

```
backend/                 Spring Boot 3.2 API (Java 17, Maven)
  src/main/java/com/toni/marketplace
    auth/                register/login/refresh/logout, JWT + RBAC, token-bucket
                         auth rate limiting
    common/              ApiResponse envelope, GlobalExceptionHandler
    item/                Item entity, repository, service, controller, photo upload
    message/             offline buyer↔seller messaging (v1)
    order/               Order entity, idempotent order creation, mock payment capture
  src/main/resources
    application.yml      H2 by default for local dev; mysql profile for Docker
    db/migration/        Flyway migrations (schema is migration-managed, never hbm2ddl)
  src/test               @DataJpaTest slice tests (H2); *IT Testcontainers tests (CI only)
frontend/                React 18 + Vite 5 + TypeScript
  src/api/               client.ts (Axios: in-memory access token, httpOnly refresh
                         cookie, single-flight 401 refresh with request queueing)
                         + domain modules: auth, items, orders, categories, messages
  src/pages/             Home (listing), ItemDetail, Login, CreateListing (sell),
                         OrderConfirmation (after buy-now)
  src/components/        MessageThread (buyer↔seller thread on the item detail page)
  src/store/             Zustand auth/UI store
.github/workflows/ci.yml  backend unit + integration (Testcontainers) + frontend build/test
docker-compose.yml        mysql + backend jar + nginx (serves frontend, proxies /api)
```

## Architecture

```
Browser ──(compose)──▶ nginx :80 ── /api ──▶ Spring Boot :8080 ──▶ MySQL 8 :3306
                      ├─ / (SPA)                            │        (Flyway V1–V7)
Browser ──(local dev)──▶ Vite :5173 ── /api ──▶ Spring Boot :8080 ──▶ H2 (in-memory)
```

Request path inside the backend (package `com.toni.marketplace`):

```
JwtAuthenticationFilter ──▶ SecurityConfig (stateless)
      │  Bearer <redacted> ── userId principal, ROLE_USER / ROLE_ADMIN authorities.
      │  Public: /api/auth/** (except POST /api/auth/logout), /uploads/**,
      │  /actuator/health + /actuator/info. Everything else needs a Bearer <redacted>
      │  (including /actuator/prometheus, exposed via management endpoints).
      ▼
auth/        AuthController  /api/auth/register, /login, /refresh, /logout
             AuthService + JwtTokenService — jjwt HS256, access 15 min,
             refresh 7 d, single-use rotation in the httpOnly `refresh_token`
             cookie; replay of a consumed refresh token revokes the whole family;
             /logout revokes the caller's whole refresh-token family server-side
             and clears the cookie with an expired Set-Cookie.
             BCrypt(cost 12) password hashing. Tables: app_user, refresh_token.
             AuthRateLimitFilter — token bucket per client IP: 5 attempts/min on
             /api/auth/login and /api/auth/register (a successful login
             resets the bucket), 30/min on /api/auth/refresh (JVM-local,
             not distributed).
      ▼
item/        ItemController  CRUD + ?q= search + ?categoryId= filter, pagination
             ItemService / ItemRepository — JPQL DTO projections for list/detail
             (fetch join, single SELECT — no N+1), composite index
             (category_id, status, created_at). Item entity carries @Version.
             ImageStorageService — MultipartFile, UUID filename (client name
             discarded), allowlisted image types, 5 MB cap, served under
             /uploads/** from app.uploads.dir (default ./uploads).
             ItemSecurity — seller-or-ADMIN @PreAuthorize policy for
             DELETE and photo upload.
order/       OrderController  POST /api/orders (Idempotency-Key header),
             POST /api/orders/{id}/pay (mock capture), GET /api/orders
             + /api/orders/{id} (buyer reads)
             OrderService — @Version optimistic locking; one active order per
             item guarded by a DB-generated unique column (NULL when terminal);
             order creation flips the listing AVAILABLE→RESERVED (@Version
             fail-fast → 409 on races). PaymentService is an honest mock PSP
             (no network I/O, no real money): pay() is PENDING→PAID, buyer-only,
             idempotent on replay, explicit flush so a version conflict maps
             to 409 instead of surfacing as a 500 at commit.
             IdempotencyKeyService — same key + same item replays the original
             order; concurrent same-key losers get 409 while IN_PROGRESS.
             Tables: orders, idempotency_key.
message/     MessageController  POST /api/messages, GET /api/messages?itemId=
             Offline v1 (no WebSocket). Sender is taken from the JWT principal;
             threads are readable only by participants (third party → 403).
             Table: message (sender/receiver/item as plain ids + FKs).
common/      ApiResponse<T> {code, message, data} envelope,
             GlobalExceptionHandler (400/401/403/404/409/413/422 → JSON),
             UploadWebConfig (/uploads/** static mapping),
             RequestIdFilter (X-Request-ID echo + MDC, JVM-local).
```

Frontend (`frontend/src`) is a React 18 SPA: `api/client.ts` holds the axios
instance with the in-memory access token and the single-flight 401 refresh queue
(parallel 401s → one refresh call, then retries); `api/` modules per domain;
`hooks/useDebouncedSearchParam` syncs search to the URL; `store/useAuthStore`
is the Zustand auth store; TanStack Query mutations invalidate the shared
`['items']` query root instead of hand-editing the cache.

## Feature scope notes

Honest status of what each landed feature actually covers — and what it does
not. (This is a portfolio/reconstruction project: no production deployment,
no real users, no real money.)

| Feature | What it does | What it does not do |
|---|---|---|
| JWT auth (access + rotating refresh) | HS256 access (15 min) + single-use rotating refresh tokens in an httpOnly `SameSite=Lax` cookie; replay of a consumed refresh token revokes the whole family (with a 60 s grace window so two concurrent tabs don't kill each other — replay after the window still revokes); identical 401s for unknown user vs wrong password (no enumeration oracle); POST /api/auth/logout revokes the caller's whole refresh-token family server-side and clears the httpOnly cookie with an expired Set-Cookie; token-bucket rate limits per client IP — 5 attempts/min on /login and /register (a successful login resets the bucket), 30/min on /refresh; per-account lockout — 5 consecutive failed logins lock the account for 15 min (423 + `Retry-After`; a success resets the counter; unknown identifiers still get the identical 401); password strength rules on register and on password change; POST /api/auth/password requires the current password, rotates the refresh-token family, and bumps the user's token_version so every previously issued access token 401s immediately (the filter checks the tver claim against the row on each request); usernames and emails are lowercased at register and matched case-insensitively at login; the mysql profile refuses to boot with the committed dev JWT secret; a nightly @Scheduled job purges expired/revoked refresh tokens (`app.cleanup.refresh-token-retention`, default 30 d) and terminal idempotency rows (default 90 d); GET /api/auth/me returns the caller's profile (id/username/email/roles — never the password hash) so the SPA can restore a session after a reload | Rate limiting is JVM-local, not distributed; logout kills every session on every device — no per-session device list or single-session revoke (open backlog); a refresh-token family can rotate forever — no absolute family lifetime cap (open backlog) |
| RBAC | ADMIN role; `@PreAuthorize("hasRole('ADMIN')")` on DELETE /api/items/{id} and on the seller-scoped order transitions (complete/refund) as an override; 403 in the JSON envelope | Only a handful of operations are RBAC-guarded; most seller actions are scoped by listing ownership, not roles |
| Listings | POST /api/items with jakarta validation; MapStruct DTOs (no JPA in JSON); category taxonomy (Flyway V3); debounced URL-synced `?q=` search; photo upload (UUID filename, type/size validated, served under /uploads/**); PATCH /api/items/{id}/status lets the seller (or ADMIN) mark a listing SOLD (AVAILABLE/RESERVED → SOLD only); photo replace/delete cleans up the orphaned file on disk (best-effort — a file-delete failure never rolls back the row delete) | Search is LIKE-based, not full-text; photos persist on local disk (in docker-compose they live in the container's /app/uploads and do not survive a container recreate — only mysql-data is a named volume); SOLD is one-way — no general listing edit endpoint |
| N+1 fix | List/detail/filter reads are single-SELECT JPQL DTO projections, asserted by Hibernate statistics in slice tests; composite index on (category_id, status, created_at) | Index benefit was asserted on H2/MySQL-compat SQL shape, not measured with production-scale data — no benchmark numbers are claimed |
| Orders | Optimistic locking (@Version); one active order per item via a DB-generated unique guard column; Idempotency-Key header replays the original order (REQUIRES_NEW reserve, 10-min IN_PROGRESS reclaim); two-buyer race proven exactly-once by a Testcontainers MySQL 8 IT (CI), plus a double-pay storm IT proving exactly one PENDING→PAID transition (@Version fail-fast → 409 for the losers); order creation flips the listing AVAILABLE→RESERVED atomically in one transaction; POST /api/orders/{id}/pay is a mock capture (PENDING→PAID, buyer-only, idempotent replay, explicit flush so a version conflict maps to 409); buyer order reads via GET /api/orders (paginated) + /api/orders/{id}; seller order reads via GET /api/seller/orders (+ /{id}); buyer cancel of PENDING orders (POST /api/orders/{id}/cancel → CANCELLED, listing flips back to AVAILABLE); seller/ADMIN confirm handoff (POST /api/orders/{id}/complete → COMPLETED, listing RESERVED→SOLD); seller/ADMIN refund (POST /api/orders/{id}/refund → REFUNDED, listing → AVAILABLE); stale PENDING orders expire automatically (scheduled job, `app.orders.pending-ttl` default 30m, 409-tolerant against a concurrent pay) | The payment provider is a mock — no real money moves; capture/refund carry no provider-side idempotency key yet (open backlog) |
| Buyer↔seller messaging v1 | POST/GET /api/messages?itemId=; chronological threads; sender from JWT (no impersonation); 403 for non-participants; 422 messaging yourself; the thread endpoint is paginated (`?page=`/`?size=`, cap 50 — chronological within the page, newest page first) | Offline only — no WebSocket, no read receipts, no notifications; plain `message_body` column (BODY is an H2 keyword) |
| Frontend auth flow | Login page wired to /api/auth/login; zustand store with in-memory token; GET /api/auth/me on boot restores the session after a page reload (silent refresh via the httpOnly cookie, then hydrate); the store's logout calls POST /api/auth/logout (server revokes the refresh family; a failed call still clears local state); single-flight 401 refresh with queued retries, verified by unit tests; a /settings page wires POST /api/auth/password (current + new password; 401 → "current password is incorrect", 400 → the backend's strength reason verbatim; on success the honest note "your other devices have been signed out" is shown) | Access token lives in memory only — a reload wipes it and the boot sequence must round-trip the refresh cookie to restore the session; no social login; no per-session device list |
| Listing UX | Create-listing form with client validation mirroring the backend; envelope field-errors mapped onto fields; optional photo upload; TanStack Query invalidation on mutations; buy-now button on the item detail page (fresh Idempotency-Key per click, 409 mapped to friendly copy, success lands on /orders/:id); order confirmation page wires the mock capture — "Pay now (demo)" button for PENDING orders (disabled while in flight; PAID orders show the paid note, no second capture); my-orders page (/orders) with status chips + load-more pagination; buyer "Cancel order" button (PENDING only, confirm dialog, success flips the listing back to AVAILABLE) + seller/ADMIN "Mark as sold" button (own listings only); message thread UI with a "Load earlier messages" button stitching older pages on top (newest page stays anchored at the bottom) — a 403 for non-participants shows a privacy note instead of the thread | Error messages are not yet localized; the seller-orders endpoint (GET /api/seller/orders) has no UI yet (open backlog) || Observability / testing | GitHub Actions CI (unit + Testcontainers MySQL 8 IT + frontend build/test); docker-compose smoke test with a manual checklist in docs/smoke-test.md; spring-boot-starter-actuator with public /actuator/health+info (everything else behind auth); Micrometer Prometheus export on /actuator/prometheus (behind auth; scrape target documented in docs/smoke-test.md); JaCoCo line-coverage gate ≥ 80% on `mvn verify` (currently ~94%); X-Request-ID correlation filter — an incoming id is echoed on the response, a UUID is generated when missing, and the id is logged via MDC on every request line | No alerting — Prometheus gives metrics, not alerts; X-Request-ID is JVM-local correlation only, no distributed-trace propagation; integration tests only run in CI — this sandbox has no Docker |
| Compose ops | mysql healthcheck (mysqladmin ping) + service_healthy depends_on, so the backend no longer boots against a closed 3306 on cold starts | compose is verified statically in this environment; the full up/curl smoke sequence was validated in CI, not locally |

## Run locally

```bash
# backend (H2 in-memory, Flyway migrates on startup)
cd backend && mvn spring-boot:run

# frontend
cd frontend && npm install && npm run dev
```

Full MySQL path: `docker compose up --build` (uses the `mysql` Spring profile).
Step-by-step smoke test: [docs/smoke-test.md](docs/smoke-test.md).

## Testing

- `cd backend && mvn test` — unit + `@DataJpaTest` slice tests (H2, no Docker needed).
- `cd backend && mvn verify -Pintegration` — Testcontainers integration tests against real
  MySQL 8. Requires Docker; runs in GitHub Actions on every push.
- `cd frontend && npm test` — Vitest + React Testing Library.

## Roadmap (from the original project)

- [x] Monorepo scaffold, CI, docker-compose
- [x] JWT auth (jjwt, access + rotating refresh, RBAC admin)
- [x] Consistent `{code, message, data}` error envelope (validation, `ResponseStatusException`, unexpected)
- [x] Listing CRUD with MapStruct DTOs (no lazy associations in JSON)
- [x] N+1 fix: fetch-join + DTO projection + composite index on (category, status, created_at)
- [x] Optimistic locking (`@Version` on `Item`, covered by a slice test)
- [x] Order entity + idempotent order creation via client request id
- [x] MultipartFile image upload (UUID filename, type/size validation)
- [x] Axios 401 single-flight refresh queue (unit-tested, no backend needed)
- [x] TanStack Query invalidation, debounced URL-synced search
- [x] Offline message table (v1, no WebSocket)
- [x] JaCoCo line-coverage gate (≥ 80% on `mvn verify`)
- [x] Listing status PATCH (seller marks a listing SOLD)
- [x] Photo orphan cleanup on replace/delete (best-effort)
- [x] Message thread pagination (backend `?page=`/`?size=`) + "load earlier messages" (frontend)
- [x] Logout (POST /api/auth/logout) + frontend logout wiring
- [x] Auth rate limiting (/login + /register at 5/min/IP, /refresh at 30/min)
- [x] Password strength rules + password change (rotates the refresh-token family)
- [x] Refresh-rotation grace window (60 s, concurrent-tab safe)
- [x] GET /api/auth/me — session restore on SPA reload
- [x] Username/email case normalization (register + login)
- [x] MySQL-profile fail-fast on the committed dev JWT secret
- [x] Nightly purge of stale refresh tokens / terminal idempotency rows
- [x] Mock payment capture + full order lifecycle: cancel (buyer), complete (seller/ADMIN), refund (seller/ADMIN), PENDING-order expiry
- [x] Buyer order reads + my-orders page; seller order reads (backend)
- [x] Cancel-order + mark-SOLD buttons; buy-now + "Pay now (demo)" button
- [x] Change-password settings page
- [x] X-Request-ID correlation filter (echo + MDC, JVM-local)
- [x] Prometheus metrics export on /actuator/prometheus (behind auth)
