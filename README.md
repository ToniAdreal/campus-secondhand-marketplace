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

## Run locally

```bash
# backend (H2 in-memory, Flyway migrates on startup)
cd backend && mvn spring-boot:run

# frontend
cd frontend && npm install && npm run dev
```

Full MySQL path: `docker compose up --build` (uses the `mysql` Spring profile).

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
