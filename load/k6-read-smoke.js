/*
 * Load smoke for the read hot paths and the order write path
 * (backlog #224, #360).
 *
 * What this proves — and what it does not:
 * - Reads: it drives the REAL backend jar on its default H2 profile (see
 *   the CI `load-smoke` job, in the `e2e` job's mould) with the browse list
 *   (GET /api/items) and the item detail (GET /api/items/{id}) under a
 *   fixed, modest VU profile below. It is a smoke net, not a benchmark:
 *   it answers "do the reads survive concurrent load" (the #7
 *   single-query list view and the category cache are exactly the kind
 *   of performance claims that silently regress), not "how many
 *   requests per second can this serve".
 * - Writes: one VU drives the order write path — POST /api/orders with a
 *   fresh Idempotency-Key, then POST /api/orders/{id}/pay — while the 10
 *   read VUs hammer the list/detail views, so the create -> pay state
 *   machine (idempotency, optimistic locking, handover-code minting) is
 *   exercised under concurrent read load. It is a smoke net, not a race
 *   test: the two-buyers-racing proof lives in the Testcontainers *IT
 *   (backlog #10), the decline path (tok_decline -> 402) lives in backend
 *   tests (backlog #92), and the pay response's handover code is asserted
 *   present but never consumed here (completion is a seller step,
 *   backlog #179).
 * - It does NOT prove production behaviour: H2 in-memory, one jar, one
 *   host, no nginx proxy, no real MySQL. The compose smoke
 *   (docs/smoke-test.md) owns the MySQL path; this owns the read path
 *   under concurrency.
 *
 * Scenarios (one script, two VU profiles — the CI job runs this file once):
 * - reads: 30 s ramp to 10 VUs, 60 s hold at 10 VUs, 30 s ramp down to 0.
 *   60% list reads, 40% detail reads, 0.2 s think time between iterations.
 * - order_write: 1 VU for the full 120 s, one order+pay pair per iteration
 *   with a WRITE_PAUSE_S pause between pairs. One VU on purpose: the
 *   write path's concurrency proof is the #10 *IT, and a single buyer
 *   keeps the pair pacing trivially under the buckets below.
 *
 * Seed arithmetic (the rate-limit buckets it must NOT trip):
 * - setup() registers TWO users: the register bucket is 5/min per IP
 *   (backlog #26), so two registers are far under it. The email-
 *   verification gate is OFF in this stack
 *   (APP_AUTH_EMAIL_VERIFICATION_ENABLED=false, backlog #138), so
 *   registration returns a session directly.
 * - The seller (k6load) creates EIGHT listings: the listing-create bucket
 *   is 10/min per authenticated user (backlog #219), so eight stays under
 *   the cap with room for a retry. Eight is one short page, which is
 *   enough: the list view is exercised per iteration and the detail
 *   reads cycle through eight real ids.
 * - The buyer (k6buyer) places the orders: a buyer cannot order their own
 *   listing (OrderCreationService), so the write path needs the second
 *   account, which carries its own 20/min buckets (#219/#228). One
 *   active order per item (uq_order_active_item, backlog #8) means each
 *   pair consumes one distinct listing — eight listings cap the run at
 *   eight pairs.
 * - Pair pacing: eight pairs over 120 s is ~4/min, against the
 *   order-create bucket (20/min per user, backlog #219) and the
 *   order-transition bucket (20/min per user, backlog #228) — a 5x
 *   margin on both, so the write VU never fights a bucket even if the
 *   runner is slow. If the runner is slow, fewer than eight pairs
 *   complete; the gate is the per-step checks, not the pair count.
 * - The load phase uses the seed seller's token for every read VU, so
 *   every detail read is a SELLER read: the #186 view counter only counts
 *   authenticated non-seller reads, so the load stays read-only in
 *   effect (no view-count writes hiding inside the "read" load).
 * - List/detail reads are unthrottled today (backlog #228 covers only
 *   transition/mutation POSTs), so the VU profile is not fighting a
 *   bucket. If a future item throttles reads, this profile must be
 *   re-derived against the new bucket first.
 *
 * Thresholds: set from CI run 42785916434 (head a4ce162, backlog #365):
 * http_req_duration p(95) < 10 ms (measured aggregate p95 8.51 ms) and
 * http_req_failed rate < 0.01 (measured 0) — rounded one step in the
 * lenient direction per the #212 precedent (the mutation floor was set
 * to 27 from a measured 27.8%). Per-endpoint Trends below record
 * list/detail/order-create/pay durations separately: run 42785916434's
 * summary carried only the aggregate http_req_duration, so the
 * per-endpoint p95s in docs/load-smoke.md are filled from the next
 * run. The CI job does fail on failed checks (a non-200 on a seeded
 * read or a failed write step is a correctness defect, not a
 * performance variance); that gate is not a performance threshold and
 * needs no measurement.
 *
 * Local limitation, stated honestly: this repo's sandbox cannot run k6
 * against the backend (the java binary cannot open TCP sockets here,
 * like the PIT profile), so locally this script is only syntax-checked
 * (`k6 inspect` with the pinned k6 v2.3.0). It is proven by the CI
 * `load-smoke` job, not locally.
 *
 * Usage (CI): k6 run --summary-export load/k6-summary.json load/k6-read-smoke.js
 *   with BASE_URL pointing at the jar (default http://127.0.0.1:8080).
 */

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// Per-endpoint duration Trends (backlog #365): the summary of the first
// measured run (42785916434) carried only the aggregate
// http_req_duration, so the docs' per-endpoint p95 cells could not be
// filled from it. These Trends make the next run's summary carry
// list/detail/order-create/pay p95s separately.
const listDuration = new Trend('list_duration');
const detailDuration = new Trend('detail_duration');
const orderCreateDuration = new Trend('order_create_duration');
const payDuration = new Trend('pay_duration');

export const options = {
  // Thresholds set from CI run 42785916434 (head a4ce162, backlog #365):
  // aggregate http_req_duration p95 8.51 ms -> p(95) < 10 ms, and
  // http_req_failed 0 -> rate < 0.01. Rounded one step in the lenient
  // direction per the #212 precedent (mutation floor 27 from 27.8%).
  thresholds: {
    http_req_duration: ['p(95)<10'],
    http_req_failed: ['rate<0.01'],
  },
  scenarios: {
    reads: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '60s', target: 10 },
        { duration: '30s', target: 0 },
      ],
      exec: 'default',
    },
    order_write: {
      executor: 'constant-vus',
      vus: 1,
      duration: '120s',
      exec: 'writeOrders',
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const SEED_LISTINGS = 8;
const LIST_WEIGHT = 0.6;
// Pause between order+pay pairs: 8 pairs over the 120 s run is ~4/min, a
// 5x margin under the 20/min order-create (#219) and order-transition
// (#228) buckets. The write VU never fights a bucket even on a slow runner.
const WRITE_PAUSE_S = 13;

function mustOk(res, what) {
  if (res.status !== 200 || res.json('code') !== 0) {
    fail(`${what} failed: status=${res.status} body=${res.body}`);
  }
}

function register(username, email) {
  const res = http.post(
    `${BASE_URL}/api/auth/register`,
    JSON.stringify({
      username,
      email,
      // 12+ chars, letters + digits, not on the common-password denylist
      // (backlog #37 / #131 / #197 rules).
      password: 'K6LoadSmoke7x9Q2',
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  mustOk(res, `seed registration (${username})`);
  const token = res.json('data.accessToken');
  if (!token) {
    fail(`seed registration (${username}) returned no accessToken`);
  }
  return token;
}

export function setup() {
  const jsonHeaders = { 'Content-Type': 'application/json' };

  const token = register('k6load', 'k6load@example.com');
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

  // Second account: a buyer cannot order their own listing, so the write
  // path needs its own user (with its own 20/min buckets, #219/#228).
  const buyerToken = register('k6buyer', 'k6buyer@example.com');
  return { token, buyerToken, ids };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  if (Math.random() < LIST_WEIGHT) {
    const res = http.get(`${BASE_URL}/api/items?page=0&size=20`, { headers });
    listDuration.add(res.timings.duration);
    check(res, {
      'list 200 + envelope ok': (r) => r.status === 200 && r.json('code') === 0,
      'list returns a page': (r) => Array.isArray(r.json('data.content')),
    });
  } else {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    const res = http.get(`${BASE_URL}/api/items/${id}`, { headers });
    detailDuration.add(res.timings.duration);
    check(res, {
      'detail 200 + envelope ok': (r) => r.status === 200 && r.json('code') === 0,
      'detail returns the requested listing': (r) => r.json('data.id') === id,
    });
  }
  sleep(0.2);
}

// Index of the next seed listing the write VU will order. Per-VU state:
// the order_write scenario runs exactly one VU, so no sharing is needed.
let nextListing = 0;

export function writeOrders(data) {
  if (nextListing >= data.ids.length) {
    // Seed exhausted: eight pairs is the run's cap (one active order per
    // item, #8). Idle until the scenario ends — no checks, no failures.
    sleep(5);
    return;
  }
  const itemId = data.ids[nextListing];
  nextListing += 1;
  const headers = {
    Authorization: `Bearer ${data.buyerToken}`,
    'Content-Type': 'application/json',
    // Fresh key per pair: a retried key would replay the original order
    // instead of creating a new one (#9), so the key must never repeat.
    'Idempotency-Key': `k6-write-${__VU}-${__ITER}-${Date.now()}`,
  };

  const orderRes = http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify({ itemId }),
    { headers },
  );
  orderCreateDuration.add(orderRes.timings.duration);
  const orderOk = check(orderRes, {
    'order 200 + envelope ok': (r) => r.status === 200 && r.json('code') === 0,
    'order is PENDING': (r) => r.json('data.status') === 'PENDING',
    'order carries its id': (r) => typeof r.json('data.id') === 'number',
  });
  if (!orderOk) {
    // A failed create has no order to pay: skip the pay for this pair
    // instead of cascading a 404 into a second failed check.
    sleep(WRITE_PAUSE_S);
    return;
  }

  const orderId = orderRes.json('data.id');
  // Empty body: no paymentToken, so the mock PSP captures normally
  // (tok_decline -> 402 is the backend-tested decline path, #92).
  const payRes = http.post(
    `${BASE_URL}/api/orders/${orderId}/pay`,
    JSON.stringify({}),
    { headers },
  );
  payDuration.add(payRes.timings.duration);
  check(payRes, {
    'pay 200 + envelope ok': (r) => r.status === 200 && r.json('code') === 0,
    'pay flips order to PAID': (r) => r.json('data.status') === 'PAID',
    'pay returns a capture id': (r) => typeof r.json('data.captureId') === 'string'
      && r.json('data.captureId').length > 0,
    // Handover code is minted exactly once, in the pay response (#179);
    // asserted present here, never consumed (completion is a seller step).
    'pay mints a 6-digit handover code': (r) => typeof r.json('data.handoverCode') === 'string'
      && r.json('data.handoverCode').length === 6,
  });
  sleep(WRITE_PAUSE_S);
}
