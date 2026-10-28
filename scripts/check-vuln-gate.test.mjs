// Tests for scripts/check-vuln-gate.mjs (backlog #248). Run with:
//   node --test scripts/check-vuln-gate.test.mjs   (also a step of the
//   dependency-vuln-gate CI job, so the job-definition check runs in CI too)
// Fixture advisories are synthetic shapes (see scripts/fixtures/*), never
// claims about this repo's real dependencies.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import {
  assessMaven, assessNpm, main, maxCvss, parseMavenTree, parseNpmAudit,
  validateSuppressions,
} from './check-vuln-gate.mjs';

const fixtures = (name) => new URL(`./fixtures/${name}`, import.meta.url).pathname;
const readJson = (name) => JSON.parse(readFileSync(fixtures(name), 'utf8'));

test('job definition: dependency-vuln-gate job exists with both scanners, thresholds and suppression refs', () => {
  const ci = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8');
  assert.match(ci, /dependency-vuln-gate:/);
  assert.match(ci, /npm audit/); // frontend scanner, as specified
  assert.match(ci, /dependency:tree/); // backend tree feeding the OSV scan
  assert.match(ci, /check-vuln-gate\.mjs/);
  assert.match(ci, /frontend\/vuln-suppressions\.json/);
  assert.match(ci, /backend\/vuln-suppressions\.json/);
  assert.match(ci, /check-vuln-gate\.test\.mjs/); // this file runs inside the job
});

test('npm assess: unsuppressed high blocks, suppressed high and moderate do not', () => {
  const vulns = parseNpmAudit(readJson('npm-audit-vulnerable.json'));
  const suppressed = validateSuppressions(readJson('suppressions-sample.json'), 'sample');
  const blocked = assessNpm(vulns, suppressed);
  assert.equal(blocked.length, 1);
  assert.equal(blocked[0].package, 'example-vuln-pkg');
  assert.equal(blocked[0].severity, 'high');
});

test('npm assess: clean audit passes', () => {
  const vulns = parseNpmAudit(readJson('npm-audit-clean.json'));
  assert.deepEqual(assessNpm(vulns, []), []);
});

test('npm assess: malformed report fails closed', () => {
  assert.throws(() => parseNpmAudit({}), /malformed npm audit report/);
});

test('suppression validation: missing reason/reviewed fails closed', () => {
  assert.throws(
    () => validateSuppressions({ suppressions: [{ package: 'x', advisory: 'y' }] }, 'bad'),
    /lacks a reason/,
  );
});

test('maven tree parse: glyphs, scopes and duplicates collapse to unique GAVs', () => {
  const gavs = parseMavenTree([
    '+- org.springframework:spring-core:jar:6.1.6:compile',
    '|  +- org.springframework:spring-core:jar:6.1.6:compile',
    '\\- com.h2database:h2:jar:2.2.224:runtime',
    '[INFO] unrelated line',
  ].join('\n'));
  assert.deepEqual(gavs, [
    { name: 'org.springframework:spring-core', version: '6.1.6', scope: 'compile' },
    { name: 'com.h2database:h2', version: '2.2.224', scope: 'runtime' },
  ]);
});

const osvFixture = (score, id) => [{ vulns: [{ id, severity: [{ type: 'CVSS_V3', score: String(score) }] }] }];

test('maven assess: CVSS >= 7 blocks, below threshold and suppressed do not, unscored never blocks', () => {
  const gavs = [
    { name: 'a:bad', version: '1', scope: 'compile' },
    { name: 'b:ok', version: '2', scope: 'compile' },
    { name: 'c:suppressed', version: '3', scope: 'compile' },
    { name: 'd:unscored', version: '4', scope: 'compile' },
  ];
  const results = [
    ...osvFixture(9.8, 'GHSA-bad-0001'),
    ...osvFixture(5.3, 'GHSA-ok-0002'),
    ...osvFixture(8.1, 'GHSA-sup-0003'),
    [{ vulns: [{ id: 'GHSA-no-score-0004', severity: [] }] }],
  ];
  const suppressions = [{ package: 'c:suppressed', advisory: 'GHSA-sup-0003', reason: 'fixture', reviewed: '2026-10-28' }];
  const { blocked } = assessMaven(gavs, results, suppressions);
  assert.equal(blocked.length, 1);
  assert.equal(blocked[0].id, 'GHSA-bad-0001');
  assert.equal(maxCvss(results[3][0].vulns[0]), null);
});

test('CLI end-to-end: vulnerable fixture goes red (exit 1), clean fixture goes green (exit 0)', () => {
  const script = new URL('./check-vuln-gate.mjs', import.meta.url).pathname;
  const red = spawnSync('node', [script,
    '--npm-audit', fixtures('npm-audit-vulnerable.json'),
    '--npm-suppressions', fixtures('suppressions-sample.json')], { encoding: 'utf8' });
  assert.equal(red.status, 1);
  assert.match(red.stdout, /VULN GATE BLOCKED/);
  assert.match(red.stdout, /example-vuln-pkg/);
  assert.doesNotMatch(red.stdout, /example-suppressed-pkg/);
  const green = spawnSync('node', [script,
    '--npm-audit', fixtures('npm-audit-clean.json'),
    '--npm-suppressions', fixtures('suppressions-empty.json')], { encoding: 'utf8' });
  assert.equal(green.status, 0);
  assert.match(green.stdout, /vuln gate passed/);
});

test('CLI end-to-end: malformed audit fails closed (exit 2), never a pass', async () => {
  const { code } = await main(['--npm-audit', fixtures('suppressions-empty.json'),
    '--npm-suppressions', fixtures('suppressions-empty.json')]);
  assert.equal(code, 2);
});
