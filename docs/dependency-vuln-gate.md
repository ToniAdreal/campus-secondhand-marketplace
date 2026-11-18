# Dependency vulnerability gate (backlog #248)

A dedicated CI job, `dependency-vuln-gate` in `.github/workflows/ci.yml`,
fails the workflow when a dependency already in the tree carries a known
high-or-above vulnerability. Dependabot (#231) opens update PRs, but before
this job nothing failed a build on a vulnerability already present.

## What runs

| Side | Scanner | Decider | Threshold |
| --- | --- | --- | --- |
| Frontend | `npm audit --json` (reads `package-lock.json`; no install needed) | `scripts/check-vuln-gate.mjs` | severity `high` or `critical` |
| Backend | `mvn dependency:tree` + OSV (`https://api.osv.dev`, no key) | same script | max CVSS `>= 7.0` |

The gate script runs its own test suite first inside the job
(`node --test scripts/check-vuln-gate.test.mjs`), including a
job-definition check that reads `ci.yml`: if a later edit drops a scanner
step, a threshold, or a suppression-file reference, the job fails on its
own test instead of silently narrowing.

Findings with no CVSS score are reported but never block (unscored is not
high). A malformed report fails closed (exit 2) — an unreadable audit is
never reported as a pass.

## Suppressions (reviewed false positives only)

- Frontend: `frontend/vuln-suppressions.json`
- Backend: `backend/vuln-suppressions.json`
- Shape: `{"suppressions": [{"package", "advisory", "reason", "reviewed"}]}`.
  Every field is required; the gate refuses entries missing any of them.
  This is the #165 ratchet discipline: a suppression is a reviewed,
  dated, reasoned decision, not a silent ignore. Remove the entry by PR
  when the dependency is upgraded.

The backend file is empty: at this tree the backend needs no suppression
(see baselines below). The frontend file holds the 13 entries from the
2026-11-18 triage (#392) — see "Triage 2026-11-18" below. An advisory
value is the GHSA id: the gate matches it against the trailing token of
the advisory URL in the npm audit report, exactly as the fixture in
`scripts/fixtures/npm-audit-vulnerable.json` shows.

## Local runs

```bash
# Gate script tests (no network)
node --test scripts/check-vuln-gate.test.mjs

# Backend, live OSV scan (offline Maven tree + curl-reachable OSV)
mvn -o -B -f backend/pom.xml dependency:tree -DoutputFile=/tmp/maven-tree.txt \
  -Dmaven.repo.local=<persistent repo>
node scripts/check-vuln-gate.mjs --maven-tree /tmp/maven-tree.txt \
  --maven-suppressions backend/vuln-suppressions.json

# Frontend, once a real audit report exists (CI produces it; see note)
node scripts/check-vuln-gate.mjs --npm-audit frontend/npm-audit.json \
  --npm-suppressions frontend/vuln-suppressions.json
```

## Measured baselines (2026-10-28, head 1b8ea37 + this change)

- Backend, live OSV scan of the full resolved tree: 161 unique
  dependencies, 97 advisory hits, **0 blocking** (none at CVSS >= 7.0).
- Frontend, OSV cross-check over all 353 packages in
  `package-lock.json`: 70 advisory hits, **0 at CVSS >= 7.0**.
- Fixture proof (in the test suite, not a repo finding): the synthetic
  vulnerable audit fixture exits 1 naming only the unsuppressed high
  finding; the clean fixture exits 0.

## Triage 2026-11-18 (backlog #392, head aef2cc0)

Live gate run 44246144113 on main: the frontend gate blocked on 7
packages (28 packages with findings) — @remix-run/router, picomatch,
postcss, react-router, tinypool, vite, vitest — all high/critical in
`npm audit`. The backend steps never ran in that job (the frontend step
fails first), but a local OSV scan of the same tree passes: 161
dependencies, 101 advisory hits, 0 blocking (CVSS >= 7.0).

Upgrades in this change (all within the current major lines; verified
against the npm registry and OSV before applying):

| Package | From | To | Effect |
| --- | --- | --- | --- |
| vite | 5.4.8 | 5.4.21 | fixes 10 of 18 advisories; 8 remain (see suppressions) |
| vitest | 2.1.2 | 2.1.9 | fixes GHSA-9crc-q9x8-hgqq (CRITICAL, RCE via API server); 2 remain |
| @vitest/coverage-v8 | 2.1.2 | 2.1.9 | lockstep with vitest: its exact peer `vitest@2.1.2` made `npm ci` fail on the vitest-only bump (ERESOLVE); 0 advisories at 2.1.9 |
| react-router-dom | 6.26.2 | 6.30.6 | brings react-router 6.30.6 + @remix-run/router 1.23.4; 0 high/critical remain (2 moderate, SSR-only) |
| postcss | 8.4.47 | 8.5.33 | fixes all 4 advisories; 0 remain |

Dependabot PRs #17 (vite 5.4.8 → 5.4.21) and #14 (vitest 2.1.2 →
2.1.9) already carried two of these bumps, but their heads still fail
the gate (run 42826227578: vite still blocked via 8 advisories after
the bump), so the upgrades were applied directly to main here and the
PRs are superseded by this change, not duplicated.

Suppressions added (13, all reviewed 2026-11-18, reasons in
`frontend/vuln-suppressions.json`):

- vite × 8 (GHSA-587h HIGH + 7 moderate/low): all dev-server-only
  (several Windows-only); the dev server is never network-exposed in
  this repo (no `--host`; e2e preview binds 127.0.0.1). Fixed in vite
  6.4.2+ — a vite 6 upgrade is a follow-up, because vitest 2.x
  requires vite ^5.
- vitest × 2: GHSA-5xrq (CRITICAL, UI server — @vitest/ui is only an
  optional peer and is not installed, `vitest --ui` never runs) and
  GHSA-82fw (MODERATE, mocker — the repo's own test code only). Fixed
  in vitest 3.2.6+/4.x.
- tinypool × 2 (both CRITICAL): prototype-pollution gadgets in worker
  options; vitest's worker pool only, options come from vitest and the
  repo's own tests, no untrusted input. No fix in the 1.x line; fixed
  in tinypool 2.1.1/2.1.2, reached via a vitest 3.x/4.x upgrade.
- picomatch × 1 (HIGH, ReDoS): no fixed version exists in any line
  (last_affected 4.0.7, the latest). Transitive build-time dep only;
  glob patterns come from the repo's own config, never user input.

Follow-up (not done here): the coordinated vite 6 + vitest 3/4 major
upgrade that would clear the vite/tinypool/vitest suppressions, and
re-checking the moderate-only react-router advisories (GHSA-337j,
GHSA-wrjc — SSR-only, fixed in 7.18.0) if the app ever gains SSR.

Expected post-change state, OSV cross-check at the new versions (npm
audit itself cannot run in the local sandbox — proxy denies the audit
endpoint, see the honesty note below): vite 5.4.21 has 1 HIGH left
(GHSA-587h, suppressed) + 7 moderate/low (suppressed); vitest 2.1.9
has 1 CRITICAL left (GHSA-5xrq, suppressed) + 1 moderate (suppressed);
tinypool 1.1.1 has 2 CRITICAL (suppressed); picomatch 2.3.2 has 1 HIGH
(suppressed); react-router 6.30.6 has 0 high/critical; postcss 8.5.33
and @remix-run/router 1.23.4 have 0 vulns. One moderate finding needs no
suppression and is recorded here for completeness: GHSA-qxg6 (MEDIUM,
code injection via manual mock registration) affects @vitest/mocker
2.1.9 only, so its package severity is moderate and the gate skips it;
it is cleared by the same vitest 4.x follow-up (fixed in 4.1.12). The live verdict is the
gate job on the next main run.

## Sandbox honesty note

The local sandbox proxy denies the npm audit endpoint
(`POST /-/npm/v1/security/audits/quick` → 403 `policy_denied`), so a live
`npm audit` report cannot be produced on this machine — the same class of
limitation as the k6 job, which is inspect-checked locally and proven in
CI. The frontend gate therefore gets its first live audit verdict from
the first CI run of this job. The OSV cross-check above (0 high-or-above
on the same lockfile) is the reason to expect that run green, but it is a
cross-check, not a substitute: if CI's npm audit goes red on a genuine
high finding, the fix is an upgrade or a suppression entry with a reason,
never editing the gate to pass.
