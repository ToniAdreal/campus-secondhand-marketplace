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
durations, so the per-endpoint p95 columns for run 42785916434 below
cannot be filled from that run and read "not measured". This commit
adds per-endpoint Trend metrics (`list_duration`, `detail_duration`,
`order_create_duration`, `pay_duration`) to the script, so the next
`load-smoke` run's summary carries them. That next run —
42806090782, head `b41cd9e` (the very commit that added the Trends),
2026-11-10 — is recorded in the second measurement section below
with all four cells filled. The aggregate threshold above stays the
gate until a follow-up considers per-endpoint thresholds from that
measurement.

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
filled in the second measurement section below. The aggregate p95 for
that run was 8.51 ms across all 4417 requests (2620 list + 1771
detail checks passed, 8/8 order+pay pairs, 8838 checks passed,
0 failed).

## Second CI measurement — per-endpoint p95 (run 42806090782, backlog #382)

The first `load-smoke` run on main whose summary carries the
per-endpoint Trends: CI run 42806090782 (head `b41cd9e`, the #365
commit that added the Trends, `load-smoke` SUCCESS, 2026-11-10).
Numbers below are read from that run's k6 summary, both the job
log's `CUSTOM` section and the `k6-load-smoke-summary` artifact's
`k6-summary.json` (they agree to the digit): 4424 requests, 0%
failed (`http_req_failed` 0 out of 4424), checks 8852/8852 passed,
aggregate `http_req_duration` p95 7.66 ms (p90 6.27, avg 4.26, med
3.61, max 662.79), thresholds `p(95)<10` and `rate<0.01` both met.
Reads: 2625 list + 1773 detail; order write path: 8/8 pairs (every
order and pay check passed 8/8).

| Run | Commit | p95 (list) | p95 (detail) | Error rate | Thresholds set |
| --- | ------ | ---------- | ------------ | ---------- | -------------- |
| 42806090782 | b41cd9e | 7.68 ms | 6.94 ms | 0% (0 failed / 4424 requests) | No — aggregate p95 < 10 ms gate stays |

| Run | Commit | p95 (order create) | p95 (pay) | Pairs completed | Error rate | Thresholds set |
| --- | ------ | ------------------ | --------- | --------------- | ---------- | -------------- |
| 42806090782 | b41cd9e | 37.46 ms | 20.48 ms | 8/8 | 0% | No — aggregate p95 < 10 ms gate stays |

Note the write-path p95s (37.46 ms create, 20.48 ms pay) sit well
above the aggregate 10 ms gate: the aggregate is dominated by the
fast reads (2625 + 1773 of 4424 requests), so the aggregate gate
does not constrain the write path at all. A per-endpoint threshold
would need its own measurement basis — one run is not enough to set
one — so no per-endpoint thresholds are set here; the aggregate gate
stays, and a follow-up may set them once more runs accumulate.

## Third CI measurement — per-endpoint p95 across five runs (backlog #420)

Four more `load-smoke` runs have accumulated since run 42806090782,
so the per-endpoint p95s can now be compared across five runs
instead of one. Every number below is read from that run's
`k6-load-smoke-summary` artifact (`k6-summary.json`), cross-checked
against the job log's end-of-run summary where it prints the same
cells (run 45016958229's log agrees to the digit).

| Run | Commit | p95 (list) | p95 (detail) | p95 (aggregate) | Requests | Checks | load-smoke |
| --- | ------ | ---------- | ------------ | --------------- | -------- | ------ | ---------- |
| 42806090782 | b41cd9e | 7.68 ms | 6.94 ms | 7.66 ms | 4424 | 8852/8852 | success |
| 44882267360 | 9f85c93 | 7.71 ms | 7.63 ms | 7.86 ms | 4424 | 8852/8852 | success |
| 44893515480 | 53d79b7 | 8.19 ms | 8.73 ms | 8.61 ms | 4423 | 8850/8850 | success |
| 44999639304 | fd0d3aa | 8.22 ms | 7.76 ms | 8.20 ms | 4422 | 8848/8848 | success |
| 45016958229 | cd0a1c3 | 10.36 ms | 10.26 ms | 10.57 ms | 4403 | 8810/8810 | failure — threshold only |

| Run | Commit | p95 (order create) | p95 (pay) | Pairs completed | Error rate |
| --- | ------ | ------------------ | --------- | --------------- | ---------- |
| 42806090782 | b41cd9e | 37.46 ms | 20.48 ms | 8/8 | 0% |
| 44882267360 | 9f85c93 | 48.27 ms | 23.46 ms | 8/8 | 0% |
| 44893515480 | 53d79b7 | 51.84 ms | 42.88 ms | 8/8 | 0% |
| 44999639304 | fd0d3aa | 50.49 ms | 29.38 ms | 8/8 | 0% |
| 45016958229 | cd0a1c3 | 43.29 ms | 22.34 ms | 8/8 | 0% |

Variance against the tolerance: the p95s count as agreeing only if
max/min stays within 1.25x across the runs (spread at most 25% of
the minimum) — a tolerance fixed here so a later round can reuse or
tighten it instead of re-deciding silently. None of the four
endpoints meets it: list 7.68–10.36 ms (1.35x), detail 6.94–10.26 ms
(1.48x), order create 37.46–51.84 ms (1.38x), pay 20.48–42.88 ms
(2.09x). Excluding the newest run does not save the decision: pay
still spans 20.48–42.88 ms (2.09x), so the disagreement is not a
single-run artifact.

Two structural reasons the numbers move this much. First, the write
path runs only eight pairs per run, so each write p95 is computed
from eight samples — effectively the second-slowest of eight — not a
stable statistic to pin a threshold on. Second, the read endpoints
carry thousands of samples per run (about 2,600 list + 1,770 detail
on the 60/40 split; exact counts read from run 42806090782) and
still vary 35–48%, which is runner noise on GitHub-hosted runners,
not code signal: the five trees span #382–#419 with no change to the
list/detail read paths in that range (the newest tree, #419, adds
only the dispute-evidence download route — `ItemController` and
`ItemService` are untouched between `b41cd9e` and `cd0a1c3`), and the
newest run's medians rose too (list med 3.65 → 4.19 ms) while its
write-path p95s stayed mid-range.

Decision: NO per-endpoint thresholds are set. The runs disagree, so
this item reports the numbers, not a guess. `load/k6-read-smoke.js`
is unchanged, and the aggregate p95 < 10 ms gate stays as the
backstop.

Aggregate-gate breach on the newest run (new finding, tracked as
supplemented backlog #61): run 45016958229 (head `cd0a1c3`, #419
tree, 2026-11-22) measured an aggregate p95 of 10.57 ms, breaching
the `p(95)<10` gate, so its `load-smoke` job failed with k6 exit
code 99 (`thresholds on metrics 'http_req_duration' have been
crossed`). All 8810 checks passed and 0 requests failed — the
failure is the gate, not a correctness defect. The gate is NOT
changed here: whether it needs a runner-noise-aware re-baseline
(per the #212/#365 discipline, from the maximum observed) or the
occasional noise failure is accepted is the #61 triage decision,
not a silent edit in this commit.

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
