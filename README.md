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
             token); signing-key rotation (backlog #113): tokens are signed
             with the current key only, stamped with its `kid`
             (`app.jwt.current-kid`); during a rotation the previous key
             (`app.jwt.previous-secret` / `previous-kid`) verifies ONLY
             until `app.jwt.previous-accept-until` and never signs — an
             unknown `kid` gets the same 401 as a bad signature, and an
             in-window old-key refresh rotates onto the new key, migrating
             the session; /logout revokes the caller's whole refresh-token family
             server-side and clears the cookie with an expired Set-Cookie.
             BCrypt(cost 12) password hashing. Tables: app_user, refresh_token.
             AuthRateLimitFilter — token bucket per client IP: 5 attempts/min on
             /api/auth/login and /api/auth/register (a successful login
             resets the bucket), 30/min on /api/auth/refresh (JVM-local,
             not distributed). Client IP is the raw remote address unless
             that address is listed in `app.security.trusted-proxies`
             (backlog #104, empty by default), in which case
             X-Forwarded-For is honored via its first untrusted hop.
      ▼
item/        ItemController  CRUD + ?q= search + ?categoryId= filter, pagination
             ItemService / ItemRepository — JPQL DTO projections for list/detail
             (fetch join, single SELECT — no N+1), composite index
             (category_id, status, created_at). Item entity carries @Version.
             ImageStorageService — MultipartFile, UUID filename (client name
             discarded), allowlisted image types, magic-byte check against the declared type, 5 MB cap, served under
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
             via app.cleanup.*) that purges stale refresh_token, terminal
             idempotency_key, used-or-expired password_reset_token and
             aged-out audit_log rows; OrphanUploadSweeper — the nightly
             sweep that deletes
             upload files no item/item_photo row references once they
             outlive app.uploads.orphan-grace (default 24 h). The only
             cross-domain package: it
             legitimately touches auth/, order/ and audit/ stores.
audit/       AuditService — append-only trail (Flyway V22 audit_log:
             actor_user_id, action, target_type, target_id, created_at; no
             payload column, no foreign keys, so a row survives deletion of
             its target and stores no new PII). Called from inside the
             audited transition's own transaction — password change,
             password-reset confirm, TOTP
             enable, ADMIN disable/enable, order pay / buyer-cancel /
             complete / refund — so a
             rolled-back transition leaves no row and idempotent no-op
             repeats write none. Read side (backlog #109):
             GET /api/admin/audit-log (ADMIN-only, paginated, exact-match
             filters actorUserId / action / targetType+targetId).
```

Intended package dependency direction (enforced by ArchUnit — see
`backend/src/test/java/com/toni/marketplace/ArchitectureTest.java`; the
build fails on violation):

```
common/ ◀── auth/  item/  order/  message/  audit/   (domains depend only on the kernel)
auth/   ◀── message/                            (sender/receiver lookups)
order/  ──╳── auth/ web classes                 (order never imports auth web
                                                classes; shared needs go via
                                                common/ types)
audit/  ──╳── auth/ item/ order/ message/ job/  (the trail is a leaf: it depends on
                                                no domain package — its own repository,
                                                a Clock, and common/ types only)
auth/, order/ services ──▶ AuditService         (transitions record from the service
                                                layer, inside their own transaction;
                                                no controller records, and no controller
                                                outside audit/ touches AuditService —
                                                audit/'s own ADMIN controller reads only)
*Controller ──▶ *Service ──▶ *Repository        (controllers never touch
                                                repositories directly)
job/    ──▶ auth/, order/, audit/ stores        (scheduled sweeps only)
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
| JWT auth (access + rotating refresh) | HS256 access (15 min) + single-use rotating refresh tokens in an httpOnly `SameSite=Lax` cookie; replay of a consumed refresh token revokes the whole family (with a 60 s grace window so two concurrent tabs don't kill each other — replay after the window still revokes); identical 401s for unknown user vs wrong password (no enumeration oracle); POST /api/auth/logout revokes the caller's whole refresh-token family server-side and clears the httpOnly cookie with an expired Set-Cookie; token-bucket rate limits — 5 attempts/min per client IP on /login and /register (a successful login resets the bucket), 30/min on /refresh, 30 sends/min per authenticated user on POST /api/messages (keyed on the JWT principal id; requests without a parseable token fall back to a per-IP bucket — anonymous hammering is still throttled); per-account lockout — 5 consecutive failed logins lock the account for 15 min (423 + `Retry-After`; a success resets the counter; unknown identifiers still get the identical 401); password strength rules on register and on password change; POST /api/auth/password requires the current password, rotates the refresh-token family, and bumps the user's token_version so every previously issued access token 401s immediately (the filter checks the tver claim against the row on each request); usernames and emails are lowercased at register and matched case-insensitively at login; the mysql profile refuses to boot with the committed dev JWT secret; password reset (backlog #90): POST /api/auth/password-reset always answers the identical 200 envelope whether or not the account exists (no enumeration oracle); for an existing account it issues a single-use token — 32 random bytes, only its SHA-256 hash stored (Flyway V20), 30 min expiry (`app.auth.password-reset.token-ttl`), a new request invalidates prior unused tokens — delivered through the PasswordResetMailSender seam (honest scope: no mail server exists in this project, so the default sender logs the request at INFO with the token redacted and delivers nothing; a real deployment injects an SMTP/SES sender); POST /api/auth/password-reset/confirm {token,newPassword} applies the strength rules (weak → 400, token not consumed), bumps token_version and revokes every refresh family (the #38 kill semantics), consumes the token, and clears any login lockout; unknown/used/expired tokens all get the identical 401; both endpoints share their own 5/min-per-IP rate-limit bucket; the SPA reset pages landed in backlog #99 (/forgot-password renders the identical success state for any identifier and never claims an email was sent — honest copy notes the log-only default sender; /reset-password?token= maps a weak password's 400 reason verbatim and unknown/used/expired 401s to one generic message, and a successful confirm lands on /login with a signed-out-everywhere note) a refresh-token family also has an absolute lifetime: 30 d from the original login (`app.jwt.refresh-max-age`) — a refresh past the cap returns 401 and revokes only that family (even when the presented token is unexpired), forcing a fresh login; a nightly @Scheduled job purges expired/revoked refresh tokens (`app.cleanup.refresh-token-retention`, default 30 d), terminal idempotency rows (default 90 d), used-or-expired password-reset tokens (`app.cleanup.password-reset-retention`, default 30 d — brief security-audit evidence; a still-valid unused token is never purged, so an in-flight reset survives the sweep) and audit_log rows past `app.cleanup.audit-log-retention` (default 365 d, backlog #118); GET /api/auth/me returns the caller's profile (id/username/email/roles — never the password hash) so the SPA can restore a session after a reload; per-session management: GET /api/auth/sessions lists the caller's live sessions (device label from the User-Agent + IP captured at login/refresh, the presented session flagged current) and DELETE /api/auth/sessions/{id} revokes one session without touching the others — unknown ids and other users' ids are 404 (no cross-user oracle); TOTP two-factor (opt-in): POST /api/auth/2fa/setup (authenticated, returns Base32 secret + otpauth:// URI) → POST /api/auth/2fa/enable (valid 6-digit code flips it on, ±1 step clock skew) → login on a 2FA-enabled account answers 202 with a 5-min signed challenge (`totp-challenge` type, accepted by exactly one endpoint) instead of a token pair, exchanged with a valid code at POST /api/auth/2fa/authenticate; TOTP is RFC 6238 SHA-1, pure JDK (no new dependency); secrets are encrypted at rest (backlog #89): AES-256-GCM via the JDK only, key from `APP_TOTP_ENCRYPTION_KEY` (Base64, 32 bytes), random 12-byte IV per row, stored as a versioned `v1:<iv>:<ciphertext>` string in the V19-widened column; legacy pre-#89 plaintext rows still verify and are re-encrypted on the next enable/authenticate; the mysql profile refuses to boot with the committed dev placeholder key; recovery codes (backlog #91): enabling 2FA also issues 10 one-time recovery codes in the enable response (shown once — only their SHA-256 hashes are stored, Flyway V21); POST /api/auth/2fa/authenticate accepts a recovery code in place of the 6-digit code against the same 202 challenge and consumes it (replay → the identical 401 as a wrong TOTP code; typing is forgiving — hyphens optional, case-insensitive); GET /api/auth/2fa/recovery-codes/count (authenticated) reports how many remain, never the codes; re-running setup/enable invalidates the previous set; TOTP disable (backlog #121): POST /api/auth/2fa/disable (authenticated) turns 2FA off — it requires the current password AND a valid TOTP code or an unused recovery code (a recovery code used here is consumed; the password is checked first, so a wrong-password attempt never burns a one-time code), answers the identical 401 for a wrong password or a wrong code, 422 when 2FA is not enabled, and shares the #101 lockout below (an active lock answers 423 even for correct proofs; wrong-code attempts feed the same counter, wrong-password attempts feed none — the password proof is not a second-factor guess); on success the secret is cleared, the whole recovery set is deleted, the lockout counters reset, a TOTP_DISABLED audit row is written in the same transaction, and the current session survives (no token_version bump, no refresh revocation — mirroring enable, which also leaves sessions alone); the password-reset flow deliberately does not bypass 2FA — a recovery code is the fallback for a lost authenticator; second-factor brute-force defences (backlog #101): POST /api/auth/2fa/authenticate has its own 5/min-per-IP rate-limit bucket (separate from the credential bucket, never reset on success), and failed exchanges feed a per-account counter (Flyway V23) — 5 consecutive failures lock the exchange for 15 min (423 + `Retry-After`, even for a correct code under a fresh challenge), a successful exchange resets the counter, and the lock deliberately never blocks password login (login still verifies the password and issues challenges; only exchanging them is refused) | Rate limiting is JVM-local, not distributed; logout still revokes every session on every device (per-session inspection/revoke exists via GET/DELETE /api/auth/sessions, but the logout button remains all-sessions) |
| RBAC | ADMIN role; `@PreAuthorize("hasRole('ADMIN')")` on DELETE /api/items/{id} and on the seller-scoped order transitions (complete/refund) as an override; 403 in the JSON envelope; ADMIN user management: POST /api/admin/users/{id}/disable sets a `disabled` flag on the account (Flyway V18) — login answers the identical 401 as a bad password, live Bearer tokens are rejected by the auth filter, and every refresh-token family is revoked (disable also bumps `token_version`, so pre-disable tokens stay dead after a re-enable); POST .../enable restores login; guards: an admin cannot disable themselves or another ADMIN (403), unknown id is 404 | Only a handful of operations are RBAC-guarded; most seller actions are scoped by listing ownership, not roles; GET /api/admin/users (backlog #105) lists/searches users (paginated, newest first, optional q= case-insensitive substring over username/email, AdminUserDto rows — never a password hash) so an admin can find the id that disable/enable acts on; admin users page (backlog #111): /admin/users in the SPA — the nav entry and route render only for ADMIN sessions (client-side hiding only; the backend @PreAuthorize stays the real gate, a non-admin session bounces home with no request, and a 403 from the API renders an honest not-authorized state, never a fake list), a paginated table (Previous/Next) over the #105 endpoint with the search box synced to ?q= through the same debounced URL param as the browse page, and disable/enable buttons wired to the POST endpoints (disable confirms first because it signs the account out everywhere; self and other-ADMIN rows render no disable button, mirroring the backend's 403 guards; a successful action invalidates the users query so the table refetches) |
| Listings | POST /api/items with jakarta validation; MapStruct DTOs (no JPA in JSON); category taxonomy (Flyway V3; the taxonomy list is cached in a JVM-local `ConcurrentMapCacheManager` region — no new dependency — and evicted by the ADMIN create/delete endpoints, since it is read on every browse render and written almost never); debounced URL-synced `?q=` search; URL-synced `?categoryId=` category filter on the browse page (dropdown fed by GET /api/categories, combinable with the search box, "All categories" clears it); price-range filter + allowlisted sort on the browse page (GET /api/items `minPriceCents`/`maxPriceCents` inclusive bounds, @PositiveOrZero, min>max → 400; `sort` ∈ newest/price-asc/price-desc resolved to a fixed ORDER BY — never raw user input — threaded through both the LIKE path and the MySQL fulltext native query, where an explicit price sort replaces relevance ordering; the SPA's min/max ¥ inputs and sort select are URL-synced as `?minPrice=`/`?maxPrice=`/`?sort=` and compose with `?q=`/`?categoryId=`); photo upload (UUID filename, type/size/magic-byte validated, served under /uploads/**); seller (or ADMIN) edit title/description/price/category via PATCH /api/items/{id} (partial update, omits left untouched; price change 422 on RESERVED — the order snapshotted amountCents — and on SOLD; status still on its own endpoint); photo replace/delete cleans up the orphaned file on disk (best-effort — a file-delete failure never rolls back the row delete) | Search is MySQL `FULLTEXT(title, description)` + `MATCH … AGAINST` in natural-language mode, relevance-ordered, on the mysql profile (index from Flyway `db/vendor/mysql/V15`; local H2 has no `MATCH` support so it keeps the case-insensitive LIKE path — the service picks the path from the JDBC database product at runtime); natural-language quirks apply on MySQL: words shorter than `ft_min_word_len` (default 4) and stopwords are ignored, so a query made only of those returns nothing; photos persist on local disk (in docker-compose they live in the `uploads-data` named volume mounted at /app/uploads, so they survive a container recreate; `docker compose down -v` drops them together with mysql-data); SOLD is one-way (status machine); on a SOLD or RESERVED listing title/description/category stay editable but price is locked |
| N+1 fix | List/detail/filter reads are single-SELECT JPQL DTO projections, asserted by Hibernate statistics in slice tests; composite index on (category_id, status, created_at) | Index benefit was asserted on H2/MySQL-compat SQL shape, not measured with production-scale data — no benchmark numbers are claimed |
| Orders | Optimistic locking (@Version); one active order per item via a DB-generated unique guard column; Idempotency-Key header replays the original order (REQUIRES_NEW reserve, 10-min IN_PROGRESS reclaim); two-buyer race proven exactly-once by a Testcontainers MySQL 8 IT (CI), plus a double-pay storm IT proving exactly one PENDING→PAID transition (@Version fail-fast → 409 for the losers), and cancel-vs-pay / expiry-vs-pay transition-race ITs proving exactly one transition wins with a consistent order+listing pair (PAID+RESERVED or CANCELLED+AVAILABLE, never a half-written pair) and the loser's orders.* counter unmoved; order creation flips the listing AVAILABLE→RESERVED atomically in one transaction; POST /api/orders/{id}/pay is a mock capture (PENDING→PAID, buyer-only, idempotent replay, explicit flush so a version conflict maps to 409; the mock-only `tok_decline` payment token declines → 402 with the order left PENDING and no capture remembered under its PSP key, so a good-token retry succeeds); buyer order reads via GET /api/orders (paginated) + /api/orders/{id}; seller order reads via GET /api/seller/orders (+ /{id}); both order lists accept an optional ?status= filter (backlog #123 — bound to the OrderStatus enum, anything else → 400 envelope; omitting it keeps the unfiltered list, and pagination totals reflect the filtered set); seller sales summary via GET /api/seller/orders/summary (backlog #108 — per-status counts + gross over the PAID/COMPLETED orders' amountCents price snapshots, so a later listing price edit never rewrites history and REFUNDED orders never count; all-time plus the current calendar month in the server-local time zone; the seller orders page header renders orders / completed / gross from that one query; a seller with no orders gets zeros, not an error); buyer cancel of PENDING orders (POST /api/orders/{id}/cancel → CANCELLED, listing flips back to AVAILABLE); seller/ADMIN confirm handoff (POST /api/orders/{id}/complete → COMPLETED, listing RESERVED→SOLD); seller/ADMIN refund (POST /api/orders/{id}/refund → REFUNDED, listing → AVAILABLE); stale PENDING orders expire automatically (scheduled job, `app.orders.pending-ttl` default 30m, 409-tolerant against a concurrent pay) | The payment provider is a mock — no real money moves; every capture/refund carries an order-scoped idempotency key (generated once, stored on the order row — a retry after a crash between the PSP call and the commit replays instead of double-charging/refunding) |
| Audit log | Append-only trail for sensitive transitions (Flyway V22 `audit_log`: actor_user_id, action, target_type, target_id, created_at — no payload column, no foreign keys, so rows survive target deletion and store no new PII): password changes, TOTP enables and TOTP disables (backlog #121; actor = the account holder), password-reset completes (backlog #110 — actor = target = the account the reset token resolved to; the caller is a token holder, not an authenticated principal, but the token names exactly one account, and the row stays distinct from an authenticated PASSWORD_CHANGED), ADMIN disable/enable (actor = the acting admin), order pay (actor = the buyer) and buyer-cancel (actor = the cancelling buyer) (backlog #122), order complete/refund (actor = the seller/ADMIN caller). The write joins the transition's own transaction — a rolled-back transition leaves no row (a declined capture writes none) — and idempotent no-op repeats (re-pay, re-cancel, re-complete, re-refund, re-disable, re-enable) write none; stale-order expiry is deliberately NOT audited — it is a system transition with no actor, and stamping the buyer as actor would falsify the trail; read side (backlog #109): GET /api/admin/audit-log — ADMIN-only, paginated newest-first, optional exact-match filters actorUserId / action / targetType+targetId (enum filters accept allowlisted values only, anything else is a 400), rows are AuditLogDto projections, never the entity | Audit-log page (backlog #120): /admin/audit-log in the SPA — ADMIN-only nav entry + route with client-side hiding only (backend @PreAuthorize stays the real gate; a non-admin session bounces home with no request, a 403 renders an honest not-authorized state, never a fake/empty trail), a paginated table (actor, action, target, time) over GET /api/admin/audit-log, and the four filters (actorUserId / action / targetType / targetId) synced to the URL query string (action/targetType as allowlisted selects fed by the backend enum values, so the SPA never sends a value the backend would 400); retention (backlog #118): the nightly purge deletes audit rows older than `app.cleanup.audit-log-retention` (default 365 d) by created_at only — never by action/actor — so the trail is append-only within the window and operators export before purge for longer evidence |
| Buyer↔seller messaging v1 | POST/GET /api/messages?itemId=; chronological threads; sender from JWT (no impersonation); 403 for non-participants; 422 messaging yourself; the thread endpoint is paginated (`?page=`/`?size=`, cap 50 — chronological within the page, newest page first); read state (backlog #106): Flyway V24 `read_at` — opening a thread stamps the caller-as-receiver rows read in the same transaction (a sender's view stamps nothing, the 403 path stamps nothing), `MessageDto.readAt` exposes it, GET /api/messages/unread-count returns the caller's total unread (count only, no body data), and the SPA nav shows an unread badge above zero (invalidated on thread view and on send); seller replies + send guard (backlog #112): POST /api/messages only accepts buyer → seller (opens a thread) or seller → a buyer with an existing thread on that listing (a reply) — every other pairing is 403, closing the pre-#112 free-form send where anyone could message anyone about someone else's listing; the SPA groups the seller's view per buyer (picker when several buyers wrote, never a merged mega-thread) and the reply box addresses the selected buyer, derived from the thread itself | Offline only — no WebSocket, no notifications, no per-item badge list (a single global nav count only); the read stamp means "the receiver opened the thread", not a per-message delivery guarantee; plain `message_body` column (BODY is an H2 keyword) |
| Frontend auth flow | Login page wired to /api/auth/login; zustand store with in-memory token; GET /api/auth/me on boot restores the session after a page reload (silent refresh via the httpOnly cookie, then hydrate); the store's logout calls POST /api/auth/logout (server revokes the refresh family; a failed call still clears local state); single-flight 401 refresh with queued retries, verified by unit tests; a /settings page wires POST /api/auth/password (current + new password; 401 → "current password is incorrect", 400 → the backend's strength reason verbatim; on success the honest note "your other devices have been signed out" is shown); a /settings/sessions page (linked from /settings) lists the caller's live sessions from GET /api/auth/sessions — device label + IP + last active per row, the presented session flagged "This device" — and revokes one session per row via DELETE /api/auth/sessions/{id} (revoking the current session asks for confirmation first, then clears local state and bounces to /login without calling the all-sessions logout, so other devices stay signed in); two-factor frontend (backlog #100): the login page handles the 202 TOTP challenge — a code step whose single field accepts a 6-digit authenticator code OR a recovery code, exchanged at POST /api/auth/2fa/authenticate, with a wrong code showing the backend's identical 401 copy — and the /settings page carries a two-factor section: setup renders the Base32 secret + otpauth:// URI as text (no QR library), enable renders the response's recovery codes exactly once with a "shown once, store them safely" warning (the codes are never re-fetched — navigating away loses them, by design), and a remaining-count readout from GET /api/auth/2fa/recovery-codes/count that silently degrades to no readout when the count request fails; a turn-off control (backlog #121) appears when that readout says 2FA is on (or this session just enabled it) — its confirm step requires the current password plus an authenticator or recovery code and posts to POST /api/auth/2fa/disable, a 401 maps to the shared "current password or code is incorrect" copy (no oracle), and success resets the section to the enrollment offer with an honest "your password alone now signs you in" note | Access token lives in memory only — a reload wipes it and the boot sequence must round-trip the refresh cookie to restore the session; no social login |
| Listing UX | Create-listing form with client validation mirroring the backend; envelope field-errors mapped onto fields; optional photo upload; TanStack Query invalidation on mutations; buy-now button on the item detail page (fresh Idempotency-Key per click, 409 mapped to friendly copy, success lands on /orders/:id); order confirmation page wires the mock capture — "Pay now (demo)" button for PENDING orders (disabled while in flight; PAID orders show the paid note, no second capture); the mock PSP references persisted on the order row (backlog #114 — captureId set by pay, refundId set by refund, both null before either happens) render on the confirmation page and on both order lists only when non-null, labelled "Payment reference (demo): cap_mock_…" / "Refund reference (demo): rfd_mock_…" — demo references a support flow would quote, never a real processor receipt (backlog #119); my-orders page (/orders) with status chips + load-more pagination; seller orders page (/seller/orders) with status chips + load-more, whose PAID rows carry the seller lifecycle buttons — "Complete order" (POST /api/orders/{id}/complete confirms the handoff, listing flips to SOLD) and "Refund order" (POST /api/orders/{id}/refund, confirm dialog first, mock refund, listing becomes AVAILABLE again) — with 422 responses mapped to friendly copy ("already completed" etc.) and success invalidating both the seller-orders and items query subtrees; buyer "Cancel order" button (PENDING only, confirm dialog, success flips the listing back to AVAILABLE) + seller/ADMIN "Mark as sold" button (own listings only); message thread UI with a "Load earlier messages" button stitching older pages on top (newest page stays anchored at the bottom) — a 403 for non-participants shows a privacy note instead of the thread | Error messages are not yet localized || Observability / testing | GitHub Actions CI (unit + Testcontainers MySQL 8 IT + frontend build/test); docker-compose smoke test with a manual checklist in docs/smoke-test.md; spring-boot-starter-actuator with public /actuator/health+info (everything else behind auth); Micrometer Prometheus export on /actuator/prometheus (behind auth; scrape target documented in docs/smoke-test.md); auth-domain counters on the same export — `auth.login.success`, `auth.login.failure`, `auth.lockout.triggered`, `auth.refresh.theft_detected`, `auth.password.changed` (the event is encoded in the counter name; no username/id/IP tags, by design); order/payment counters on the same export — `orders.created`, `orders.paid`, `orders.cancelled`, `orders.completed`, `orders.refunded`, `orders.expired` (transitions only: idempotent replays — same Idempotency-Key, re-pay, re-cancel, re-complete, re-refund, re-expire — do not count; expiry is counted separately from buyer cancels), plus `payments.declined` for mock-PSP capture declines (an attempt outcome, not a transition: the order stays PENDING, each declined attempt counts); JaCoCo line-coverage gate ≥ 80% on `mvn verify` (currently ~94%); X-Request-ID correlation filter — an incoming id is echoed on the response, a UUID is generated when missing, and the id is logged via MDC on every request line | No alerting — Prometheus gives metrics, not alerts; X-Request-ID is JVM-local correlation only, no distributed-trace propagation; ; security response headers on every response (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`; HSTS opt-in via `app.security.headers.hsts-enabled` for HTTPS deployments, off by default for local HTTP dev); Content-Security-Policy opt-in via `app.security.headers.csp-enabled` (strict policy — `default-src 'self'`, `script-src 'self'`, `img-src 'self' data:`, `connect-src 'self' ws:` for Vite HMR; no `'unsafe-inline'`, verified against the real Vite build output) | No alerting — Prometheus gives metrics, not alerts; X-Request-ID is JVM-local correlation only, no distributed-trace propagation; integration tests only run in CI — this sandbox has no Docker; Content-Security-Policy is opt-in and off by default — enable it per deployment (`app.security.headers.csp-enabled`); in the compose stack nginx serves the SPA's HTML, so the policy must be mirrored at the proxy to cover HTML documents (the backend flag covers the API + `/uploads/**`) |
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
  springdoc; response codes springdoc cannot infer (402/409/422/423/202/429)
  are annotated by hand, and `OpenApiContractTest` pins the exact controller
  path set (38 paths — a new endpoint fails the test until the contract is
  extended deliberately) plus those codes (402 on pay only).

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
- [x] Message read state + unread count (Flyway V24 `read_at`, thread view marks receiver rows, nav unread badge)
- [x] Seller replies in a listing thread + send participant guard (backlog #112: per-buyer seller view/reply box; POST only buyer → seller or seller → buyer with an existing thread on that listing, other pairings 403)
- [x] JaCoCo line-coverage gate (≥ 80% on `mvn verify`)
- [x] Listing status PATCH (seller marks a listing SOLD)
- [x] Photo orphan cleanup on replace/delete (best-effort)
- [x] Message thread pagination (backend `?page=`/`?size=`) + "load earlier messages" (frontend)
- [x] Logout (POST /api/auth/logout) + frontend logout wiring
- [x] Auth rate limiting (/login + /register at 5/min/IP, /refresh at 30/min, /api/messages sends at 30/min/user)
- [x] Password strength rules + password change (rotates the refresh-token family)
- [x] Password reset flow (POST /api/auth/password-reset + /confirm — enumeration-identical request, single-use hash-only tokens, 30 min expiry, mail-sender seam with a log-only default, own 5/min rate-limit bucket; SPA pages /forgot-password + /reset-password landed in backlog #99)
- [x] Refresh-rotation grace window (60 s, concurrent-tab safe)
- [x] GET /api/auth/me — session restore on SPA reload
- [x] Username/email case normalization (register + login)
- [x] MySQL-profile fail-fast on the committed dev JWT secret
- [x] JWT signing-key rotation (backlog #113 — `kid`-stamped signings with the current key only; previous key verifies inside a configurable window only, never signs; unknown `kid` = the same 401 as a bad signature; the mysql-profile placeholder refusal covers the previous-secret slot too)
- [x] Nightly purge of stale refresh tokens / terminal idempotency rows / used-or-expired password-reset tokens
- [x] Nightly orphan-upload sweep (rollback orphans deleted after a 24 h grace window)
- [x] Mock payment capture + full order lifecycle: cancel (buyer), complete (seller/ADMIN), refund (seller/ADMIN), PENDING-order expiry
- [x] Buyer order reads + my-orders page; seller order reads (backend)
- [x] Cancel-order + mark-SOLD buttons; buy-now + "Pay now (demo)" button
- [x] Change-password settings page
- [x] My-sessions page (/settings/sessions — per-session device list + revoke-one; revoking the current session confirms first, then signs this tab out)
- [x] Two-factor frontend (backlog #100 — login 202 challenge step accepting a TOTP code or a recovery code in one field; /settings enrollment with the secret/otpauth URI as text, recovery codes shown exactly once, and a failure-tolerant remaining-count readout)
- [x] TOTP authenticate throttle + lockout-feed (backlog #101 — own 5/min-per-IP bucket for /api/auth/2fa/authenticate; per-account failure counter (V23) locks the exchange after 5 consecutive failures for 15 min, a successful exchange resets it, and password login is deliberately unaffected)
- [x] TOTP disable (backlog #121 — POST /api/auth/2fa/disable: current password + TOTP/recovery proof (password checked first, recovery code consumed on success), identical 401 for wrong password/code, 422 when 2FA is off, failed code proofs feed the shared #101 lockout and an active lock answers 423, secret cleared + recovery set deleted + counters reset on success, TOTP_DISABLED audit row in the same transaction, current session survives; /settings gains a confirm-step turn-off control shown when the count readout says 2FA is on)
- [x] Seller complete/refund buttons on the seller orders page (PAID rows only; refund confirms first; 422 → friendly copy)
- [x] Mock PSP references in the SPA (backlog #119 — Order type carries captureId/refundId from backlog #114; the shared OrderPspReferences component renders them on the order confirmation page and the my-orders / seller-orders rows only when non-null, labelled "(demo)" — never presented as a real processor receipt)
- [x] Order-list status filter (backlog #123 — optional ?status= on GET /api/orders and GET /api/seller/orders, bound to the OrderStatus enum so only allowlisted values reach the new findByBuyerIdAndStatus / findByItem_SellerIdAndStatus queries; invalid → 400 envelope, a status with no orders → an empty page, pagination totals reflect the filtered set; scoping unchanged — buyer list stays own-orders only, seller list stays own-listings only, no ADMIN bypass)
- [x] Seller sales summary (backlog #108 — GET /api/seller/orders/summary: per-status counts + gross from PAID/COMPLETED amountCents snapshots, REFUNDED excluded, all-time + current server-local calendar month; seller orders page header renders orders / completed / gross; no-order sellers get zeros)
- [x] X-Request-ID correlation filter (echo + MDC, JVM-local)
- [x] OpenAPI contract via springdoc — `/v3/api-docs` + Swagger UI (both behind Bearer auth, like `/actuator/metrics`; the {code,message,data} envelope and the non-inferrable codes — login 202/423, pay 402/409, transitions 422 — are annotated on the controllers)
- [x] Prometheus metrics export on /actuator/prometheus (behind auth)
- [x] Auth security counters (login success/failure, lockout triggered, refresh theft detected, password changed) on /actuator/prometheus
- [x] Order/payment counters (created, paid, cancelled, completed, refunded, expired — transitions only, idempotent replays excluded; plus payments.declined for declined captures) on /actuator/prometheus
- [x] Content-Security-Policy header (opt-in via `app.security.headers.csp-enabled`; strict `'self'`-only policy, no `'unsafe-inline'`)
- [x] Uploads persist across compose recreates (`uploads-data` named volume at /app/uploads)
- [x] Seller username on listing DTOs — ad-hoc JPQL join on the raw `sellerId` FK in the list/detail projections (single SELECT, no N+1; the detail page renders "Sold by <username>"; usernames are visible to anyone who can read a listing, as on any marketplace)
- [x] Category-list cache — `GET /api/categories` served from a JVM-local ConcurrentMap cache (spring-context built-in, no new dependency), evicted on ADMIN category create/delete; a rejected write does not flush the cache; no TTL and not shared across instances (single-JVM demo scope)
- [x] ADMIN disable/enable user — POST /api/admin/users/{id}/disable (+ /enable); disabled accounts get the identical login 401, live Bearer tokens are rejected, and all refresh families are revoked; self-disable and ADMIN-on-ADMIN are 403, unknown id 404; re-enable restores login but never resurrects pre-disable tokens
- [x] ADMIN users page (backlog #111 — /admin/users: ADMIN-only nav entry + route with client-side hiding (backend @PreAuthorize stays the real gate; non-admin sessions bounce home, a 403 renders an honest not-authorized state), paginated table over GET /api/admin/users with debounced ?q= URL-synced search, disable (confirm first; no button on self/other-ADMIN rows) / enable wired to the POST endpoints with users-query invalidation on success)
- [x] ADMIN audit-log page (backlog #120 — /admin/audit-log: ADMIN-only nav entry + route with client-side hiding (backend @PreAuthorize stays the real gate; non-admin sessions bounce home with no request, a 403 renders an honest not-authorized state), paginated table (actor/action/target/time) over GET /api/admin/audit-log, four filters synced to the URL query string with action/targetType as allowlisted enum selects)
- [x] Audit pay + buyer-cancel (backlog #122 — ORDER_PAID (actor = the buyer) and ORDER_CANCELLED (actor = the cancelling buyer) rows written after the in-transaction flush, exactly like complete/refund: idempotent re-pay / re-cancel write none, a declined capture writes none, a 409 race loss writes none; both actions filterable on GET /api/admin/audit-log and selectable in the SPA's action filter; stale-order expiry deliberately NOT audited — a system transition with no actor)
- [x] Trusted-proxy client-IP resolution for the rate-limit buckets (backlog #104 — `app.security.trusted-proxies`, empty by default; X-Forwarded-For honored only from a listed immediate peer, first untrusted hop wins, so a spoofed prefix cannot pick the bucket key; the resolved identity feeds the credential/refresh/reset/TOTP buckets and the message per-IP fallback)
- [x] Price-range filter + sort on the browse page — GET /api/items accepts inclusive `minPriceCents`/`maxPriceCents` (@PositiveOrZero; min>max → 400 envelope) and an allowlisted `sort` (newest / price-asc / price-desc; unknown → 400, resolved to a fixed ORDER BY, never raw input) on both the LIKE and MySQL-fulltext paths; HomePage adds min/max ¥ inputs + a sort select, all URL-synced (`?minPrice=`/`?maxPrice=`/`?sort=`) and combinable with search + category
