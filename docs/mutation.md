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
- `com.toni.marketplace.auth.AuthRateLimiter` (backlog #320) — the
  token-bucket throttle for the auth endpoints: burst, refill,
  snapshot/retry-after math, the disabled switch, and the
  null/blank-key "unknown" bucket.
- `com.toni.marketplace.order.SellerPayoutTransitions` (backlog #333)
  — the per-row payout transitions: the seller-account hold, the
  dispute-window gate, promotion and pay guards, and the pay-time
  PAYOUT_PAID notification.
- `com.toni.marketplace.order.OrderReceiptService` (backlog #338)
  — the printable-receipt builder: the buyer/seller/ADMIN read rule
  with its no-leak 404, the `paid` flag's status/capture-id
  disjunction, the zero-refunded-total → null map, the
  RESOLVED_REFUND-only dispute note, the latest-refunded-attempt
  reason, and the `"unknown"` username for a deleted account.

The driving tests are the pure unit suites for those classes
(`JwtTokenServiceTest`, `JwtKeyRotationTest`, `OrderServiceTest`,
`PasswordStrengthValidatorTest`, `BreachedPasswordCheckerTest`,
`TotpServiceTest`, `AuthRateLimiterTest`,
`SellerPayoutTransitionsTest`, `OrderReceiptServiceTest`) — no
Spring context, no database — so a mutant is killed by an assertion about
behaviour, not by a context failing to boot. The Spring/MockMvc TOTP
suites (recovery-code flow, lockout, encryption at rest) exercise
`TotpService` through `AuthService` but are deliberately NOT in PIT's
`targetTests`: they would make every mutant pay a context boot, and
the pure suite now pins the same branches directly (see #285 below).
The same exclusion applies to the filter suite `AuthRateLimitTest`:
it exercises `AuthRateLimiter` through `AuthRateLimitFilter`, but the
pure suite pins the limiter's own branches directly (see #320 below),
so no filter-seam test is in `targetTests`. Backlog #345 is the
deliberate exception: the eight MockMvc suites driving the
`OrderService` methods added after #192 (`OrderCancelTest`,
`OrderCompleteTest`, `OrderRefundTest`, `OrderDisputeTest`,
`OrderShipmentTest`, `OrderReadTest`, `OrderHandoverCodeTest`,
`OrderPaymentTest`) ARE in `targetTests`, because no pure suite
reaches those methods at all — the context boot per mutant is
accepted and paid in CI (see #345 below).

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

### Widened scope, unmeasured (backlog #320, 2026-11-03)

Backlog #320 adds exactly one more class to the PIT scope —
`auth.AuthRateLimiter` — driven by its pure unit suite
`AuthRateLimiterTest` (13 tests before this round, 16 after). The
item names the filter suite `AuthRateLimitTest` as a conditional
addition ("if the pilot needs the filter seam"); the pilot does not
need it — the pure suite pins every observable limiter behaviour
directly, and the filter suite would make every mutant pay a Spring
context boot for no new limiter branch, the same exclusion the TOTP
MockMvc suites already carry. It stays out of `targetTests`
deliberately, and the pom comment records why.

The round closes the branches the pure suite left unpinned, so the
widened scope does not ship known unkilled mutants with it:

- a non-positive `attemptsPerMinute` (0 and -3) fails closed in the
  constructor (`constructorRejectsNonPositiveAttemptsPerMinute`) —
  the guard existed but no test ever tripped it;
- the disabled switch: `tryConsume` always allows even past capacity,
  `snapshot` reports the full bucket, `retryAfterSeconds` is 0, and
  `isEnabled()` is false (`disabledLimiterAlwaysAllowsAndReportsFullState`);
- null/blank keys share one "unknown" bucket instead of bypassing the
  limit: five null-key consumes exhaust it, and blank/`""` keys then
  deny while a real key stays untouched
  (`nullAndBlankKeysShareTheUnknownBucket`).

Two branches stay knowingly unpinned, as expected surviving mutants
for the next CI run to report, not silently fixable by a test: the
idle-eviction path only fires past 10,000 tracked keys and is
unobservable through the public API (a refilled bucket looks
identical whether its row was evicted or not), and the backwards-clock
guard shares its branch with the elapsed-`0` case the burst tests
already take. Neither is a behaviour gap worth a contrived test.

The floor is **held at 27, not raised and not lowered**, on the same
terms as #269/#285: PIT cannot run in this sandbox, so no local
6-class score exists and none is claimed; the next CI
`backend-mutation` run measures the widened scope, and the round
that reads its `pit-reports` artifact re-ratchets the floor from
that measured score, rounded down.

### Widened scope, unmeasured (backlog #333, 2026-11-05)

Backlog #333 adds exactly one more class to the PIT scope —
`order.SellerPayoutTransitions` — driven by its new pure unit suite
`SellerPayoutTransitionsTest` (21 tests). The item names the direct
test class as a conditional addition ("if the existing suite cannot
kill the mutants"); the pilot needs it — the existing
`SellerPayoutTest` drives the bean through the sweep service with a
Spring context boot per test, which would make every mutant pay a
context boot for no new transition branch, the same exclusion the
TOTP and filter suites already carry. It stays out of `targetTests`
deliberately, and this section records why.

The suite pins every guard directly, with Mockito repositories and a
one-hour dispute window so the window arithmetic stays exact:

- the seller hold in all three shapes: missing payout row (not held),
  missing seller row (held, fail closed), disabled seller (held);
- every `promoteToPayable` skip: missing row, wrong status, held
  seller, missing order, non-COMPLETED order, open dispute, null
  anchor, window still open — each asserting `false` plus no save;
- promotion itself: exactly at window expiry (the `isAfter`
  boundary), and the `completedAt`-null fallback to `updatedAt`;
- every `markPaid` skip on the same terms, plus the pay-time
  notification recorded exactly once (a rerun returns `false` before
  reaching the record) and the null-notification-service guard
  degrading to the noop service instead of throwing.

Two test-authoring traps the suite documents in comments: the dispute
lookup keys on `order.getId()`, which is null on a directly
constructed `Order`, so the dispute stub matches any id; and
`updatedAt` is set only by `@PrePersist`, so the fallback-anchor case
uses a mocked `Order` rather than a constructed one.

The floor is **held at 27, not raised and not lowered**, on the same
terms as #269/#285/#320: PIT cannot run in this sandbox, so no local
7-class score exists and none is claimed; the next CI
`backend-mutation` run measures the widened scope, and the round
that reads its `pit-reports` artifact re-ratchets the floor from
that measured score, rounded down.

### Widened scope, unmeasured (backlog #338, 2026-11-06)

Backlog #338 adds exactly one more class to the PIT scope —
`order.OrderReceiptService` — driven by its new pure unit suite
`OrderReceiptServiceTest` (16 tests). The item names the direct
test class as a conditional addition ("if the existing suite cannot
kill the mutants"); the pilot needs it — the existing
`OrderReceiptTest` drives the service through MockMvc with a Spring
context boot per test, which would make every mutant pay a context
boot for no new receipt branch, the same exclusion the TOTP, filter
and payout suites already carry. It stays out of `targetTests`
deliberately, and the pom comment records why.

The suite pins every guard directly, with Mockito repositories and
directly constructed orders (no id is ever assigned, so the dispute
and attempt stubs match on any order id):

- the read rule in all four shapes: unknown id → 404, stranger →
  404 with the same message, buyer/seller/ADMIN each read;
- the `paid` flag disjunct by disjunct: PENDING with a capture id
  is paid, PAID/COMPLETED/REFUNDED are paid without one, PENDING
  without one is not, and — the branch only this suite can pin —
  DISPUTED without a capture id is paid (through the API a dispute
  always follows a payment, so the MockMvc suite only ever sees
  DISPUTED with a capture id set);
- the refunded-total map at its boundary: 0 → null (the
  `>=` boundary mutant) and 30000 → 30000 (the negated mutant);
- the dispute note: a RESOLVED_REFUND dispute's note is carried,
  no such dispute → null (a release resolution lives under a
  different status and never surfaces as a refund note);
- the refund reason: the latest REFUNDED attempt's reason is
  carried, no refunded attempt → null;
- the deleted-account usernames: a missing buyer row and a missing
  seller row each render as `"unknown"` instead of failing the
  receipt.

One branch is pinned as documentation of a deliberate absence:
SHIPPED without a capture id is *not* paid — SHIPPED is not in the
`paid` disjunction because the capture id is the payment proof, and
a shipped order always carries one in production (ship requires
PAID first). No mutant exposed a real bug, so there is no
production-code change in this round.

The floor is **held at 27, not raised and not lowered**, on the same
terms as #269/#285/#320/#333: PIT cannot run in this sandbox — the
measure-first step, an OrderReceiptService-only run at threshold 0
on 2026-11-06, reproduced the sandbox failure in ~25 s (coverage
minion died with `EOFException` reading its socket, `MINION_DIED`,
`BUILD FAILURE`), so no local 8-class score exists and none is
claimed; the next CI `backend-mutation` run measures the widened
scope, and the round that reads its `pit-reports` artifact
re-ratchets the floor from that measured score, rounded down.

### Widened scope, unmeasured (backlog #345, 2026-11-07)

Backlog #345 widens `targetTests` — no new `targetClasses` — to the
eight MockMvc suites that drive the `OrderService` methods added
after #192 (cancel / complete / refund / dispute / handover / read
paths): `OrderCancelTest` (9 tests), `OrderCompleteTest` (10),
`OrderRefundTest` (11), `OrderDisputeTest` (18),
`OrderShipmentTest` (7), `OrderReadTest` (12),
`OrderHandoverCodeTest` (7) and `OrderPaymentTest` (8) — 82 tests
in total. These are the only drivers of the 162 NO_COVERAGE mutants
from run 39735286602, so this is the widening the NO_COVERAGE gap
section below called future work. It is the deliberate exception to
the MockMvc exclusion: unlike `OrderReceiptTest` and
`AuthRateLimitTest`, no pure suite pins these methods' branches, so
the Spring context boot per mutant is accepted and paid in CI
rather than worked around with a second pure suite. The `*IT`
classes stay out (CI-only, Testcontainers).

The floor is **held at 27, not raised and not lowered**, on the same
terms as #269/#285/#320/#333/#338: PIT cannot run in this sandbox —
the measure-first step, an `OrderService`-only run at threshold 0
driving the eight new suites on 2026-11-07, reproduced the sandbox
failure in 29 s (1 mutation test unit created in the pre-scan, then
the coverage minion died with `EOFException` reading its socket,
`MINION_DIED`, `BUILD FAILURE`; log
`hidden_files/pit-345-measure-first.log`), so no local widened-scope
score exists and none is claimed. The new suites can only add kills
against the same target classes, so the widened score is expected
to rise from the 27.8% of run 39735286602 — but that is an
expectation, not a score, and the floor stays 27 until the run is
read. The round that reads the next CI `backend-mutation` run's
`pit-reports` artifact re-ratchets the floor from the measured
score, rounded down, and dispositions the newly reachable survivors
per the #212 discipline (no survivor is dispositioned here: none
was measured).

### Widened scope, measured (backlog #355, 2026-11-08)

GitHub Actions run **42434347952**, 2026-11-08T01:27:44Z, head
**099aba0** (backlog #353); the `backend-mutation` job succeeded.
(The run's overall conclusion is failure — other jobs failed for
unrelated reasons; the `pit-reports` artifact is the mutation job's
output.) This is the first CI `backend-mutation` run after the #345
`targetTests` widening, so it is the run the #345 note deferred the
floor re-ratchet and the survivor disposition to.

| Scope | Line coverage | Mutation coverage | Test strength |
|---|---|---|---|
| Project (8 classes) | 87% (805/928) | 80% (477/599) | 88% (477/542) |
| `auth` — 4 classes | 89% (385/434) | 85% (249/294) | 90% (249/277) |
| `order` — 4 classes | 85% (420/494) | 75% (228/305) | 86% (228/265) |

Mutant statuses, project-wide: **KILLED 476, SURVIVED 65,
NO_COVERAGE 57, TIMED_OUT 1** (the timed-out mutant counts as
detected: 477 detected / 599). The displayed 80% is the rounded
figure; the exact score is 477/599 = 79.6%.

Floor decision: the floor moves **27 → 79** — the measured mutation
coverage, 79.6%, rounded down to a whole percent per the #212
discipline, so an identical rerun cannot fail on rounding. The floor
is never set above the measured score.

#### Disposition of the 65 SURVIVED mutants (backlog #355)

Per the #212 discipline: every survivor gets a killing test, or a
documented disposition naming why it cannot be killed — none
dispositioned silently. Because PIT cannot run in the local sandbox,
a targeted mutant is not claimed killed here; the kill is confirmed
(or not) by the next CI `backend-mutation` run.

`OrderService` — 37, all targeted by new tests in
`OrderServiceTest` (the aware-service tests, built unit-style
against mocks so the kill does not depend on a Spring context):

- `cancel` (4: L676 `flush`, L677 `metrics.cancelled`, L681
  `audit.record`, L685 `notifications.record`, all VoidMethodCall) —
  targeted by
  `cancel_awareService_recordsSideEffectsAndReleasesListing`, which
  asserts the CANCELLED status, the listing back to AVAILABLE, and
  each verified side effect.
- `complete` (2: L1023 NegateConditionals on the `!callerIsAdmin`
  guard, L1030 `metrics.handoverConfirmed`) — targeted by the
  seller-path test
  `complete_awareService_sellerPathConfirmsHandoverAndRecordsSideEffects`
  (verifies `handoverConfirmed()`) together with
  `complete_adminOverride_neverCountsHandoverConfirmed` (verifies
  `never()`); negating the guard breaks both.
- `getSellerOrderFor` (2: L554 NegateConditionals on the
  owner-or-admin check, L558 NullReturnVals) — targeted by
  `getSellerOrderFor_ownerAdminAndStranger` (owner and admin pass,
  stranger gets 403, and the returned DTO id is asserted).
- `listSellerOrders` (2: L379 NegateConditionals on the
  `status == null` branch, L380 NullReturnVals) — targeted by
  `listSellerOrders_nullStatusUsesUnfilteredQuery` and
  `listSellerOrders_statusUsesFilteredQuery`, which verify the exact
  repository method called in each branch.
- `mintHandoverCode` (1: L235 `setHandoverCodeExpiresAt`) —
  targeted by the seller-path complete test, which asserts the expiry
  equals the pay instant plus the 7-day TTL.
- `nextRefundKeyFor` (2: L594 MathMutator on the refund-count
  arithmetic, L595 `setRefundIdempotencyKey`) — targeted by the
  full-refund test, which asserts the key is exactly `rfd_5_2`.
- `refund` (14) — targeted by
  `refund_awareService_fullRefundRecordsMoneyAndSideEffects` (500-char
  reason pins the L1381 `> 500` boundary; amount exactly equal to the
  remaining pins the L1394 `amount > remaining` boundary; the
  refunded-amount / refund-count / refund-id / flush / forget-refund /
  refunded-metric / audit / notification assertions pin L1392,
  L1412–L1414, L1432, L1436, L1443, L1450, L1453),
  `refund_awareService_partialRefundStaysPaidAndCountsNoRefundMetric`
  (the L1442 `completesRefund` branch: partial stays PAID and never
  counts the refunded metric), and
  `refund_zeroAmount_throws422AndCallsNoPsp` as a companion pinning
  the `amount <= 0` guard (no survivor sits on that guard — it was
  already killed in the measured run — but the zero-amount path had
  no direct test).
- `regenerateHandoverCode` (1: L1295 `flush`) — targeted by
  `regenerateHandoverCode_awareService_flushesAndRestampsExpiry`
  (`times(2).flush()` across pay + regenerate).
- `ship` (3: L1097 `flush`, L1101 `metrics.shipped`, L1107
  `notifications.record`) — targeted by
  `ship_awareService_recordsSideEffects`.
- `transitionToCompleted` (5: L1174 `setHandoverFailedAttempts`,
  L1175 `setHandoverLockedUntil`, L1190 `flush`, L1191
  `metrics.completed`, L1209 `notifications.record`) — targeted by
  the seller-path complete test, which pre-sets 3 failed attempts and
  an expired lock and asserts both are cleared, plus the flush,
  completed-metric and notification verifications.
- `verifyHandoverCode` (1: L1246 NegateConditionals on the expiry
  check) — targeted by `complete_expiredHandoverCode_throws422` and
  by the seller-path complete test (a live code must pass).

`JwtTokenService` — 4, all targeted by new tests in
`JwtTokenServiceTest`:

- `authTimeOf` (2: L426 NegateConditionals, NullReturnVals) —
  targeted by `rotate_preservesAuthTime_neverFreshens` (rotation an
  hour after issuance must carry the original `auth_time`, on both
  the access and the refresh token) and
  `rotate_tokenWithoutAuthTime_succeedsWithNoClaim` (a pre-#218 token
  with no claim must rotate without inventing one).
- `rotateLive` (2: L514 and L546 NegateConditionals on the two
  `authTimeAt != null` guards, refresh and access claim) — targeted
  by the same two rotate tests: negating either guard drops the claim
  when present or throws on the null when absent.

`TotpService` — 14: 6 targeted, 8 documented equivalents.

- `base32Decode` (1: L274 ConditionalsBoundary on the
  `c >= BASE32_DECODE.length || BASE32_DECODE[c] < 0` guard) —
  targeted by `base32Decode_boundaryValues`: `'A'` (table value 0)
  must decode, and char 128 (exactly past the 128-entry table) must
  throw `IllegalArgumentException`.
- `encodeLabel` (5: L231 ConditionalsBoundary on the unreserved-set
  range checks) — targeted by
  `otpauthUri_keepsBoundaryLabelCharactersLiteral`, which puts the
  first and last letter and digit of each range (`AZaz09`) through
  the URI unencoded.
- `base32Encode` L252 (ConditionalsBoundary, `while (bitsLeft >= 5)`)
  — **equivalent**: when `bitsLeft` is exactly 5 the loop would emit
  `(buffer >> 0) & 0x1f`, and the post-loop flush emits
  `(buffer << 0) & 0x1f` — the same five bits, in the same order, one
  emit later at most. The bit stream is a pure shift register, so
  deferring the emit cannot change the output string for any input.
- `base32Encode` L246 (3 MathMutators on the
  `(bytes.length * 8 + 4) / 5` initial capacity) — **equivalent**: the
  expression only sizes the `StringBuilder`; a `StringBuilder` grows
  as needed, so the encoded output is identical.
- `generateRecoveryCode` L110 (ConditionalsBoundary, the raw loop's
  `i < RECOVERY_CODE_CHARS`) — **equivalent**: the display loop
  still copies only the first 16 raw characters, so a 17th generated
  character is never displayed, stored, or hashed.
- `hotp` L195 (ConditionalsBoundary, `for (i = 7; i >= 0; i--)`) —
  **equivalent through the public API**: skipping `i = 0` leaves
  `message[0]` at 0 instead of `(counter >> 56) & 0xff`, and `hotp`
  is only called from `verify()` with time-step counters (epoch
  seconds / 30), which never reach 2^56.
- `isAllDigits` L218 (NegateConditionals, the loop condition) and
  L221 (BooleanTrueReturnVals on the in-loop `return false`) —
  **equivalent**: `isAllDigits` is only a fail-fast guard inside
  `verify()`; a non-digit code can never equal a `%06d` HOTP value,
  so `verify()` returns false with or without the guard. The guard
  saves a needless HMAC, nothing observable.

`AuthRateLimiter` — 10: 4 targeted, 1 superseded, 5 documented
equivalents.

- `evictIdleIfNeeded` L171 (NegateConditionals) — targeted by
  `evictionDoesNotRunBelowTheKeyCap`: with the guard negated,
  eviction runs at 2 tracked keys and the idle bucket is recreated
  full, so the final `tryConsume` returns true instead of false.
- `evictIdleIfNeeded` L171 (ConditionalsBoundary, `>` vs `>=`) —
  targeted by `evictionDoesNotRunAtExactlyTheKeyCap`: exactly 10,000
  keys tracked (1 idle + 9,999 filler) must not evict — the guard is
  strictly-greater — while 10,001 keys do (`evictionRunsPastTheKeyCap`).
- `retryAfterSeconds` L136 (VoidMethodCall, `bucket.refill`) —
  targeted by `retryAfterSecondsRefillsWithoutAConsume`: without the
  refill the post-advance call still reports 12 s instead of 0.
- `tryConsume` L66 (VoidMethodCall, `evictIdleIfNeeded`) — targeted
  by `evictionRunsPastTheKeyCap`: without the call the idle bucket is
  never evicted and the final `tryConsume` returns false.
- `<init>` L40 (ConditionalsBoundary, the props constructor's
  `attemptsPerMinute < 1` guard) — **superseded by #354**: the guard
  moved into the windowed constructor added there, and the old
  constructor now only delegates. The equivalent boundary in the new
  shape is pinned by `windowedConstructorAcceptsExactlyOneAttempt`
  (limit 1 accepted) together with the existing zero/negative
  props-constructor tests.
- `retryAfterSeconds` L137 (ConditionalsBoundary, `>=` vs `>`) —
  **equivalent**: at exactly 1.0 token the deficit path computes
  `(1.0 - 1.0) / rate = 0`, `ceil(0 - 1e-9) = 0`, and returns 0 —
  the same value as the early return. Above 1.0 both branches return
  0; below 1.0 both take the deficit path.
- `retryAfterSeconds` L138 (PrimitiveReturnsMutator, `return 0`) —
  **equivalent**: the mutant replaces the returned value with 0,
  which is what the code already returns.
- `snapshot` L115 (ConditionalsBoundary, `bucket.tokens < capacity`)
  — **equivalent**: at exactly full the deficit path computes 0 ms
  and `resetSeconds` stays 0, the same value as the skipped branch;
  below full both branches take the deficit path.
- `tryConsume` L73 (BooleanTrueReturnVals, `return true`) and L78
  (BooleanFalseReturnVals, `return false`) — **equivalent**: each
  mutant replaces the returned value with the value the code already
  returns.

Still surviving after this follow-up: **none of the 65** — 51
targeted by new tests (37 OrderService + 4 JwtTokenService + 6
TotpService + 4 AuthRateLimiter) + 14 dispositioned above (8
TotpService equivalents + 6 AuthRateLimiter equivalents/superseded)
= 65. The timed-out mutant (`TotpService.verify` L182, a
MathMutator on the skew-step addition) already counts as detected;
it is the HOTP time-step loop, and no separate disposition is
needed.

#### NO_COVERAGE after the widening (backlog #355)

57 mutants remain NO_COVERAGE, down from 162: the eight MockMvc
suites from #345 now drive the cancel / complete / refund / dispute /
handover / read paths. What is left sits in methods no `targetTests`
suite drives: `OrderService` holds 40 (`expireStaleOrder` 10,
`settlementSummary` 8, `sellerSalesCsv` 4, `listOrderAttempts` 3,
`pay` 2, `listSellerOrders` 2, `listOrders` 1, `monthWindow` 1,
`sellerSalesSummary` 1, and 8 single mutants in the `cancel` /
`expireStaleOrder` / `getOrder` / `getSellerOrderFor` /
`listOrderAttempts` / `refund` / `transitionToCompleted` lambdas);
`JwtTokenService` holds 13 across the TOTP-challenge and
session-family methods (`createTotpChallenge`,
`familyBelongsToUser`, `getTotpChallengeTtl`, `liveSessionTokens`,
`mintStepUpAccessToken`, `parseTotpChallenge`, `revokeFamily`,
`sessionFamilyOfToken` and their lambdas); `AuthRateLimiter` holds 4
in its eviction lambda. These are not survivors — no driving test
reaches them at all — and closing them is a future widening item,
not this one.

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

`mutationThreshold` is now **79**: the latest measured mutation
coverage is 477/599 = 79.6% (GitHub Actions run 42434347952,
2026-11-08, head 099aba0 — backlog #355), and the floor is that
score rounded **down** to a whole percent, so an identical rerun
cannot fail on rounding. The floor is never set above the measured
score. The 79.6% is measured on the full 8-class scope with the #345
`targetTests` widening, so unlike the 27 it replaces, this floor is a
measured score for the current scope — not a conservative hold. The
next CI `backend-mutation` run re-measures it; if the new killing
tests land their kills the score rises, and the floor ratchets up
from that run's measured score, rounded down. (History: the floor was
27 from run 39735286602's 81/291 = 27.8%, backlog #212, measured on
the 3-class scope.)

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

162 mutants sat in `OrderService` methods added after #192
(cancel / complete / refund / dispute / handover / read paths) that
only the MockMvc suites exercise. Backlog #345 closed that gap in
scope: the eight MockMvc suites are now in PIT's `targetTests`. Run
42434347952 (backlog #355) measured the result: 57 NO_COVERAGE
remain (see the breakdown above) and the rest are now reached and
mostly killed. They are not survivors — no driving test reaches
them at all — and the 79 floor above is set against this measured
run.

## Honest scope

The mutation score covers the eight classes above — the auth token
service, the order state machine, the shared password policy, TOTP,
the auth token bucket, the payout transitions and the receipt builder
— **not** the codebase.
Controllers, repositories, and the frontend have no mutation score,
and none is claimed for them in the README. Widening the scope
continues one class at a time, with the floor re-measured each time.
Backlog #345 widened `targetTests` to the eight MockMvc suites
driving the `OrderService` methods added after #192; run 42434347952
measured that widened scope at 79.6% (backlog #355).
