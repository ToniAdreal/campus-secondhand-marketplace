// Pinned request/response shapes for the api-drift gate (backlogs #258, #264, #271, #274 and #281).
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
// object. For a plain `response` pin it prefers the 2xx carriers:
// when any 2xx response carries an envelope, the pin must pass
// against a 2xx data schema — an error response carrying the old DTO
// must not mask a success-only field that disappeared from the 200
// (backlog #271). Only operations with no 2xx envelope fall back to
// any carrier, the pre-#271 behaviour the #258 error-carried pins
// relied on. Since #271 every annotated order action
// (create/pay/cancel/ship/complete/confirm-receipt/regenerate/refund)
// and the receipt read carry a content-bearing 200 of their own:
// springdoc fills a bare 200 entry from the method's
// ApiResponse<OrderDto>/ApiResponse<ReceiptDto> return type, exactly
// as it already filled the error entries. Backlog #274 extends that
// fix to the offers/disputes surfaces: the offer actions, the dispute
// open/read, the ADMIN queue list and the evidence add/list all
// carried error-only @ApiResponses, so their typed success bodies
// were invisible to the gate; each now carries a bare 200 entry and
// is pinned against it, never against an error carrier.
//   Endpoints whose success body branches by status pin each variant
// against its own status with `responseByStatus` (never against a
// sibling status's shape): POST /api/auth/register answers 200 with
// the token pair when the verification gate is off and 201 with the
// pending-verification body when it is on; POST /api/auth/login
// answers 200 with the token pair and 202 with the TOTP challenge.
// Both methods return ResponseEntity<?> (the bodies branch at
// runtime), so the 200s reuse the ApiResponseAuthResponse component
// the refresh endpoint's typed return generates, while the 201/202
// carry inline envelopes whose data is the RegisterResponse /
// TotpChallengeResponse — no envelope component for those exists
// anywhere else to reference. A pin entry without a `response` (or
// `responseByStatus`) key is reported in the gate's summary as
// response-unpinned, never silently passed as checked.
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
//
// Third tranche (backlog #274): the offers + seller payouts +
// disputes surfaces — every operation those three api modules call,
// except DELETE /api/disputes/{disputeId}/evidence/{evidenceId},
// which answers ApiResponseVoid and whose data the SPA never reads:
// it is pinned with a responseNote only, so it stays explicitly
// listed as response-unpinned in the gate's summary instead of
// silently passing as checked. DisputeDto.openedBy (backlog #255) is
// backend-only — the SPA's OrderDispute type does not carry it, so
// it is not pinned. DisputeDto.previousStatus is pinned to the
// frontend type's PAID/COMPLETED, not the backend enum's full range.
//
// Fourth tranche (backlog #281): the social graph — favorites,
// follows, blocks, saved searches and recently-viewed, every
// operation those five api modules call. The two listing reads
// (favorites, recently-viewed) reuse ITEM_FIELDS: both answer the
// same list-view ItemDto projection as GET /api/items. The seven
// writes answer ApiResponseVoid and the SPA reads no body from any
// of them, so each is pinned with a responseNote only, exactly like
// the #274 evidence delete — listed explicitly as response-unpinned,
// never silently checked. Note the blocks write paths: the SPA posts
// and deletes /api/users/{id}/block, not /api/users/me/blocks/{id}
// (the backlog's shorthand); the pins name the paths the modules
// actually call. Nullable saved-search fields (keyword, categoryId,
// min/max price) are pinned as their base types, required, per the
// #264 nullable rule, and the create request pins all five filter
// fields as required: false, mirroring SavedSearchInput's optionality.

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

// Register 201 (backlog #271): no session — just the user summary plus
// the always-true verification flag (mirrors frontend RegisterPendingBody).
const REGISTER_PENDING_FIELDS = {
  user: { type: 'object', fields: AUTH_USER_FIELDS },
  emailVerificationRequired: 'boolean',
};

// Login 202 (backlog #271): the signed second-factor challenge
// (mirrors frontend TotpChallenge; factors lists totp and/or passkey).
const TOTP_CHALLENGE_FIELDS = {
  challenge: 'string',
  expiresAt: 'string',
  factors: { type: 'array', items: 'string' },
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

const OFFER_FIELDS = {
  id: 'integer',
  itemId: 'integer',
  buyerId: 'integer',
  sellerId: 'integer',
  priceCents: 'integer',
  // Nullable in the SPA type (null until the seller counters / an
  // accept creates the order): pinned as the base type, required, per
  // the #264 nullable rule — the schema lists the property either way.
  counterPriceCents: 'integer',
  status: {
    type: 'string',
    enum: ['PENDING', 'COUNTERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'WITHDRAWN'],
  },
  orderId: 'integer',
  expiresAt: 'string',
  createdAt: 'string',
};

const PAGE_OFFER_FIELDS = {
  content: { type: 'array', items: { type: 'object', fields: OFFER_FIELDS } },
  totalElements: 'integer',
  totalPages: 'integer',
  number: 'integer',
};

const SELLER_PAYOUT_FIELDS = {
  id: 'integer',
  orderId: 'integer',
  sellerId: 'integer',
  amountCents: 'integer',
  adjustmentsCents: 'integer',
  netCents: 'integer',
  status: { type: 'string', enum: ['PENDING', 'PAYABLE', 'PAID'] },
  createdAt: 'string',
  payableAt: 'string',
  paidAt: 'string',
};

const PAGE_PAYOUT_FIELDS = {
  content: { type: 'array', items: { type: 'object', fields: SELLER_PAYOUT_FIELDS } },
  totalElements: 'integer',
  totalPages: 'integer',
  number: 'integer',
};

const SELLER_PAYOUT_TOTALS_FIELDS = {
  pendingCount: 'integer',
  pendingCents: 'integer',
  payableCount: 'integer',
  payableCents: 'integer',
  paidCount: 'integer',
  paidCents: 'integer',
  adjustmentsCents: 'integer',
  netCents: 'integer',
};

const DISPUTE_FIELDS = {
  id: 'integer',
  orderId: 'integer',
  buyerId: 'integer',
  sellerId: 'integer',
  reason: 'string',
  status: { type: 'string', enum: ['OPEN', 'RESOLVED_REFUND', 'RESOLVED_RELEASE'] },
  // Frontend type only: PAID/COMPLETED (see header note).
  previousStatus: { type: 'string', enum: ['PAID', 'COMPLETED'] },
  listingRestored: 'boolean',
  createdAt: 'string',
  resolvedAt: 'string',
  resolutionNote: 'string',
};

const PAGE_DISPUTE_FIELDS = {
  content: { type: 'array', items: { type: 'object', fields: DISPUTE_FIELDS } },
  totalElements: 'integer',
  totalPages: 'integer',
  number: 'integer',
};

const DISPUTE_EVIDENCE_FIELDS = {
  id: 'integer',
  disputeId: 'integer',
  uploaderId: 'integer',
  url: 'string',
  createdAt: 'string',
};

const FOLLOWED_SELLER_FIELDS = {
  sellerId: 'integer',
  sellerUsername: 'string',
  createdAt: 'string',
};

const BLOCKED_USER_FIELDS = {
  blockedId: 'integer',
  blockedUsername: 'string',
  createdAt: 'string',
};

const SAVED_SEARCH_FIELDS = {
  id: 'integer',
  // Nullable in the SPA type (null = no filter on that dimension):
  // pinned as the base type, required, per the #264 nullable rule.
  keyword: 'string',
  categoryId: 'integer',
  minPriceCents: 'integer',
  maxPriceCents: 'integer',
  sort: 'string',
  matchCount: 'integer',
  createdAt: 'string',
};

export const API_SHAPE_PINS = [
  // Auth session core.
  {
    method: 'POST',
    path: '/api/auth/register',
    request: { username: 'string', email: 'string', password: 'string' },
    // Backlog #271: one variant per success status (see header).
    responseByStatus: {
      200: AUTH_RESPONSE_FIELDS,
      201: REGISTER_PENDING_FIELDS,
    },
  },
  {
    method: 'POST',
    path: '/api/auth/login',
    request: { usernameOrEmail: 'string', password: 'string' },
    responseByStatus: {
      200: AUTH_RESPONSE_FIELDS,
      202: TOTP_CHALLENGE_FIELDS,
    },
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
  // Offers surface (third tranche, backlog #274). The action POSTs
  // carry no request pin: the SPA posts an empty body (accept /
  // decline / counter-accept / counter-decline / withdraw), exactly
  // like POST /api/orders/{id}/pay in the first tranche.
  {
    method: 'POST',
    path: '/api/items/{itemId}/offers',
    request: { priceCents: 'integer' },
    response: OFFER_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/offers/made',
    response: PAGE_OFFER_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/offers/received',
    response: PAGE_OFFER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/offers/{id}/accept',
    response: OFFER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/offers/{id}/decline',
    response: OFFER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/offers/{id}/counter',
    request: { priceCents: 'integer' },
    response: OFFER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/offers/{id}/counter-accept',
    response: OFFER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/offers/{id}/counter-decline',
    response: OFFER_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/offers/{id}/withdraw',
    response: OFFER_FIELDS,
  },
  // Seller payouts surface (third tranche, backlog #274).
  {
    method: 'GET',
    path: '/api/seller/payouts',
    response: PAGE_PAYOUT_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/seller/payouts/totals',
    response: SELLER_PAYOUT_TOTALS_FIELDS,
  },
  // Disputes surface (third tranche, backlog #274).
  {
    method: 'POST',
    path: '/api/orders/{id}/dispute',
    request: { reason: 'string' },
    response: DISPUTE_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/orders/{id}/dispute',
    response: DISPUTE_FIELDS,
  },
  {
    method: 'GET',
    path: '/api/disputes/{disputeId}/evidence',
    response: { type: 'array', items: { type: 'object', fields: DISPUTE_EVIDENCE_FIELDS } },
  },
  {
    method: 'POST',
    path: '/api/disputes/{disputeId}/evidence',
    // Multipart twin of the message-photo pin: springdoc puts only
    // the `file` part in the multipart schema, and the gate checks
    // the union with query parameters (none here), so the pin is
    // just the file part.
    request: { file: 'string' },
    response: DISPUTE_EVIDENCE_FIELDS,
  },
  {
    method: 'DELETE',
    path: '/api/disputes/{disputeId}/evidence/{evidenceId}',
    // ApiResponseVoid: the SPA reads no body from this call, so
    // there is nothing to pin — listed explicitly, never silently
    // checked (see header). A pin without a response key is what
    // makes the gate's summary print this line.
    responseNote: 'ApiResponseVoid — the SPA reads no body from this call',
  },
  {
    method: 'GET',
    path: '/api/admin/disputes',
    response: PAGE_DISPUTE_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/admin/disputes/{id}/resolve-refund',
    request: { resolutionNote: 'string' },
    response: DISPUTE_FIELDS,
  },
  {
    method: 'POST',
    path: '/api/admin/disputes/{id}/resolve-release',
    request: { resolutionNote: 'string' },
    response: DISPUTE_FIELDS,
  },
  // Social graph (fourth tranche, backlog #281). The void writes
  // carry a responseNote only (see header): the SPA reads no body.
  {
    method: 'GET',
    path: '/api/users/me/favorites',
    response: { type: 'array', items: { type: 'object', fields: ITEM_FIELDS } },
  },
  {
    method: 'PUT',
    path: '/api/users/me/favorites/{itemId}',
    responseNote: 'ApiResponseVoid — the SPA reads no body from this call',
  },
  {
    method: 'DELETE',
    path: '/api/users/me/favorites/{itemId}',
    responseNote: 'ApiResponseVoid — the SPA reads no body from this call',
  },
  {
    method: 'GET',
    path: '/api/users/me/follows',
    response: { type: 'array', items: { type: 'object', fields: FOLLOWED_SELLER_FIELDS } },
  },
  {
    method: 'POST',
    path: '/api/users/me/follows/{sellerId}',
    responseNote: 'ApiResponseVoid — the SPA reads no body from this call',
  },
  {
    method: 'DELETE',
    path: '/api/users/me/follows/{sellerId}',
    responseNote: 'ApiResponseVoid — the SPA reads no body from this call',
  },
  {
    method: 'GET',
    path: '/api/users/me/blocks',
    response: { type: 'array', items: { type: 'object', fields: BLOCKED_USER_FIELDS } },
  },
  {
    method: 'POST',
    path: '/api/users/{id}/block',
    responseNote: 'ApiResponseVoid — the SPA reads no body from this call',
  },
  {
    method: 'DELETE',
    path: '/api/users/{id}/block',
    responseNote: 'ApiResponseVoid — the SPA reads no body from this call',
  },
  {
    method: 'GET',
    path: '/api/users/me/saved-searches',
    response: { type: 'array', items: { type: 'object', fields: SAVED_SEARCH_FIELDS } },
  },
  {
    method: 'POST',
    path: '/api/users/me/saved-searches',
    request: {
      keyword: { type: 'string', required: false },
      categoryId: { type: 'integer', required: false },
      minPriceCents: { type: 'integer', required: false },
      maxPriceCents: { type: 'integer', required: false },
      sort: { type: 'string', required: false },
    },
    response: SAVED_SEARCH_FIELDS,
  },
  {
    method: 'DELETE',
    path: '/api/users/me/saved-searches/{id}',
    responseNote: 'ApiResponseVoid — the SPA reads no body from this call',
  },
  {
    method: 'GET',
    path: '/api/users/me/recently-viewed',
    response: { type: 'array', items: { type: 'object', fields: ITEM_FIELDS } },
  },
];
