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

Both files start empty: at this tree neither side needs a suppression
(see baselines below).

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
