// Dependency vulnerability gate (backlog #248).
//
// Two scanners, one decision rule — fail on high-or-above, with reviewed
// suppressions honoured instead of silently ignored:
//
//   Frontend: `npm audit --json` is the scanner (see the
//     `dependency-vuln-gate` job in .github/workflows/ci.yml). This script
//     is the decider: it reads the audit report, drops findings whose
//     package+advisory pair is listed in frontend/vuln-suppressions.json,
//     and fails when anything at severity high or critical remains.
//   Backend: the Maven dependency tree (mvn dependency:tree) is scanned
//     against OSV (https://api.osv.dev, no key needed). A finding blocks
//     when its maximum CVSS score is >= 7.0 and its package+advisory pair
//     is not listed in backend/vuln-suppressions.json. Findings with no
//     CVSS score are reported but never block (unscored != high).
//
// Suppression-file discipline (the #165 ratchet rule, not silent ignores):
// every entry MUST carry package, advisory, reason and reviewed (date).
// An entry missing any of those fails the gate with exit code 2, so a
// half-written suppression can never quietly swallow a finding.
// Suppressions are removed by PR when the dependency is upgraded, never
// by editing the gate to look the other way.
//
// Exit codes: 0 = gate passes, 1 = blocked by an unsuppressed finding,
// 2 = usage error, malformed report, or invalid suppression file.
// A malformed report fails closed (exit 2): an unreadable audit is never
// reported as a pass.
//
// Pure functions are exported for scripts/check-vuln-gate.test.mjs; the
// CLI at the bottom only runs when the file is executed directly.

import { readFileSync } from 'node:fs';

export const NPM_BLOCKED_SEVERITIES = new Set(['high', 'critical']);
export const MAVEN_CVSS_THRESHOLD = 7.0;

// ---------------------------------------------------------------- npm ---

/** Parse an `npm audit --json` report into { name: { severity, advisories } }.
 *  `via` entries are objects ({source, url, ...}) for direct advisories or
 *  plain strings for transitive hops; only object entries carry an advisory
 *  identity, so only those are suppression-matchable. */
export function parseNpmAudit(report) {
  if (!report || typeof report !== 'object' || !report.vulnerabilities
    || typeof report.vulnerabilities !== 'object') {
    throw new Error('malformed npm audit report: missing "vulnerabilities" object');
  }
  const out = {};
  for (const [name, v] of Object.entries(report.vulnerabilities)) {
    const advisories = [];
    for (const via of v.via ?? []) {
      if (via && typeof via === 'object') {
        advisories.push({
          id: via.source !== undefined ? String(via.source) : null,
          title: via.title ?? null,
          url: via.url ?? null,
        });
      }
    }
    out[name] = { severity: v.severity ?? 'unknown', advisories };
  }
  return out;
}

/** Does a suppression entry match this npm finding? Matches on package name
 *  plus any advisory identity the report carries (numeric source id, or the
 *  trailing GHSA/CVE token of the advisory URL). */
export function npmSuppressed(name, advisory, suppressions) {
  return suppressions.some((s) => {
    if (s.package !== name) return false;
    if (advisory.id !== null && s.advisory === advisory.id) return true;
    if (advisory.url) {
      const token = advisory.url.split('/').pop();
      if (token && s.advisory === token) return true;
    }
    return false;
  });
}

/** Remaining blocking npm findings after suppressions. A package with no
 *  matchable advisory identity can never be suppressed (fail-closed). */
export function assessNpm(vulns, suppressions) {
  const blocked = [];
  for (const [name, v] of Object.entries(vulns)) {
    if (!NPM_BLOCKED_SEVERITIES.has(v.severity)) continue;
    const unsuppressed = v.advisories.filter((a) => !npmSuppressed(name, a, suppressions));
    if (unsuppressed.length > 0) blocked.push({ package: name, severity: v.severity, advisories: unsuppressed });
  }
  return blocked;
}

// -------------------------------------------------------------- maven ---

/** Parse `mvn dependency:tree` output into unique { name, version, scope }.
 *  Lines look like `+- group:artifact:jar:version:scope` (tree glyphs and
 *  `|  ` prefixes vary); test-jar/duplicate rows collapse by GAV. */
export function parseMavenTree(text) {
  const seen = new Map();
  for (const line of text.split('\n')) {
    const m = line.match(/([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):jar:([^\s:]+):([a-z]+)/);
    if (!m) continue;
    const key = `${m[1]}:${m[2]}@${m[3]}`;
    if (!seen.has(key)) seen.set(key, { name: `${m[1]}:${m[2]}`, version: m[3], scope: m[4] });
  }
  return [...seen.values()];
}

export function maxCvss(vuln) {
  const scores = (vuln.severity ?? [])
    .map((s) => parseFloat(s.score))
    .filter((n) => !Number.isNaN(n));
  return scores.length ? Math.max(...scores) : null;
}

/** Remaining blocking Maven findings after suppressions. `osvResults` is
 *  the querybatch `results` array aligned with `gavs`. */
export function assessMaven(gavs, osvResults, suppressions) {
  const blocked = [];
  const hits = [];
  gavs.forEach((gav, i) => {
    for (const vuln of osvResults[i]?.vulns ?? []) {
      const score = maxCvss(vuln);
      hits.push({ ...gav, id: vuln.id, score });
      if (score === null || score < MAVEN_CVSS_THRESHOLD) continue;
      if (suppressions.some((s) => s.package === gav.name && s.advisory === vuln.id)) continue;
      blocked.push({ ...gav, id: vuln.id, score });
    }
  });
  return { blocked, hits };
}

export async function queryOsv(gavs, fetchFn = fetch) {
  const queries = gavs.map((g) => ({
    package: { name: g.name, ecosystem: 'Maven' }, version: g.version,
  }));
  const results = [];
  for (let i = 0; i < queries.length; i += 100) {
    const res = await fetchFn('https://api.osv.dev/v1/querybatch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ queries: queries.slice(i, i + 100) }),
    });
    if (!res.ok) throw new Error(`OSV query failed: HTTP ${res.status}`);
    const data = await res.json();
    results.push(...(data.results ?? []));
  }
  return results;
}

// -------------------------------------------------------- suppressions ---

export function validateSuppressions(doc, label) {
  if (!doc || typeof doc !== 'object' || !Array.isArray(doc.suppressions)) {
    throw new Error(`invalid suppression file ${label}: expected {"suppressions": [...]}`);
  }
  for (const s of doc.suppressions) {
    for (const field of ['package', 'advisory', 'reason', 'reviewed']) {
      if (!s[field] || typeof s[field] !== 'string') {
        throw new Error(`invalid suppression in ${label}: entry for ${s.package ?? '?'} lacks a ${field}`);
      }
    }
  }
  return doc.suppressions;
}

// ----------------------------------------------------------------- CLI ---

function usage() {
  return 'usage: check-vuln-gate.mjs [--npm-audit FILE --npm-suppressions FILE] '
    + '[--maven-tree FILE --maven-suppressions FILE]';
}

export async function main(argv, deps = {}) {
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    if (!argv[i]?.startsWith('--') || argv[i + 1] === undefined) return { code: 2, message: usage() };
    args[argv[i].slice(2)] = argv[i + 1];
  }
  const fetchFn = deps.fetchFn ?? fetch;
  const blocked = [];
  const summary = [];
  try {
    if (args['npm-audit']) {
      if (!args['npm-suppressions']) return { code: 2, message: `${usage()} (npm-audit needs npm-suppressions)` };
      const report = JSON.parse(readFileSync(args['npm-audit'], 'utf8'));
      const suppressions = validateSuppressions(
        JSON.parse(readFileSync(args['npm-suppressions'], 'utf8')), args['npm-suppressions']);
      const found = assessNpm(parseNpmAudit(report), suppressions);
      summary.push(`npm audit: ${Object.keys(parseNpmAudit(report)).length} packages with findings, `
        + `${found.length} blocking (high/critical, unsuppressed)`);
      blocked.push(...found.map((f) => `npm ${f.package} [${f.severity}] via `
        + f.advisories.map((a) => a.id ?? a.url ?? '?').join(', ')));
    }
    if (args['maven-tree']) {
      if (!args['maven-suppressions']) return { code: 2, message: `${usage()} (maven-tree needs maven-suppressions)` };
      const gavs = parseMavenTree(readFileSync(args['maven-tree'], 'utf8'));
      const suppressions = validateSuppressions(
        JSON.parse(readFileSync(args['maven-suppressions'], 'utf8')), args['maven-suppressions']);
      const results = await queryOsv(gavs, fetchFn);
      const { blocked: mBlocked, hits } = assessMaven(gavs, results, suppressions);
      summary.push(`maven/OSV: ${gavs.length} dependencies, ${hits.length} advisory hits, `
        + `${mBlocked.length} blocking (CVSS >= ${MAVEN_CVSS_THRESHOLD}, unsuppressed)`);
      blocked.push(...mBlocked.map((f) => `maven ${f.name}@${f.version} ${f.id} CVSS ${f.score}`));
    }
  } catch (err) {
    return { code: 2, message: `vuln gate error: ${err.message}` };
  }
  if (Object.keys(args).length === 0) return { code: 2, message: usage() };
  if (blocked.length > 0) {
    return { code: 1, message: `VULN GATE BLOCKED:\n${blocked.map((b) => `  - ${b}`).join('\n')}\n${summary.join('\n')}` };
  }
  return { code: 0, message: `vuln gate passed\n${summary.join('\n')}` };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { code, message } = await main(process.argv.slice(2));
  console.log(message);
  process.exit(code);
}
