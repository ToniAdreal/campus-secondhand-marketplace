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
    common/              ApiResponse envelope, GlobalExceptionHandler
    item/                Item entity, repository, service, controller
  src/main/resources
    application.yml      H2 by default for local dev; mysql profile for Docker
    db/migration/        Flyway migrations (schema is migration-managed, never hbm2ddl)
  src/test               @DataJpaTest slice tests (H2); *IT Testcontainers tests (CI only)
frontend/                React 18 + Vite 5 + TypeScript
  src/api/client.ts      Axios instance: in-memory access token, httpOnly refresh cookie,
                         single-flight 401 refresh with request queueing
  src/pages/             Home (listing), ItemDetail, Login
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
JwtAuthenticationFilter ──▶ SecurityConfig (stateless, /api/auth/** + /uploads/** public)
      │  Bearer <redacted> ── userId principal, ROLE_USER / ROLE_ADMIN authorities
      ▼
auth/        AuthController  /api/auth/register, /login, /refresh
             AuthService + JwtTokenService — jjwt HS256, access 15 min,
             refresh 7 d, single-use rotation in the httpOnly `refresh_token`
             cookie; replay of a consumed refresh token revokes the whole family.
             BCrypt(cost 12) password hashing. Tables: app_user, refresh_token.
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
order/       OrderController  POST /api/orders (Idempotency-Key header)
             OrderService — @Version optimistic locking; one active order per
             item guarded by a DB-generated unique column (NULL when terminal).
             IdempotencyKeyService — same key + same item replays the original
             order; concurrent same-key losers get 409 while IN_PROGRESS.
             Tables: orders, idempotency_key.
message/     MessageController  POST /api/messages, GET /api/messages?itemId=
             Offline v1 (no WebSocket). Sender is taken from the JWT principal;
             threads are readable only by participants (third party → 403).
             Table: message (sender/receiver/item as plain ids + FKs).
common/      ApiResponse<T> {code, message, data} envelope,
             GlobalExceptionHandler (400/401/403/404/409/413/422 → JSON),
             UploadWebConfig (/uploads/** static mapping).
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
| JWT auth (access + rotating refresh) | HS256 access (15 min) + single-use rotating refresh tokens in an httpOnly `SameSite=Lax` cookie; replay revokes the token family; identical 401s for unknown user vs wrong password (no enumeration oracle) | No server-side logout yet — the refresh cookie lives its full 7 d (open backlog); no brute-force throttling on /api/auth/login (open backlog) |
| RBAC | ADMIN role; `@PreAuthorize("hasRole('ADMIN')")` on DELETE /api/items/{id}; 403 in the JSON envelope | Only one elevated operation (delete) is RBAC-guarded so far |
| Listings | POST /api/items with jakarta validation; MapStruct DTOs (no JPA in JSON); category taxonomy (Flyway V3); debounced URL-synced `?q=` search; photo upload (UUID filename, type/size validated, served under /uploads/**) | Search is LIKE-based, not full-text; photos persist on local disk (in docker-compose they live in the container's /app/uploads and do not survive a container recreate — only mysql-data is a named volume); no listing edit endpoint yet (status SOLD transition is open backlog) |
| N+1 fix | List/detail/filter reads are single-SELECT JPQL DTO projections, asserted by Hibernate statistics in slice tests; composite index on (category_id, status, created_at) | Index benefit was asserted on H2/MySQL-compat SQL shape, not measured with production-scale data — no benchmark numbers are claimed |
| Orders | Optimistic locking (@Version); one active order per item via a DB-generated unique guard column; Idempotency-Key header replays the original order (REQUIRES_NEW reserve, 10-min IN_PROGRESS reclaim); two-buyer race proven exactly-once by a Testcontainers MySQL 8 IT (CI) | Order creation does NOT flip the item to RESERVED yet (open backlog — the list view still shows it as buyable; the unique guard prevents a second order); orders stop at PENDING — no pay/refund/cancel transitions yet (mock capture is open backlog) |
| Buyer↔seller messaging v1 | POST/GET /api/messages?itemId=; chronological threads; sender from JWT (no impersonation); 403 for non-participants; 422 messaging yourself | Offline only — no WebSocket, no read receipts, no notifications; plain `message_body` column (BODY is an H2 keyword) |
| Frontend auth flow | Login page wired to /api/auth/login; zustand store with in-memory token; single-flight 401 refresh with queued retries, verified by unit tests | In-memory token only — a page reload loses the session until refresh runs; no social login |
| Listing UX | Create-listing form with client validation mirroring the backend; envelope field-errors mapped onto fields; optional photo upload; TanStack Query invalidation on mutations | No buy-now button or message-thread UI yet (open backlog); error messages are not yet localized |
| Observability / testing | GitHub Actions CI (unit + Testcontainers MySQL 8 IT + frontend build/test); docker-compose smoke test with a manual checklist in docs/smoke-test.md | No actuator/health endpoint yet, no coverage gate (both open backlog); integration tests only run in CI — this sandbox has no Docker |
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
- [ ] JWT auth (jjwt, access + rotating refresh, RBAC admin)
- [x] Consistent `{code, message, data}` error envelope (validation, `ResponseStatusException`, unexpected)
- [ ] Listing CRUD with MapStruct DTOs (no lazy associations in JSON)
- [ ] N+1 fix: fetch-join + DTO projection + composite index on (category, status, created_at)
- [x] Optimistic locking (`@Version` on `Item`, covered by a slice test)
- [ ] Order entity + idempotent order creation via client request id
- [ ] MultipartFile image upload (UUID filename, type/size validation)
- [x] Axios 401 single-flight refresh queue (unit-tested, no backend needed)
- [ ] TanStack Query invalidation, debounced URL-synced search
- [ ] Offline message table (v1, no WebSocket)
