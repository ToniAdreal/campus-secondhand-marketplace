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
    audit/               append-only audit log for sensitive transitions
                         (password change, 2FA enable, order complete/refund,
                         ADMIN disable/enable)
    auth/                register/login/refresh/logout, JWT + RBAC, token-bucket
                         auth rate limiting
    common/              ApiResponse envelope, GlobalExceptionHandler, shared
                         domain exception types (no project-internal deps —
                         see layering rules below)
    item/                Item entity, repository, service, controller, photo upload
    job/                 scheduled cross-domain maintenance (data-retention
                         sweep); the only package allowed to touch several
                         domains' stores
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
             a refresh-token family also has an absolute lifetime —
             30 d from the original login (`app.jwt.refresh-max-age`), after
             which refresh 401s and revokes the family (even for an unexpired
             token); /logout revokes the caller's whole refresh-token family
             server-side and clears the cookie with an expired Set-Cookie.
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
             to 409 instead of surfacing as a 500 at commit. A capture with
             the mock-only test token `tok_decline` (pay body's optional
             `paymentToken`) declines → 402, order stays PENDING, and
             nothing is remembered under the order's PSP idempotency key,
             so a retry with a good token still succeeds.
             IdempotencyKeyService — same key + same item replays the original
             order; concurrent same-key losers get 409 while IN_PROGRESS.
             Tables: orders, idempotency_key.
message/     MessageController  POST /api/messages, GET /api/messages?itemId=
             Offline v1 (no WebSocket). Sender is taken from the JWT principal;
             threads are readable only by participants (third party → 403).
             Table: message (sender/receiver/item as plain ids + FKs).
common/      ApiResponse<T> {code, message, data} envelope,
             GlobalExceptionHandler (400/401/403/404/409/413/422 → JSON),
             shared domain exception types, UploadWebConfig (/uploads/**
             static mapping), RequestIdFilter (X-Request-ID echo + MDC,
             JVM-local). common/ depends on NO project package.
job/         DataRetentionService — the nightly @Scheduled sweep (retention
             via app.cleanup.*) that purges stale refresh_token and terminal
             idempotency_key rows. The only cross-domain package: it
             legitimately touches auth/ and order/ stores.
audit/       AuditService — append-only trail (Flyway V22 audit_log:
             actor_user_id, action, target_type, target_id, created_at; no
             payload column, no foreign keys, so a row survives deletion of
             its target and stores no new PII). Called from inside the
             audited transition's own transaction — password change, TOTP
             enable, ADMIN disable/enable, order complete/refund — so a
             rolled-back transition leaves no row and idempotent no-op
             repeats write none. Write-only in v1: no read endpoint yet.
```

Intended package dependency direction (enforced by ArchUnit — see
`backend/src/test/java/com/toni/marketplace/ArchitectureTest.java`; the
build fails on violation):

```
common/ ◀── auth/  item/  order/  message/      (domains depend only on the kernel)
auth/   ◀── message/                            (sender/receiver lookups)
order/  ──╳── auth/ web classes                 (order never imports auth web
                                                classes; shared needs go via
                                                common/ types)
*Controller ──▶ *Service ──▶ *Repository        (controllers never touch
                                                repositories directly)
job/    ──▶ auth/, order/ stores                (scheduled sweeps only)
```

The exception types used to live in auth/ and item/ while the
GlobalExceptionHandler lived in common/ — an inverted edge (common → auth).
They were moved into common/ so the kernel is genuinely dependency-free;
InvalidRefreshTokenException stays in auth/ (it extends the common
InvalidTokenException). CategoryController previously injected
CategoryRepository directly; it now goes through CategoryService.

Frontend (`frontend/src`) is a React 18 SPA: `api/client.ts` holds the axios
instance with the in-memory access token and the single-flight 401 refresh queue
(parallel 401s → one refresh call, then retries); `api/` modules per domain;
`hooks/useDebouncedSearchParam` syncs search to the URL; `store/useAuthStore`
is the Zustand auth store; TanStack Query mutations invalidate the shared
`['items']` query root instead of hand-editing the cache;
`components/ErrorBoundary` wraps the route outlet in `App` — a crashed route
renders a "Something went wrong" fallback with a back-to-home reset instead
of unmounting the tree into a blank page (render errors only, logged via
`console.error`; no remote error reporting).

## Feature scope notes

Honest status of what each landed feature actually covers — and what it does
not. (This is a portfolio/reconstruction project: no production deployment,
no real users, no real money.)

| Feature | What it does | What it does not do |
|---|---|---|
| JWT auth (access + rotating refresh) | HS256 access (15 min) + single-use rotating refresh tokens in an httpOnly `SameSite=Lax` cookie; replay of a consumed refresh token revokes the whole family (with a 60 s grace window so two concurrent tabs don't kill each other — replay after the window still revokes); identical 401s for unknown user vs wrong password (no enumeration oracle); POST /api/auth/logout revokes the caller's whole refresh-token family server-side and clears the httpOnly cookie with an expired Set-Cookie; token-bucket rate limits — 5 attempts/min per client IP on /login and /register (a successful login resets the bucket), 30/min on /refresh, 30 sends/min per authenticated user on POST /api/messages (keyed on the JWT principal id; requests without a parseable token fall back to a per-IP bucket — anonymous hammering is still throttled); per-account lockout — 5 consecutive failed logins lock the account for 15 min (423 + `Retry-After`; a success resets the counter; unknown identifiers still get the identical 401); password strength rules on register and on password change; POST /api/auth/password requires the current password, rotates the refresh-token family, and bumps the user's token_version so every previously issued access token 401s immediately (the filter checks the tver claim against the row on each request); usernames and emails are lowercased at register and matched case-insensitively at login; the mysql profile refuses to boot with the committed dev JWT secret; password reset (backlog #90): POST /api/auth/password-reset always answers the identical 200 envelope whether or not the account exists (no enumeration oracle); for an existing account it issues a single-use token — 32 random bytes, only its SHA-256 hash stored (Flyway V20), 30 min expiry (`app.auth.password-reset.token-ttl`), a new request invalidates prior unused tokens — delivered through the PasswordResetMailSender seam (honest scope: no mail server exists in this project, so the default sender logs the request at INFO with the token redacted and delivers nothing; a real deployment injects an SMTP/SES sender); POST /api/auth/password-reset/confirm {token,newPassword} applies the strength rules (weak → 400, token not consumed), bumps token_version and revokes every refresh family (the #38 kill semantics), consumes the token, and clears any login lockout; unknown/used/expired tokens all get the identical 401; both endpoints share their own 5/min-per-IP rate-limit bucket; the SPA has no reset pages yet (frontend follow-up, deliberately out of scope) a refresh-token family also has an absolute lifetime: 30 d from the original login (`app.jwt.refresh-max-age`) — a refresh past the cap returns 401 and revokes only that family (even when the presented token is unexpired), forcing a fresh login; a nightly @Scheduled job purges expired/revoked refresh tokens (`app.cleanup.refresh-token-retention`, default 30 d) and terminal idempotency rows (default 90 d); GET /api/auth/me returns the caller's profile (id/username/email/roles — never the password hash) so the SPA can restore a session after a reload; per-session management: GET /api/auth/sessions lists the caller's live sessions (device label from the User-Agent + IP captured at login/refresh, the presented session flagged current) and DELETE /api/auth/sessions/{id} revokes one session without touching the others — unknown ids and other users' ids are 404 (no cross-user oracle); TOTP two-factor (opt-in): POST /api/auth/2fa/setup (authenticated, returns Base32 secret + otpauth:// URI) → POST /api/auth/2fa/enable (valid 6-digit code flips it on, ±1 step clock skew) → login on a 2FA-enabled account answers 202 with a 5-min signed challenge (`totp-challenge` type, accepted by exactly one endpoint) instead of a token pair, exchanged with a valid code at POST /api/auth/2fa/authenticate; TOTP is RFC 6238 SHA-1, pure JDK (no new dependency); secrets are encrypted at rest (backlog #89): AES-256-GCM via the JDK only, key from `APP_TOTP_ENCRYPTION_KEY` (Base64, 32 bytes), random 12-byte IV per row, stored as a versioned `v1:<iv>:<ciphertext>` string in the V19-widened column; legacy pre-#89 plaintext rows still verify and are re-encrypted on the next enable/authenticate; the mysql profile refuses to boot with the committed dev placeholder key; recovery codes (backlog #91): enabling 2FA also issues 10 one-time recovery codes in the enable response (shown once — only their SHA-256 hashes are stored, Flyway V21); POST /api/auth/2fa/authenticate accepts a recovery code in place of the 6-digit code against the same 202 challenge and consumes it (replay → the identical 401 as a wrong TOTP code; typing is forgiving — hyphens optional, case-insensitive); GET /api/auth/2fa/recovery-codes/count (authenticated) reports how many remain, never the codes; re-running setup/enable invalidates the previous set; the password-reset flow deliberately does not bypass 2FA — a recovery code is the fallback for a lost authenticator | Rate limiting is JVM-local, not distributed; logout still revokes every session on every device (per-session inspection/revoke exists via GET/DELETE /api/auth/sessions, but the logout button remains all-sessions) |
| RBAC | ADMIN role; `@PreAuthorize("hasRole('ADMIN')")` on DELETE /api/items/{id} and on the seller-scoped order transitions (complete/refund) as an override; 403 in the JSON envelope; ADMIN user management: POST /api/admin/users/{id}/disable sets a `disabled` flag on the account (Flyway V18) — login answers the identical 401 as a bad password, live Bearer tokens are rejected by the auth filter, and every refresh-token family is revoked (disable also bumps `token_version`, so pre-disable tokens stay dead after a re-enable); POST .../enable restores login; guards: an admin cannot disable themselves or another ADMIN (403), unknown id is 404 | Only a handful of operations are RBAC-guarded; most seller actions are scoped by listing ownership, not roles; there is no admin UI and no user list/search endpoint yet — disable/enable is API-only, by user id |
| Listings | POST /api/items with jakarta validation; MapStruct DTOs (no JPA in JSON); category taxonomy (Flyway V3; the taxonomy list is cached in a JVM-local `ConcurrentMapCacheManager` region — no new dependency — and evicted by the ADMIN create/delete endpoints, since it is read on every browse render and written almost never); debounced URL-synced `?q=` search; URL-synced `?categoryId=` category filter on the browse page (dropdown fed by GET /api/categories, combinable with the search box, "All categories" clears it); price-range filter + allowlisted sort on the browse page (GET /api/items `minPriceCents`/`maxPriceCents` inclusive bounds, @PositiveOrZero, min>max → 400; `sort` ∈ newest/price-asc/price-desc resolved to a fixed ORDER BY — never raw user input — threaded through both the LIKE path and the MySQL fulltext native query, where an explicit price sort replaces relevance ordering; the SPA's min/max ¥ inputs and sort select are URL-synced as `?minPrice=`/`?maxPrice=`/`?sort=` and compose with `?q=`/`?categoryId=`); photo upload (UUID filename, type/size validated, served under /uploads/**); seller (or ADMIN) edit title/description/price/category via PATCH /api/items/{id} (partial update, omits left untouched; price change 422 on RESERVED — the order snapshotted amountCents — and on SOLD; status still on its own endpoint); photo replace/delete cleans up the orphaned file on disk (best-effort — a file-delete failure never rolls back the row delete) | Search is MySQL `FULLTEXT(title, description)` + `MATCH … AGAINST` in natural-language mode, relevance-ordered, on the mysql profile (index from Flyway `db/vendor/mysql/V15`; local H2 has no `MATCH` support so it keeps the case-insensitive LIKE path — the service picks the path from the JDBC database product at runtime); natural-language quirks apply on MySQL: words shorter than `ft_min_word_len` (default 4) and stopwords are ignored, so a query made only of those returns nothing; photos persist on local disk (in docker-compose they live in the `uploads-data` named volume mounted at /app/uploads, so they survive a container recreate; `docker compose down -v` drops them together with mysql-data); SOLD is one-way (status machine); on a SOLD or RESERVED listing title/description/category stay editable but price is locked |
| N+1 fix | List/detail/filter reads are single-SELECT JPQL DTO projections, asserted by Hibernate statistics in slice tests; composite index on (category_id, status, created_at) | Index benefit was asserted on H2/MySQL-compat SQL shape, not measured with production-scale data — no benchmark numbers are claimed |
| Orders | Optimistic locking (@Version); one active order per item via a DB-generated unique guard column; Idempotency-Key header replays the original order (REQUIRES_NEW reserve, 10-min IN_PROGRESS reclaim); two-buyer race proven exactly-once by a Testcontainers MySQL 8 IT (CI), plus a double-pay storm IT proving exactly one PENDING→PAID transition (@Version fail-fast → 409 for the losers); order creation flips the listing AVAILABLE→RESERVED atomically in one transaction; POST /api/orders/{id}/pay is a mock capture (PENDING→PAID, buyer-only, idempotent replay, explicit flush so a version conflict maps to 409; the mock-only `tok_decline` payment token declines → 402 with the order left PENDING and no capture remembered under its PSP key, so a good-token retry succeeds); buyer order reads via GET /api/orders (paginated) + /api/orders/{id}; seller order reads via GET /api/seller/orders (+ /{id}); buyer cancel of PENDING orders (POST /api/orders/{id}/cancel → CANCELLED, listing flips back to AVAILABLE); seller/ADMIN confirm handoff (POST /api/orders/{id}/complete → COMPLETED, listing RESERVED→SOLD); seller/ADMIN refund (POST /api/orders/{id}/refund → REFUNDED, listing → AVAILABLE); stale PENDING orders expire automatically (scheduled job, `app.orders.pending-ttl` default 30m, 409-tolerant against a concurrent pay) | The payment provider is a mock — no real money moves; every capture/refund carries an order-scoped idempotency key (generated once, stored on the order row — a retry after a crash between the PSP call and the commit replays instead of double-charging/refunding) |
| Audit log | Append-only trail for sensitive transitions (Flyway V22 `audit_log`: actor_user_id, action, target_type, target_id, created_at — no payload column, no foreign keys, so rows survive target deletion and store no new PII): password changes and TOTP enables (actor = the account holder), ADMIN disable/enable (actor = the acting admin), order complete/refund (actor = the seller/ADMIN caller). The write joins the transition's own transaction — a rolled-back transition leaves no row — and idempotent no-op repeats (re-complete, re-refund, re-disable, re-enable) write none | Write-only in v1: no read endpoint or admin UI yet (operators read via SQL); password-reset confirm is not audited yet (its actor is a token holder, not an authenticated principal — attribution is a declared follow-up) |
| Buyer↔seller messaging v1 | POST/GET /api/messages?itemId=; chronological threads; sender from JWT (no impersonation); 403 for non-participants; 422 messaging yourself; the thread endpoint is paginated (`?page=`/`?size=`, cap 50 — chronological within the page, newest page first) | Offline only — no WebSocket, no read receipts, no notifications; plain `message_body` column (BODY is an H2 keyword) |
| Frontend auth flow | Login page wired to /api/auth/login; zustand store with in-memory token; GET /api/auth/me on boot restores the session after a page reload (silent refresh via the httpOnly cookie, then hydrate); the store's logout calls POST /api/auth/logout (server revokes the refresh family; a failed call still clears local state); single-flight 401 refresh with queued retries, verified by unit tests; a /settings page wires POST /api/auth/password (current + new password; 401 → "current password is incorrect", 400 → the backend's strength reason verbatim; on success the honest note "your other devices have been signed out" is shown); a /settings/sessions page (linked from /settings) lists the caller's live sessions from GET /api/auth/sessions — device label + IP + last active per row, the presented session flagged "This device" — and revokes one session per row via DELETE /api/auth/sessions/{id} (revoking the current session asks for confirmation first, then clears local state and bounces to /login without calling the all-sessions logout, so other devices stay signed in) | Access token lives in memory only — a reload wipes it and the boot sequence must round-trip the refresh cookie to restore the session; no social login |
| Listing UX | Create-listing form with client validation mirroring the backend; envelope field-errors mapped onto fields; optional photo upload; TanStack Query invalidation on mutations; buy-now button on the item detail page (fresh Idempotency-Key per click, 409 mapped to friendly copy, success lands on /orders/:id); order confirmation page wires the mock capture — "Pay now (demo)" button for PENDING orders (disabled while in flight; PAID orders show the paid note, no second capture); my-orders page (/orders) with status chips + load-more pagination; seller orders page (/seller/orders) with status chips + load-more, whose PAID rows carry the seller lifecycle buttons — "Complete order" (POST /api/orders/{id}/complete confirms the handoff, listing flips to SOLD) and "Refund order" (POST /api/orders/{id}/refund, confirm dialog first, mock refund, listing becomes AVAILABLE again) — with 422 responses mapped to friendly copy ("already completed" etc.) and success invalidating both the seller-orders and items query subtrees; buyer "Cancel order" button (PENDING only, confirm dialog, success flips the listing back to AVAILABLE) + seller/ADMIN "Mark as sold" button (own listings only); message thread UI with a "Load earlier messages" button stitching older pages on top (newest page stays anchored at the bottom) — a 403 for non-participants shows a privacy note instead of the thread | Error messages are not yet localized || Observability / testing | GitHub Actions CI (unit + Testcontainers MySQL 8 IT + frontend build/test); docker-compose smoke test with a manual checklist in docs/smoke-test.md; spring-boot-starter-actuator with public /actuator/health+info (everything else behind auth); Micrometer Prometheus export on /actuator/prometheus (behind auth; scrape target documented in docs/smoke-test.md); auth-domain counters on the same export — `auth.login.success`, `auth.login.failure`, `auth.lockout.triggered`, `auth.refresh.theft_detected`, `auth.password.changed` (the event is encoded in the counter name; no username/id/IP tags, by design); order/payment counters on the same export — `orders.created`, `orders.paid`, `orders.cancelled`, `orders.completed`, `orders.refunded`, `orders.expired` (transitions only: idempotent replays — same Idempotency-Key, re-pay, re-cancel, re-complete, re-refund, re-expire — do not count; expiry is counted separately from buyer cancels); JaCoCo line-coverage gate ≥ 80% on `mvn verify` (currently ~94%); X-Request-ID correlation filter — an incoming id is echoed on the response, a UUID is generated when missing, and the id is logged via MDC on every request line | No alerting — Prometheus gives metrics, not alerts; X-Request-ID is JVM-local correlation only, no distributed-trace propagation; ; security response headers on every response (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`; HSTS opt-in via `app.security.headers.hsts-enabled` for HTTPS deployments, off by default for local HTTP dev); Content-Security-Policy opt-in via `app.security.headers.csp-enabled` (strict policy — `default-src 'self'`, `script-src 'self'`, `img-src 'self' data:`, `connect-src 'self' ws:` for Vite HMR; no `'unsafe-inline'`, verified against the real Vite build output) | No alerting — Prometheus gives metrics, not alerts; X-Request-ID is JVM-local correlation only, no distributed-trace propagation; integration tests only run in CI — this sandbox has no Docker; Content-Security-Policy is opt-in and off by default — enable it per deployment (`app.security.headers.csp-enabled`); in the compose stack nginx serves the SPA's HTML, so the policy must be mirrored at the proxy to cover HTML documents (the backend flag covers the API + `/uploads/**`) |
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
- API docs: with the backend running, `GET /v3/api-docs` (OpenAPI JSON) and
  `/swagger-ui.html` — both require a Bearer access token (same rule as
  `/actuator/metrics`; the route map is not public in this demo's threat
  model). Honest scope: the contract is generated from the controllers by
  springdoc; response codes springdoc cannot infer (402/409/422/423/202)
  are annotated by hand and pinned by `OpenApiContractTest`.

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
- [x] Auth rate limiting (/login + /register at 5/min/IP, /refresh at 30/min, /api/messages sends at 30/min/user)
- [x] Password strength rules + password change (rotates the refresh-token family)
- [x] Password reset flow (POST /api/auth/password-reset + /confirm — enumeration-identical request, single-use hash-only tokens, 30 min expiry, mail-sender seam with a log-only default, own 5/min rate-limit bucket; SPA pages are a follow-up)
- [x] Refresh-rotation grace window (60 s, concurrent-tab safe)
- [x] GET /api/auth/me — session restore on SPA reload
- [x] Username/email case normalization (register + login)
- [x] MySQL-profile fail-fast on the committed dev JWT secret
- [x] Nightly purge of stale refresh tokens / terminal idempotency rows
- [x] Mock payment capture + full order lifecycle: cancel (buyer), complete (seller/ADMIN), refund (seller/ADMIN), PENDING-order expiry
- [x] Buyer order reads + my-orders page; seller order reads (backend)
- [x] Cancel-order + mark-SOLD buttons; buy-now + "Pay now (demo)" button
- [x] Change-password settings page
- [x] My-sessions page (/settings/sessions — per-session device list + revoke-one; revoking the current session confirms first, then signs this tab out)
- [x] Seller complete/refund buttons on the seller orders page (PAID rows only; refund confirms first; 422 → friendly copy)
- [x] X-Request-ID correlation filter (echo + MDC, JVM-local)
- [x] OpenAPI contract via springdoc — `/v3/api-docs` + Swagger UI (both behind Bearer auth, like `/actuator/metrics`; the {code,message,data} envelope and the non-inferrable codes — login 202/423, pay 402/409, transitions 422 — are annotated on the controllers)
- [x] Prometheus metrics export on /actuator/prometheus (behind auth)
- [x] Auth security counters (login success/failure, lockout triggered, refresh theft detected, password changed) on /actuator/prometheus
- [x] Order/payment counters (created, paid, cancelled, completed, refunded, expired — transitions only, idempotent replays excluded) on /actuator/prometheus
- [x] Content-Security-Policy header (opt-in via `app.security.headers.csp-enabled`; strict `'self'`-only policy, no `'unsafe-inline'`)
- [x] Uploads persist across compose recreates (`uploads-data` named volume at /app/uploads)
- [x] Seller username on listing DTOs — ad-hoc JPQL join on the raw `sellerId` FK in the list/detail projections (single SELECT, no N+1; the detail page renders "Sold by <username>"; usernames are visible to anyone who can read a listing, as on any marketplace)
- [x] Category-list cache — `GET /api/categories` served from a JVM-local ConcurrentMap cache (spring-context built-in, no new dependency), evicted on ADMIN category create/delete; a rejected write does not flush the cache; no TTL and not shared across instances (single-JVM demo scope)
- [x] ADMIN disable/enable user — POST /api/admin/users/{id}/disable (+ /enable); disabled accounts get the identical login 401, live Bearer tokens are rejected, and all refresh families are revoked; self-disable and ADMIN-on-ADMIN are 403, unknown id 404; re-enable restores login but never resurrects pre-disable tokens
- [x] Price-range filter + sort on the browse page — GET /api/items accepts inclusive `minPriceCents`/`maxPriceCents` (@PositiveOrZero; min>max → 400 envelope) and an allowlisted `sort` (newest / price-asc / price-desc; unknown → 400, resolved to a fixed ORDER BY, never raw input) on both the LIKE and MySQL-fulltext paths; HomePage adds min/max ¥ inputs + a sort select, all URL-synced (`?minPrice=`/`?maxPrice=`/`?sort=`) and combinable with search + category
