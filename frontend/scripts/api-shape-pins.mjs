// Pinned request/response shapes for the api-drift gate (backlog #258).
//
// First tranche: the auth session core + the whole orders surface the
// frontend api modules call (19 operations). The pins mirror the
// frontend's own TypeScript types (frontend/src/api/auth.ts and
// orders.ts), not the backend's DTOs: a pin fails when a field the SPA
// actually reads or sends disappears from the OpenAPI document or
// changes type. Requiredness mirrors the frontend type's optionality:
// a `?`-optional field is pinned as { required: false } — its type is
// checked when present, but its absence alone does not fail the gate.
//
// Field spec forms:
//   "string" | "integer" | "number" | "boolean" | "array" | "object"
//     — shorthand; required, type-checked.
//   { type, required: false } — optional field, type-checked if present.
//   { type: "array", items: <spec> } — item shape checked too.
//   { type: "object", fields: { ... } } — nested object shape.
//   { type: "string", enum: [...] } — every pinned value must still be
//     in the document's enum when the document declares one; a schema
//     with no enum at all passes on type alone (a dropped enum is a
//     weakening, not a disappearance — the type check still holds).
//
// How response shapes resolve (read this before extending the file):
// the gate unwraps the {code,message,data} envelope and pins the data
// object. It collects the data schema from EVERY response of the
// operation that carries the envelope — not just the 2xx one — because
// springdoc drops the default 200 from any method carrying an explicit
// @ApiResponses annotation that lists only error codes. That is the
// current state of every annotated order action (create/pay/cancel/
// ship/complete/confirm-receipt/regenerate/refund) and of the receipt
// read: their success shape is pinned through the error responses
// that carry the same ApiResponse<OrderDto>/ApiResponseReceiptDto
// envelope. What that does NOT prove is that the 200 itself carries
// the shape — typing those success responses is backlog #271.
//   Two operations have NO response pin at all, on purpose:
//   POST /api/auth/register and POST /api/auth/login return
//   ResponseEntity<?> (the 200/201/202 bodies branch at runtime), so
//   springdoc emits bare `{type: object}` success schemas and the
//   RegisterResponse / TotpChallengeResponse components do not exist
//   in the document. Their request shapes ARE pinned; their response
//   shapes join #271. A pin entry without a `response` key is reported
//   in the gate's summary as response-unpinned, never silently passed
//   as checked.
//   POST /api/auth/logout pins the envelope only (response: {}) — the
//   ApiResponseVoid data payload is untyped by design.
//   POST /api/orders/{id}/pay pins no request: the SPA posts an empty
//   body (PayRequest.paymentToken exists on the backend but the SPA
//   never sends it, so it is not a frontend-called field).
//
// Ratchet discipline (#165): pins are added deliberately, with the
// measured document in hand, and never loosened to make a red run
// green. The next tranche is backlog #264 (items + messages +
// notifications + reviews); the auth 2FA/passkey/session/device
// surfaces stay path+method-only until a later tranche pins them.

const AUTH_USER_FIELDS = {
  id: 'integer',
  username: 'string',
  email: 'string',
  roles: { type: 'array', items: 'string' },
  totpEnabled: { type: 'boolean', required: false },
};

const AUTH_RESPONSE_FIELDS = {
  accessToken: 'string',
  tokenType: 'string',
  expiresInSeconds: 'integer',
  user: { type: 'object', fields: AUTH_USER_FIELDS },
};

const ORDER_FIELDS = {
  id: 'integer',
  itemId: 'integer',
  buyerId: 'integer',
  status: {
    type: 'string',
    enum: ['PENDING', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELLED', 'REFUNDED', 'DISPUTED'],
  },
  amountCents: 'integer',
  createdAt: 'string',
  captureId: 'string',
  refundId: 'string',
  // Conditional fields: the SPA reads them only on the paths that
  // carry them (pay/regenerate disclose handoverCode; ship fills the
  // carrier fields), so absence alone must not fail the gate.
  refundedAmountCents: { type: 'integer', required: false },
  handoverCode: { type: 'string', required: false },
  lastPaymentFailure: { type: 'string', required: false },
  carrier: { type: 'string', required: false },
  trackingNumber: { type: 'string', required: false },
  shippedAt: { type: 'string', required: false },
};

const RECEIPT_FIELDS = {
  orderId: 'integer',
  status: {
    type: 'string',
    enum: ['PENDING', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELLED', 'REFUNDED', 'DISPUTED'],
  },
  paid: 'boolean',
  itemId: 'integer',
  itemTitle: 'string',
  amountCents: 'integer',
  formattedPrice: 'string',
  buyerUsername: 'string',
  sellerUsername: 'string',
  createdAt: 'string',
  paidAt: 'string',
  completedAt: 'string',
  captureId: 'string',
  refundId: 'string',
  refundReason: 'string',
  refundedAmountCents: 'integer',
  disputeResolutionNote: 'string',
};

const PAGE_ORDER_FIELDS = {
  content: { type: 'array', items: { type: 'object', fields: ORDER_FIELDS } },
  totalElements: 'integer',
  totalPages: 'integer',
  number: 'integer',
};

const SELLER_SUMMARY_FIELDS = {
  totalOrders: 'integer',
  pendingCount: 'integer',
  paidCount: 'integer',
  completedCount: 'integer',
  cancelledCount: 'integer',
  refundedCount: 'integer',
  grossCents: 'integer',
  currentMonth: {
    type: 'object',
    fields: {
      year: 'integer',
      month: 'integer',
      totalOrders: 'integer',
      completedCount: 'integer',
      grossCents: 'integer',
    },
  },
};

export const API_SHAPE_PINS = [
  // Auth session core.
  {
    method: 'POST',
    path: '/api/auth/register',
    request: { username: 'string', email: 'string', password: 'string' },
    // No response pin: success bodies untyped in the document (see header). Backlog #271.
    responseNote: 'register/login success bodies are ResponseEntity<?> — untyped in the document',
  },
  {
    method: 'POST',
    path: '/api/auth/login',
    request: { usernameOrEmail: 'string', password: 'string' },
    responseNote: 'register/login success bodies are ResponseEntity<?> — untyped in the document',
  },
  {
    method: 'POST',
    path: '/api/auth/refresh',
    response: AUTH_RESPONSE_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/auth/logout',
    response: {},
  },
  {
    method: 'GET',
    path: '/api/auth/me',
    response: AUTH_USER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/auth/username',
    request: { username: 'string' },
    response: AUTH_USER_FIELDS,
  },
  // Orders surface (every operation the api modules call).
  {
    method: 'POST',
    path: '/api/orders',
    request: { itemId: 'integer' },
    response: ORDER_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/orders',
    response: PAGE_ORDER_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/orders/{id}',
    response: ORDER_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/orders/{id}/receipt',
    response: RECEIPT_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/seller/orders',
    response: PAGE_ORDER_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/seller/orders/summary',
    response: SELLER_SUMMARY_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/orders/{id}/pay',
    response: ORDER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/orders/{id}/cancel',
    response: ORDER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/orders/{id}/ship',
    request: { carrier: 'string', trackingNumber: 'string' },
    response: ORDER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/orders/{id}/complete',
    request: { handoverCode: { type: 'string', required: false } },
    response: ORDER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/orders/{id}/confirm-receipt',
    response: ORDER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/orders/{id}/handover-code/regenerate',
    response: ORDER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/orders/{id}/refund',
    request: { reason: 'string', amountCents: { type: 'integer', required: false } },
    response: ORDER_FIELDS,
  },
];
