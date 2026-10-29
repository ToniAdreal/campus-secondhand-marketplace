// Pinned request/response shapes for the api-drift gate (backlogs #258 and #264).
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
// green.
//
// Second tranche (backlog #264): the items + messages +
// notifications + reviews surfaces — every operation those four api
// modules call, except the two that carry no pinnable body: the SSE
// stream (GET /api/notifications/stream, an event stream, not JSON)
// and DELETE /api/notifications/{id} (the SPA reads no body); both
// stay path+method-only. Two new response forms appear here, handled
// by checkResponseData in check-api-drift.mjs: bare-array data
// ({ type: 'array', items: ... } — suggestions, similar,
// price-history, preferences) and bare-scalar data (a bare type
// string — the unread-count and read-all counts). Nullable fields
// (description, sellerUsername, readAt, body on a star-only review
// and the like) are pinned as their base type, required: the schema
// lists the property whether or not a given row carries null, and a
// renamed or retyped property is exactly the drift this gate catches.
// The auth 2FA/passkey/session/device surfaces stay path+method-only
// until a later tranche pins them.

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

const ITEM_PHOTO_FIELDS = {
  id: 'integer',
  url: 'string',
  position: 'integer',
};

const ITEM_FIELDS = {
  id: 'integer',
  title: 'string',
  description: 'string',
  priceCents: 'integer',
  status: {
    type: 'string',
    enum: ['DRAFT', 'AVAILABLE', 'RESERVED', 'SOLD', 'WITHDRAWN', 'EXPIRED'],
  },
  sellerId: 'integer',
  sellerUsername: 'string',
  categoryId: 'integer',
  categoryName: 'string',
  photoUrl: 'string',
  // Optional in the SPA type (createdAt/updatedAt/expiresAt/viewCount
  // are `?`-optional there): type-checked when the document carries
  // them, absence alone does not fail. The detail read and the
  // seller's own rows are the surfaces that carry viewCount; browse
  // payloads omit the key at runtime, but the schema still lists it.
  createdAt: { type: 'string', required: false },
  updatedAt: { type: 'string', required: false },
  photos: { type: 'array', items: { type: 'object', fields: ITEM_PHOTO_FIELDS } },
  expiresAt: { type: 'string', required: false },
  viewCount: { type: 'integer', required: false },
};

const PAGE_ITEM_FIELDS = {
  content: { type: 'array', items: { type: 'object', fields: ITEM_FIELDS } },
  totalElements: 'integer',
  totalPages: 'integer',
  number: 'integer',
};

const ITEM_SUGGESTION_FIELDS = {
  id: 'integer',
  title: 'string',
};

const PRICE_HISTORY_POINT_FIELDS = {
  priceCents: 'integer',
  changedAt: 'string',
};

const MESSAGE_FIELDS = {
  id: 'integer',
  itemId: 'integer',
  senderId: 'integer',
  receiverId: 'integer',
  body: 'string',
  createdAt: 'string',
  readAt: 'string',
  editedAt: 'string',
  deletedAt: 'string',
  photoUrl: 'string',
};

const PAGE_MESSAGE_FIELDS = {
  content: { type: 'array', items: { type: 'object', fields: MESSAGE_FIELDS } },
  totalElements: 'integer',
  totalPages: 'integer',
  number: 'integer',
};

const NOTIFICATION_TYPE_ENUM = [
  'ORDER_PAID',
  'ORDER_SHIPPED',
  'ORDER_COMPLETED',
  'ORDER_REFUNDED',
  'ORDER_CANCELLED',
  'ORDER_EXPIRED',
  'OFFER_ACCEPTED',
  'OFFER_DECLINED',
  'OFFER_COUNTERED',
  'OFFER_COUNTER_ACCEPTED',
  'OFFER_COUNTER_DECLINED',
  'OFFER_EXPIRED',
  'ORDER_DISPUTED',
  'DISPUTE_RESOLVED_REFUND',
  'DISPUTE_RESOLVED_RELEASE',
  'LOGIN_NEW_DEVICE',
  'SAVED_SEARCH_MATCH',
  'FAVORITE_PRICE_DROP',
  'REPORT_APPEAL_UPHELD',
  'REPORT_APPEAL_REJECTED',
  'SESSION_EVICTED',
  'EMAIL_CHANGE_REQUESTED',
  'TOTP_RESET_BY_ADMIN',
  'PASSKEY_REGISTERED',
  'PASSKEY_REMOVED',
  'SELLER_NEW_LISTING',
];

const NOTIFICATION_FIELDS = {
  id: 'integer',
  type: { type: 'string', enum: NOTIFICATION_TYPE_ENUM },
  orderId: 'integer',
  offerId: 'integer',
  savedSearchId: 'integer',
  matchedListingCount: 'integer',
  itemId: 'integer',
  oldPriceCents: 'integer',
  newPriceCents: 'integer',
  appealId: 'integer',
  createdAt: 'string',
  readAt: 'string',
};

const PAGE_NOTIFICATION_FIELDS = {
  content: { type: 'array', items: { type: 'object', fields: NOTIFICATION_FIELDS } },
  totalElements: 'integer',
  totalPages: 'integer',
  number: 'integer',
};

const NOTIFICATION_PREFERENCE_FIELDS = {
  type: { type: 'string', enum: NOTIFICATION_TYPE_ENUM },
  label: 'string',
  security: 'boolean',
  enabled: 'boolean',
};

const STREAM_TICKET_FIELDS = {
  ticket: 'string',
};

const REVIEW_FIELDS = {
  id: 'integer',
  orderId: 'integer',
  buyerId: 'integer',
  reviewerUsername: 'string',
  sellerId: 'integer',
  rating: 'integer',
  body: 'string',
  sellerResponse: 'string',
  createdAt: 'string',
};

const PAGE_REVIEW_FIELDS = {
  content: { type: 'array', items: { type: 'object', fields: REVIEW_FIELDS } },
  totalElements: 'integer',
  totalPages: 'integer',
  number: 'integer',
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
  // Items surface (second tranche, backlog #264).
  {
    method: 'GET',
    path: '/api/items',
    response: PAGE_ITEM_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/items/suggestions',
    response: { type: 'array', items: { type: 'object', fields: ITEM_SUGGESTION_FIELDS } },
  },
  {
    method: 'GET',
    path: '/api/items/{id}',
    response: ITEM_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/items/{id}/price-history',
    response: { type: 'array', items: { type: 'object', fields: PRICE_HISTORY_POINT_FIELDS } },
  },
  {
    method: 'GET',
    path: '/api/items/{id}/similar',
    response: { type: 'array', items: { type: 'object', fields: ITEM_FIELDS } },
  },
  {
    method: 'POST',
    path: '/api/items',
    request: {
      title: 'string',
      description: { type: 'string', required: false },
      priceCents: 'integer',
      categoryId: { type: 'integer', required: false },
      // The SPA only ever sends AVAILABLE or DRAFT; any other value is
      // 422 server-side, so the pin names exactly the values it sends.
      status: { type: 'string', enum: ['AVAILABLE', 'DRAFT'], required: false },
    },
    response: ITEM_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/items/{id}/photo',
    // Multipart form: the SPA posts FormData with a single `file`
    // part; springdoc documents the part as a string (binary) field.
    request: { file: 'string' },
    response: ITEM_FIELDS,
  },
  {
    method: 'PUT',
    path: '/api/items/{id}/photos/order',
    request: { photoIds: { type: 'array', items: 'integer' } },
    response: ITEM_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/items/{id}/renew',
    response: ITEM_FIELDS,
  },
  {
    method: 'PATCH',
    path: '/api/items/{id}/status',
    // The SPA only ever sends SOLD (useMarkSold hardcodes it).
    request: { status: { type: 'string', enum: ['SOLD'] } },
    response: ITEM_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/items/{id}/publish',
    response: ITEM_FIELDS,
  },
  // Messages surface (second tranche, backlog #264).
  {
    method: 'GET',
    path: '/api/messages',
    response: PAGE_MESSAGE_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/messages/unread-count',
    response: 'integer',
  },
  {
    method: 'POST',
    path: '/api/messages',
    request: { itemId: 'integer', receiverId: 'integer', body: 'string' },
    response: MESSAGE_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/messages/photo',
    // Multipart twin of the JSON send: the same three fields plus
    // the `file` part (the sender is the JWT principal, never a field).
    // springdoc models the @RequestParam scalars as query parameters
    // and only `file` in the multipart schema — the gate checks the
    // union of both (see checkShapeDrift), so all four are pinned.
    request: { itemId: 'integer', receiverId: 'integer', body: 'string', file: 'string' },
    response: MESSAGE_FIELDS,
  },
  {
    method: 'PUT',
    path: '/api/messages/{id}',
    request: { body: 'string' },
    response: MESSAGE_FIELDS,
  },
  {
    method: 'DELETE',
    path: '/api/messages/{id}',
    response: MESSAGE_FIELDS,
  },
  // Notifications surface (second tranche, backlog #264). The SSE
  // stream and the row delete carry no pinnable body (see header).
  {
    method: 'GET',
    path: '/api/notifications',
    response: PAGE_NOTIFICATION_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/notifications/unread-count',
    response: 'integer',
  },
  {
    method: 'POST',
    path: '/api/notifications/stream-ticket',
    response: STREAM_TICKET_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/notifications/preferences',
    response: { type: 'array', items: { type: 'object', fields: NOTIFICATION_PREFERENCE_FIELDS } },
  },
  {
    method: 'PUT',
    path: '/api/notifications/preferences/{type}',
    request: { enabled: 'boolean' },
    response: NOTIFICATION_PREFERENCE_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/notifications/read-all',
    response: 'integer',
  },
  {
    method: 'POST',
    path: '/api/notifications/{id}/read',
    response: NOTIFICATION_FIELDS,
  },
  // Reviews surface (second tranche, backlog #264).
  {
    method: 'POST',
    path: '/api/orders/{id}/review',
    request: { rating: 'integer', body: { type: 'string', required: false } },
    response: REVIEW_FIELDS,
  },
  {
    method: 'PUT',
    path: '/api/reviews/{id}/response',
    request: { response: 'string' },
    response: REVIEW_FIELDS,
  },
  {
    method: 'PUT',
    path: '/api/reviews/{id}',
    request: { rating: 'integer', body: 'string' },
    response: REVIEW_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/users/{id}/reviews',
    response: PAGE_REVIEW_FIELDS,
  },
];
