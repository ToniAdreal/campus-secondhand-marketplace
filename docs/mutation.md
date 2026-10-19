# Mutation testing pilot (backlog #192)

Line coverage (JaCoCo, gated at ≥ 80% on `mvn verify`) proves code is
*executed* by tests; it does not prove the tests would *notice* if the
behaviour changed. This pilot runs PIT (pitest) over the two highest-value
units in the codebase and measures exactly that:

- `com.toni.marketplace.auth.JwtTokenService` — access/refresh token
  issuance, rotation, grace window, theft detection, key rotation.
- `com.toni.marketplace.order.OrderService` +
  `com.toni.marketplace.order.OrderCreationService` — the order state
  machine (create / pay / cancel / complete / refund transitions and
  their guards).

The driving tests are the pure unit suites for those classes
(`JwtTokenServiceTest`, `JwtKeyRotationTest`, `OrderServiceTest`) — no
Spring context, no database — so a mutant is killed by an assertion about
behaviour, not by a context failing to boot.

## How it runs

PIT lives in the `mutation` Maven profile in `backend/pom.xml`. The
profile is **not active by default**: the local `mvn -o test` /
`mvn verify` path never runs PIT. CI runs it in the dedicated
`backend-mutation` job:

```
mvn -B -f backend/pom.xml verify -Pmutation
```

Reports land in `backend/target/pit-reports/` (XML + HTML) and the CI
job uploads them as the `pit-reports` artifact on every run.

## Score and floor

The `mutationThreshold` in the pom starts at **0 for the first CI run on
purpose**: the floor is a ratchet set from the first real run's measured
score, never guessed. The first real run is the `backend-mutation` CI
job for the commit that introduced this pilot — PIT's parent↔minion
protocol runs over a local TCP socket, and this project's local sandbox
blocks Java socket data transfer, so no honest local PIT measurement
exists (the same sandbox class that keeps Testcontainers integration
tests CI-only; the driving unit suites themselves pass locally, 49/49
at pilot time). Once that run's `mutations.xml` is read, this section
records the measured score, the date and run, the floor it set, and the
survivors below — in the follow-up commit that sets the floor.

<!-- MEASURED RESULTS — filled in from the first real CI run; never guessed. -->

## Surviving mutants

Every surviving mutant in the pilot scope is either killed by a new test
in the follow-up that sets the floor, or recorded below as a named
exception with the equivalent-mutant reasoning. There are no blanket
exclusions: no class, method, or mutator is excluded wholesale in the
pom.

<!-- SURVIVORS TABLE — filled in from the first real CI run. -->

## Honest scope

The mutation score covers the three classes above — two packages'
core units — **not** the codebase. Controllers, repositories, and the
frontend have no mutation score, and none is claimed for them in the
README. Widening the scope is future work, one package at a time, with
the floor re-measured each time.
