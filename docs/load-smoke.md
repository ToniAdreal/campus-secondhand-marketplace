# Load smoke for the read hot paths (backlog #224)

## What it is

`load/k6-read-smoke.js` is a k6 script that hammers the two read hot
paths — the browse list (`GET /api/items`) and the item detail
(`GET /api/items/{id}`) — against the real backend jar on its default
H2 profile, under a fixed, modest VU profile: 30 s ramp to 10 VUs,
60 s hold at 10 VUs, 30 s ramp down, 60% list / 40% detail, 0.2 s think
time. It runs in the CI `load-smoke` job, in the `e2e` job's mould
(the jar is packaged with tests skipped — the `backend-unit` /
`backend-integration` jobs gate the suites on the same push — and the
jar is only a fixture here).

It is a smoke net, not a benchmark: it answers "do the reads survive
concurrent load", not "how many requests per second can this serve".
The performance claims it guards are the #7 single-query list view and
the category cache — exactly the kind that silently regress.

## Seed and bucket arithmetic

The script's `setup()` registers one user and creates eight listings,
then every VU reads with the seed user's token:

- One register: the register bucket is 5/min per IP (#26).
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

## Thresholds — deliberately unset (backlog #212 discipline)

The script carries NO performance thresholds. The first CI run's
measured p95 / error rate are recorded below with the run id, and only
then are thresholds set at or below the measured floor — never guessed.

The CI job DOES fail on failed k6 checks: a non-200 on a seeded read is
a correctness defect, not a performance variance. That gate is not a
performance threshold and needs no measurement.

## First CI measurement (to be filled in after the first `load-smoke` run)

| Run | Commit | p95 (list) | p95 (detail) | Error rate | Thresholds set |
| --- | ------ | ---------- | ------------ | ---------- | -------------- |
| _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | No — follow-up item sets them from this row |

## Honest limits

- Local proof is syntax only: this repo's sandbox cannot run k6
  against the backend (the java binary cannot open TCP sockets here,
  like the PIT profile), so locally the script is only `k6 inspect`
  checked. It is proven by the CI `load-smoke` job, not locally.
- H2 in-memory, one jar, one host, no nginx proxy, no real MySQL:
  this smoke does not prove production behaviour. The compose smoke
  (`docs/smoke-test.md`) owns the MySQL path.
- The k6 summary JSON (`load/k6-summary.json`) is uploaded as a CI
  artifact on every run and git-ignored locally, so the measured
  numbers are retrievable, not just pass/fail.
