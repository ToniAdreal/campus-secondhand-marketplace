/*
 * Load smoke for the read hot paths (backlog #224).
 *
 * What this proves — and what it does not:
 * - It drives the REAL backend jar on its default H2 profile (see the CI
 *   `load-smoke` job, in the `e2e` job's mould) with the browse list
 *   (GET /api/items) and the item detail (GET /api/items/{id}) under a
 *   fixed, modest VU profile below. It is a smoke net, not a benchmark:
 *   it answers "do the reads survive concurrent load" (the #7
 *   single-query list view and the category cache are exactly the kind
 *   of performance claims that silently regress), not "how many
 *   requests per second can this serve".
 * - It does NOT prove production behaviour: H2 in-memory, one jar, one
 *   host, no nginx proxy, no real MySQL. The compose smoke
 *   (docs/smoke-test.md) owns the MySQL path; this owns the read path
 *   under concurrency.
 *
 * VU profile (fixed, modest — 10 VUs is deliberate: the jar runs on a
 * shared CI runner next to the build, and this smoke must never be the
 * reason a push goes red on runner noise):
 * - 30 s ramp to 10 VUs, 60 s hold at 10 VUs, 30 s ramp down to 0.
 * - 60% list reads, 40% detail reads, 0.2 s think time between iterations.
 *
 * Seed arithmetic (the rate-limit buckets it must NOT trip):
 * - setup() registers ONE user: the register bucket is 5/min per IP
 *   (backlog #26), so one register is far under it. The email-
 *   verification gate is OFF in this stack
 *   (APP_AUTH_EMAIL_VERIFICATION_ENABLED=false, backlog #138), so
 *   registration returns a session directly.
 * - setup() creates EIGHT listings: the listing-create bucket is
 *   10/min per authenticated user (backlog #219), so eight stays under
 *   the cap with room for a retry. Eight is one short page, which is
 *   enough: the list view is exercised per iteration and the detail
 *   reads cycle through eight real ids.
 * - The load phase uses the seed user's token for every VU, so every
 *   detail read is a SELLER read: the #186 view counter only counts
 *   authenticated non-seller reads, so the load stays read-only in
 *   effect (no view-count writes hiding inside the "read" load).
 * - List/detail reads are unthrottled today (backlog #228 covers only
 *   transition/mutation POSTs), so the VU profile is not fighting a
 *   bucket. If a future item throttles reads, this profile must be
 *   re-derived against the new bucket first.
 *
 * Thresholds: NONE — deliberately (backlog #212 discipline). The first
 * CI run's measured p95 / error rate are recorded in docs/load-smoke.md
 * with the run id, and only then are thresholds set at or below the
 * measured floor — never guessed in this script. The CI job does fail
 * on failed checks (a non-200 on a seeded read is a correctness
 * defect, not a performance variance); that gate is not a performance
 * threshold and needs no measurement.
 *
 * Local limitation, stated honestly: this repo's sandbox cannot run k6
 * against the backend (the java binary cannot open TCP sockets here,
 * like the PIT profile), so locally this script is only syntax-checked
 * (`k6 inspect`). It is proven by the CI `load-smoke` job, not locally.
 *
 * Usage (CI): k6 run --summary-export load/k6-summary.json load/k6-read-smoke.js
 *   with BASE_URL pointing at the jar (default http://127.0.0.1:8080).
 */

import http from 'k6/http';
import { check, fail, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '60s', target: 10 },
    { duration: '30s', target: 0 },
  ],
};

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const SEED_LISTINGS = 8;
const LIST_WEIGHT = 0.6;

function mustOk(res, what) {
  if (res.status !== 200 || res.json('code') !== 0) {
    fail(`${what} failed: status=${res.status} body=${res.body}`);
  }
}

export function setup() {
  const jsonHeaders = { 'Content-Type': 'application/json' };

  const reg = http.post(
    `${BASE_URL}/api/auth/register`,
    JSON.stringify({
      username: 'k6load',
      email: 'k6load@example.com',
      // 12+ chars, letters + digits, not on the common-password denylist
      // (backlog #37 / #131 / #197 rules).
      password: 'K6LoadSmoke7x9Q2',
    }),
    { headers: jsonHeaders },
  );
  mustOk(reg, 'seed registration');
  const token = reg.json('data.accessToken');
  if (!token) {
    fail('seed registration returned no accessToken');
  }
  const authHeaders = { Authorization: `Bearer ${token}`, ...jsonHeaders };

  const ids = [];
  for (let i = 0; i < SEED_LISTINGS; i += 1) {
    const created = http.post(
      `${BASE_URL}/api/items`,
      JSON.stringify({ title: `k6 load seed ${i}`, priceCents: 1000 + i }),
      { headers: authHeaders },
    );
    mustOk(created, `seed listing ${i}`);
    ids.push(created.json('data.id'));
  }
  return { token, ids };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  if (Math.random() < LIST_WEIGHT) {
    const res = http.get(`${BASE_URL}/api/items?page=0&size=20`, { headers });
    check(res, {
      'list 200 + envelope ok': (r) => r.status === 200 && r.json('code') === 0,
      'list returns a page': (r) => Array.isArray(r.json('data.content')),
    });
  } else {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    const res = http.get(`${BASE_URL}/api/items/${id}`, { headers });
    check(res, {
      'detail 200 + envelope ok': (r) => r.status === 200 && r.json('code') === 0,
      'detail returns the requested listing': (r) => r.json('data.id') === id,
    });
  }
  sleep(0.2);
}
