# Load smoke for the read hot paths + the order write path (backlog #224, #360)

## What it is

`load/k6-read-smoke.js` is a k6 script with two scenarios that runs in the
CI `load-smoke` job, in the `e2e` job's mould (the jar is packaged with
tests skipped — the `backend-unit` / `backend-integration` jobs gate the
suites on the same push — and the jar is only a fixture here):

- `reads`: 10 VUs hammer the two read hot paths — the browse list
  (`GET /api/items`) and the item detail (`GET /api/items/{id}`) — against
  the real backend jar on its default H2 profile, under a fixed, modest VU
  profile: 30 s ramp to 10 VUs, 60 s hold at 10 VUs, 30 s ramp down, 60%
  list / 40% detail, 0.2 s think time.
- `order_write`: 1 VU drives the order write path — `POST /api/orders`
  with a fresh Idempotency-Key, then `POST /api/orders/{id}/pay` — while
  the reads run, one pair per iteration with a 13 s pause between pairs.

It is a smoke net, not a benchmark: it answers "do the reads survive
concurrent load" and "does the create -> pay state machine survive under
that load", not "how many requests per second can this serve". The
performance claims it guards are the #7 single-query list view and the
category cache — exactly the kind that silently regress — plus the order
write path the whole marketplace exists for (idempotency, optimistic
locking, handover-code minting).

## Seed and bucket arithmetic

The script's `setup()` registers two users — a seller and a buyer — and
the seller creates eight listings; then the read VUs read with the seller's
token and the single write VU orders with the buyer's token:

- Two registers: the register bucket is 5/min per IP (#26).
- Eight creates: the listing-create bucket is 10/min per authenticated
  user (#219) — eight stays under the cap with room for a retry.
- The email-verification gate is OFF in this stack
  (`APP_AUTH_EMAIL_VERIFICATION_ENABLED=false`, #138), so registration
  returns a session directly.
- Every detail read is a SELLER read (the token belongs to the listings'
  owner), so the #186 view counter — which counts only authenticated
  non-seller reads — never turns the "read" load into hidden writes.
- List/detail reads are unthrottled today (#228 throttles only
  transition/mutation POSTs). If a future item throttles reads, this
  profile must be re-derived against the new bucket first.

## Order write path

The buyer account exists because a buyer cannot order their own listing
(`OrderCreationService`), so the write path needs a second user — which
carries its own 20/min buckets. One active order per item
(`uq_order_active_item`, #8) means each pair consumes one distinct
listing, so eight listings cap the run at eight pairs. Pair pacing is
13 s: eight pairs over the 120 s run is ~4/min, against the order-create
bucket (20/min per user, #219) and the order-transition bucket (20/min
per user, #228) — a 5x margin on both, so the write VU never fights a
bucket even if the runner is slow. If the runner is slow, fewer than
eight pairs complete; the gate is the per-step checks, not the pair
count. Each pair asserts the 200 envelope on both steps, the order
flipping PENDING -> PAID, a non-empty capture id, and the 6-digit
handover code minted in the pay response (#179). A failed create skips
that pair's pay instead of cascading a 404 into a second failed check.

What it does NOT prove: buyer-race concurrency (two buyers racing on one
item is the Testcontainers *IT, #10); the decline path (`tok_decline` ->
402 is backend-tested, #92); handover completion (the pay response's
handover code is asserted present but never consumed — completion is a
seller step, #179).

## Thresholds — set from the first clean CI measurement (backlog #365)

Measured in CI run 42785916434 (head `a4ce162`, `load-smoke` SUCCESS,
2026-11-10): 4417 requests, 0% failed (`http_req_failed` value 0),
checks 8838/8838 passed, aggregate `http_req_duration` p95 8.51 ms
(p90 7.06, avg 4.67, med 3.73, max 726.92). Thresholds in
`load/k6-read-smoke.js` are set from those numbers, rounded one step
in the lenient direction per the #212 precedent (the mutation floor
was set to 27 from a measured 27.8% — rounded to be slightly easier
than measured; for latency the easier direction is up):

- `http_req_duration`: `p(95) < 10` ms (measured aggregate p95 8.51 ms)
- `http_req_failed`: `rate < 0.01` (measured 0)

One honest limitation of this run: the summary carries only the
AGGREGATE `http_req_duration` — the script recorded no per-endpoint
durations, so the per-endpoint p95 columns below cannot be filled from
this run and read "not measured". This commit adds per-endpoint Trend
metrics (`list_duration`, `detail_duration`, `order_create_duration`,
`pay_duration`) to the script, so the next `load-smoke` run's summary
carries them and a follow-up fills these cells. The aggregate
threshold above is the gate until then.

The CI job still fails on failed k6 checks: a non-200 on a seeded read
or a failed write step is a correctness defect, not a performance
variance. That gate is not a performance threshold and needs no
measurement.

## First CI measurement — reads (run 42785916434)

| Run | Commit | p95 (list) | p95 (detail) | Error rate | Thresholds set |
| --- | ------ | ---------- | ------------ | ---------- | -------------- |
| 42785916434 | a4ce162 | not measured* | not measured* | 0% (0 failed / 4417 requests) | Yes — aggregate p95 < 10 ms + error rate < 1% |

## First CI measurement — order write path (run 42785916434, #360 script)

| Run | Commit | p95 (order create) | p95 (pay) | Pairs completed | Error rate | Thresholds set |
| --- | ------ | ------------------ | --------- | --------------- | ---------- | -------------- |
| 42785916434 | a4ce162 | not measured* | not measured* | 8/8 | 0% | Yes — aggregate p95 < 10 ms + error rate < 1% |

\* Per-endpoint p95 was not measurable from run 42785916434's summary:
k6 exports only the aggregate `http_req_duration` when the script
records no per-endpoint Trends. The Trends landed with this commit;
the next `load-smoke` run's summary carries them, and these cells are
filled then. The aggregate p95 for that run was 8.51 ms across all
4417 requests (2620 list + 1771 detail checks passed, 8/8 order+pay
pairs, 8838 checks passed, 0 failed).

## Honest limits

- Local proof is syntax only: this repo's sandbox cannot run k6
  against the backend (the java binary cannot open TCP sockets here,
  like the PIT profile), so locally the script is only `k6 inspect`
  checked (pinned k6 v2.3.0, same as CI). It is proven by the CI
  `load-smoke` job, not locally.
- H2 in-memory, one jar, one host, no nginx proxy, no real MySQL:
  this smoke does not prove production behaviour. The compose smoke
  (`docs/smoke-test.md`) owns the MySQL path.
- The k6 summary JSON (`load/k6-summary.json`) is uploaded as a CI
  artifact on every run and git-ignored locally, so the measured
  numbers are retrievable, not just pass/fail.
