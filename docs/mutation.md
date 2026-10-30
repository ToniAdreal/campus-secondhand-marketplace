# Mutation testing pilot (backlog #192)

Line coverage (JaCoCo, gated at ≥ 80% on `mvn verify`) proves code is
*executed* by tests; it does not prove the tests would *notice* if the
behaviour changed. This pilot runs PIT (pitest) over the highest-value
units in the codebase and measures exactly that:

- `com.toni.marketplace.auth.JwtTokenService` — access/refresh token
  issuance, rotation, grace window, theft detection, key rotation.
- `com.toni.marketplace.order.OrderService` +
  `com.toni.marketplace.order.OrderCreationService` — the order state
  machine (create / pay / cancel / complete / refund transitions and
  their guards).
- `com.toni.marketplace.auth.PasswordStrengthValidator` (backlog #269)
  — the shared password policy: blocklist, length, composition, and
  the breached-password screen.
- `com.toni.marketplace.auth.TotpService` (backlog #285) — TOTP codes,
  Base32, otpauth URIs, and recovery-code formatting/normalization.

The driving tests are the pure unit suites for those classes
(`JwtTokenServiceTest`, `JwtKeyRotationTest`, `OrderServiceTest`,
`PasswordStrengthValidatorTest`, `BreachedPasswordCheckerTest`,
`TotpServiceTest`) — no
Spring context, no database — so a mutant is killed by an assertion about
behaviour, not by a context failing to boot. The Spring/MockMvc TOTP
suites (recovery-code flow, lockout, encryption at rest) exercise
`TotpService` through `AuthService` but are deliberately NOT in PIT's
`targetTests`: they would make every mutant pay a context boot, and
the pure suite now pins the same branches directly (see #285 below).

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

The `mutationThreshold` in the pom started at **0 for the first CI run
on purpose**: the floor is a ratchet set from a real run's measured
score, never guessed. PIT's parent↔minion protocol runs over a local
TCP socket, and this project's local sandbox blocks Java socket data
transfer, so no honest local PIT measurement exists (the same sandbox
class that keeps Testcontainers integration tests CI-only; the driving
unit suites themselves pass locally). All numbers below are read from
the `pit-reports` artifact (`mutations.xml`, PIT 1.15.3) of real CI
runs — never estimated.

### Latest measured run (backlog #212)

GitHub Actions run **39735286602**, 2026-10-22T06:39:14Z, head
**41c334d** (backlog #211); the `backend-mutation` job succeeded.

| Scope | Line coverage | Mutation coverage | Test strength |
|---|---|---|---|
| Project (3 classes) | 55% (296/536) | 28% (81/291) | 63% (81/129) |
| `auth` — `JwtTokenService` | 82% (153/186) | 54% (41/76) | 76% (41/54) |
| `order` — `OrderService` + `OrderCreationService` | 41% (143/350) | 19% (40/215) | 53% (40/75) |

Mutant statuses, project-wide: **KILLED 81, SURVIVED 48, NO_COVERAGE
162**.

### Widened scope, unmeasured (backlog #269, 2026-10-29)

Backlog #269 adds exactly one class to the PIT scope —
`auth.PasswordStrengthValidator` — driven by its two existing pure
unit suites. The class was picked because it is the best-covered
pure unit outside the old scope (27 tests across the two suites
before this round), and the round closes the four branches those
suites left unpinned, so the widened scope does not ship known
unkilled mutants with it:

- `minLength <= 0` falls back to the default 12
  (`PasswordStrengthValidatorTest.nonPositiveMinimumLengthFallsBackToDefault`,
  including the 2-arg `requireStrong` path);
- null/blank candidates never reach the checker
  (`BreachedPasswordCheckerTest.nullAndBlankPasswordsNeverReachTheChecker`);
- a checker answering `null` suffixes passes the candidate instead of
  throwing (`checkerReturningNullSuffixesPassesTheCandidate`);
- the 3-arg `requireStrong` overload actually applies the breach
  screen (`requireStrongThreeArgOverloadAppliesTheBreachScreen` — a
  mutant delegating to the 2-arg overload would drop the screen while
  every `check()` test stayed green).

The floor is **held at 27, not raised**: PIT cannot run in the local
sandbox, so no 4-class score exists yet, and a floor is never guessed
(the #212 discipline). The next CI `backend-mutation` run measures the
widened scope; the round that reads its `pit-reports` artifact
re-ratchets the floor from that measured score, rounded down, and
updates the table above. Until then the 27 floor is a conservative
hold, not a claim about the new scope's score.

### Widened scope, unmeasured (backlog #285, 2026-10-30)

Backlog #285 adds exactly one more class to the PIT scope —
`auth.TotpService` — driven by its pure unit suite `TotpServiceTest`
(8 tests before this round, 14 after). The spec's measure-first step
was attempted honestly: a TotpService-only PIT run at threshold 0
reproduced the sandbox failure in 47 s (coverage minion died with
`EOFException` reading its socket, `MINION_DIED`, `BUILD FAILURE`),
even though the pitest jars are now present in the offline repository
— so, as with #269, no local score exists and none is claimed.

The round therefore closes the branches the pure suite left unpinned,
so the widened scope does not ship known unkilled mutants with it:

- Base32 leftover bits: short inputs (`0x00` → `AA`, `0xff` → `74`,
  `f` → `MY`, `fo` → `MZXQ`, `foo` → `MZXW6`, `foobar` → `MZXW6YTBOI`,
  cross-checked against the standard Base32 encoding) — the 20-byte
  RFC secret never touches this branch, since 160 bits divide evenly
  by 5;
- lowercase Base32 decodes to the same bytes and verifies
  (`RFC_SECRET.toLowerCase()`);
- a non-ASCII secret character (past the 128-entry decode table) fails
  closed, distinct from the in-range `!` case already pinned;
- otpauth label encoding: space → `%20`, `+` → `%2B`, `@` → `%40`,
  non-ASCII → UTF-8 percent sequences, unreserved `-_.~` untouched;
- recovery codes: display form `XXXX-XXXX-XXXX-XXXX` over the
  look-alike-free alphabet (hyphens at 4/8/12), and normalization
  branches (lowercase, space/tab separators, invalid
  `0`/`1`/`O`/`I`/`L`/`!`, wrong lengths 15/17, empty, null).

The floor is **held at 27, not raised and not lowered**, on the same
terms as #269: the next CI `backend-mutation` run measures the 5-class
scope, and the round that reads its `pit-reports` artifact
re-ratchets the floor from that measured score, rounded down.

### First run, for contrast (backlog #192)

GitHub Actions run 39194362544, 2026-10-19T03:22:13Z, head c5361e7:
mutation coverage 30% (79/266), test strength 63% (79/126), line
coverage 57% (291/509).

Mutation *coverage* went **down** (30% → 28%) between the two runs
while *test strength held* (63% → 63%): the denominator grew, not the
weakness. `OrderService` gained methods in backlogs #193–#211
(cancel / complete / refund / dispute / handover / read paths) that the
three driving unit suites do not touch — their mutants are
NO_COVERAGE (162 of them), exercised only by the MockMvc suites, which
are outside PIT's `targetTests`. Of the mutants the driving suites do
reach, the same proportion is still killed; what fell is the share of
the enlarged class those suites reach at all.

### Floor decision

`mutationThreshold` is now **27**: the latest measured mutation
coverage is 81/291 = 27.8%, and the floor is that score rounded
**down** to a whole percent, so an identical rerun cannot fail on
rounding. The floor is never set above the measured score. Note the
scope drift honestly: that 27.8% was measured on the 3-class scope;
#269 widened it to 4 classes and #285 to 5 classes, both unmeasured
locally (PIT cannot run in this sandbox), so 27 is now a conservative
hold for the 5-class scope, not a measured score for it — the next CI
`backend-mutation` run re-ratchets it from its measured score,
rounded down. Widening `targetTests` to reach the remaining
NO_COVERAGE methods is future work, not done here, and will require
re-measuring.

## Surviving mutants

The 48 SURVIVED mutants of run 39735286602, grouped by class and
method. Every one is **targeted by a new or strengthened test in the
follow-up that sets the floor (#212)** — the disposition names the
test(s). No mutant is labelled "equivalent": none was shown to be one.
Because PIT cannot run in the local sandbox, a targeted mutant is not
claimed killed here; the kill is confirmed (or not) by the next CI
`backend-mutation` run. Still surviving after this follow-up: **none of
the 48** — every survivor is targeted (48 targeted + 0 still-surviving
= 48).

### `OrderService.pay` — 13

- L819 `setCaptureId`, L824 `setPaidAt`, L839 `orders.flush()`,
  L856 `forgetCapture`, L857 `metrics.paid()`, L858 `stopPayTimer`,
  L864 `audit.record`, L868 `notifications.record` (all
  VoidMethodCall) — targeted by
  `OrderServiceTest.pay_awareService_storesCaptureStampMintsHandoverAndRecordsSideEffects`,
  which constructs the service with mocked metrics/audit/notifications
  and a fixed clock and asserts the stored capture id, the paid-at
  instant, the flush, the forget, and each recorded side effect.
- L872 (NegateConditionals, the decline catch's timer guard), L873
  `stopPayTimer` and L881 `paymentsDeclined()` (VoidMethodCall) in the
  declined path — targeted by
  `pay_awareService_declinedCapture_countsDeclineStopsTimerAndRecordsAttempt`,
  which also pins the DECLINED attempt being recorded under the very
  capture key the PSP declined.
- L893 (NegateConditionals) and L894 `stopPayTimer` (VoidMethodCall)
  in the optimistic-lock catch — targeted by
  `pay_awareService_versionConflict_stopsPayTimerAsFailure`.

### `OrderService.createOrder` — 8

- L267 `metrics.created()` and L268 `stopCreateTimer` (VoidMethodCall,
  success path) — targeted by
  `createOrder_awareService_success_countsCreatedAndStopsTimerAsSuccess`.
- L273 (NegateConditionals) and L274 `stopCreateTimer` in the
  constraint-race catch — targeted by
  `createOrder_awareService_constraintRace_stopsTimerAsFailureAndNeverCountsCreated`.
- L283 (NegateConditionals) and L284 `stopCreateTimer` in the
  item-version-race catch — targeted by
  `createOrder_awareService_itemVersionRace_stopsTimerAsFailureAndNeverCountsCreated`.
- L294 (NegateConditionals) and L295 `stopCreateTimer` in the generic
  application-failure catch — targeted by
  `createOrder_awareService_appFailure_stopsTimerAsFailureAndNeverCountsCreated`.

### `OrderService` — accessors and helpers — 8

- L112 `clock()` (NegateConditionals) — targeted by the pay kill test
  above: with a non-null fixed clock wired in, a negated accessor falls
  back to the system clock and the asserted `paidAt` instant moves.
- L121 `audit()`, L130 `notifications()`, L140 `metrics()` (all
  NegateConditionals) — targeted by the aware-service tests above: a
  negated accessor falls back to the no-op instance, so the verified
  calls on the mocks never happen.
- L175 `paymentAttempts()` (NullReturnVals) — targeted by
  `getOrderFor_buyer_latestAttemptDeclined_carriesPaymentFailureNote`:
  a null-returning accessor makes the enrichment skip the recorder, so
  the buyer DTO would carry no failure note. (The SUCCEEDED and
  no-attempt companion tests pin that the note appears *only* for a
  declined latest attempt.)
- L200 `handoverHash()` (EmptyObjectReturnVals) — targeted by the pay
  kill test: the stored hash is asserted to be the 64-hex SHA-256 of
  the disclosed handover code, not `""`.
- L533 `captureKeyFor()` (EmptyObjectReturnVals) — targeted by the pay
  kill test: the key passed to `capture` is captured and asserted
  non-empty, `cap_`-prefixed, and equal to the key stored on the order.

### `OrderService.mintHandoverCode` — 4

- L216 `setHandoverCodeHash`, L217 `setHandoverFailedAttempts`,
  L218 `setHandoverLockedUntil` (VoidMethodCall) and L219 (return
  replaced with `""`) — targeted by the pay kill test, which pre-sets
  3 failed attempts and a future lock, then asserts the stored hash,
  the reset counter, the cleared lock, and the 6-digit disclosed code.

### `OrderService.validateKey` — 1

- L561 (ConditionalsBoundary, the `> 64` length check) — targeted by
  `createOrder_keyOfExactly64Chars_isAccepted`: a key of exactly 64
  chars goes through to a successful create (the 65-char rejection was
  already pinned).

### `OrderCreationService.create` — 2

- L103 (NegateConditionals) — from the source, L103 is the lazy
  listing-expiry guard (`item.isExpiredAt(...)` → 409); targeted by
  `creation_expiredListing_throws409` (an AVAILABLE listing past its
  `expiresAt` is rejected, nothing saved).
- L110 `orders.flush()` (VoidMethodCall) — targeted by
  `creation_happyPath_flushesInsideTheTransaction`.

### `JwtTokenService.createTokenPair` — 2

- L273 `setUserAgent` and L274 `setIpAddress` (VoidMethodCall) —
  targeted by `createTokenPair_explicitSessionMeta_isStoredOnRefreshRow`,
  which issues a pair with `SessionMeta("ua-x", "10.0.0.1")` and asserts
  the captured refresh row carries exactly that UA/IP.

### `JwtTokenService.rotateLive` — 5

- L440 `setRevokedAt` (VoidMethodCall), L452/L453 `setUserAgent` /
  `setIpAddress` (VoidMethodCall) and L452/L453 (NegateConditionals on
  the meta-present ternaries) — targeted by
  `rotate_unknownMeta_newRowInheritsConsumedRowsDeviceInfo` (unknown
  meta → the new row inherits the consumed row's UA/IP) and
  `rotate_explicitMeta_newRowCarriesNewDeviceInfo` (explicit meta →
  the new row carries the new UA/IP); both assert the consumed row's
  `revokedAt` equals the rotation instant.

### `JwtTokenService.revokeFamilyOnReuse` — 2

- L374 (NegateConditionals, the metrics null-guard) and L375
  `AuthMetrics.refreshTheftDetected()` (VoidMethodCall) — targeted by
  `rotate_revokedTokenReuse_countsTheftDetection`, which rotates
  through an instance wired to a mocked `AuthMetrics` and verifies the
  counter call alongside the family kill.

### `JwtTokenService.rotateRefreshToken` — 2

- L311 `requireType` (VoidMethodCall) — targeted by
  `rotate_accessToken_throwsNotARefreshToken`: an access token is
  rejected with the "not a refresh token" message (the message, not
  just the shared exception supertype, pins the type check).
- L330 (NegateConditionals, the revoked-and-unexpired branch) —
  targeted by `rotate_revokedToken_withLiveUser_stillRejectedAsReuse`:
  with the user lookup stubbed to succeed, falling through the branch
  would rotate the revoked token instead of rejecting it as reuse, so
  the test asserts the reuse rejection and the family kill.

### `JwtTokenService.tryGraceRotation` — 1

- L390 (NegateConditionals, the no-successor/no-revocation-instant
  early return) — targeted by
  `rotate_justReplacedTokenInsideGrace_rotatesLiveSuccessor`, built
  unit-style against mocks (the existing grace suite is MockMvc /
  Spring-backed): a just-replaced token inside the grace window whose
  successor is still live rotates that successor instead of taking the
  theft path.

### `JwtTokenService.keyFromBase64` — 1

- L552 (ConditionalsBoundary, the `< 32` byte check) — targeted by
  `keyFromBase64_exactly32ByteSecret_isAccepted`: a secret decoding to
  exactly 32 bytes yields a usable HS256 key (the short-key rejection
  was already pinned).

### The NO_COVERAGE gap (honest scope)

162 mutants sit in `OrderService` methods added after #192
(cancel / complete / refund / dispute / handover / read paths) that
only the MockMvc suites exercise. They are not survivors — no driving
test reaches them at all — and nothing in this follow-up changes that:
widening PIT's `targetTests` is future work, and the 27 floor above is
set against the current scope, as stated there.

## Honest scope

The mutation score covers the four classes above — two packages'
core units plus the shared password policy — **not** the codebase.
Controllers, repositories, and the frontend have no mutation score,
and none is claimed for them in the README. Widening the scope
continues one class at a time, with the floor re-measured each time.
