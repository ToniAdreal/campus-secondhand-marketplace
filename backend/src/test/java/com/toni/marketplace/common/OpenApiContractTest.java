package com.toni.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenAPI contract (backlog #95, extended in #125): {@code /v3/api-docs} and
 * the Swagger UI sit behind the same Bearer authentication as
 * {@code /actuator/metrics} (the route map is not public), and the generated
 * document lists exactly the controller paths plus the non-inferrable
 * response codes springdoc cannot guess (402 declined capture — pay only,
 * 409 version conflicts, 422 state guards, 202 login challenge, 423 lockout,
 * 429 rate limit).
 *
 * <p>The expected path set below is enumerated from the controllers, the
 * source of truth — {@code AuthController}, {@code AdminUserController},
 * {@code AdminPaymentController}, {@code AuditLogController},
 * {@code CategoryController},
 * {@code ItemController}, {@code MessageController},
 * {@code NotificationController}, {@code OrderController},
 * {@code ReportController}, {@code AdminReportController},
 * {@code SellerOrderController}, {@code SellerPayoutController},
 * {@code SellerSalesExportController},
 * {@code FavoriteController}, {@code RecentlyViewedController},
 * {@code SavedSearchController} and
 * {@code AccountDataExportController} — the passkey ceremony
 * endpoints ({@code /api/auth/2fa/passkeys/**}) live on
 * {@code AuthController} and are pinned here too.
 * The set is asserted by equality, so
 * adding a controller endpoint without extending this contract — or removing
 * one silently — fails the test until the contract is updated deliberately.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenApiContractTest {

  /** Every path the nine controllers expose, as springdoc renders them. */
  private static final Set<String> EXPECTED_PATHS = Set.of(
      "/api/admin/appeals",
      "/api/admin/appeals/{id}/reject",
      "/api/admin/appeals/{id}/uphold",
      "/api/admin/audit-log",
      "/api/admin/audit-log/export",
      "/api/admin/dashboard",
      "/api/admin/disputes",
      "/api/admin/disputes/{id}/resolve-refund",
      "/api/admin/disputes/{id}/resolve-release",
      "/api/admin/reports",
      "/api/admin/reports/{id}/action",
      "/api/admin/reports/{id}/dismiss",
      "/api/admin/payments/attempts",
      "/api/admin/payments/settlement",
      "/api/admin/payments/webhooks",
      "/api/admin/payments/webhooks/{id}/replay",
      "/api/payments/webhooks/mock-psp",
      "/api/security/csp-report",
      "/api/telemetry/errors",
      "/api/admin/users",
      "/api/admin/users/{id}/disable",
      "/api/admin/users/{id}/enable",
      "/api/admin/users/{id}/totp-reset",
      "/api/auth/2fa/authenticate",
      "/api/auth/2fa/disable",
      "/api/auth/2fa/enable",
      "/api/auth/2fa/passkeys",
      "/api/auth/2fa/passkeys/assertion/options",
      "/api/auth/2fa/passkeys/registration/options",
      "/api/auth/2fa/passkeys/registration/verify",
      "/api/auth/2fa/passkeys/{id}",
      "/api/auth/2fa/recovery-codes/count",
      "/api/auth/2fa/recovery-codes/regenerate",
      "/api/auth/2fa/setup",
      "/api/auth/email-change",
      "/api/auth/email-change/confirm",
      "/api/auth/email-verification/confirm",
      "/api/auth/email-verification/resend",
      "/api/auth/login",
      "/api/auth/login-history",
      "/api/auth/logout",
      "/api/auth/me",
      "/api/auth/password",
      "/api/auth/password-reset",
      "/api/auth/password-reset/confirm",
      "/api/auth/refresh",
      "/api/auth/register",
      "/api/auth/sessions",
      "/api/auth/sessions/revoke-others",
      "/api/auth/sessions/{id}",
      "/api/auth/step-up",
      "/api/auth/trusted-devices",
      "/api/auth/trusted-devices/{id}",
      "/api/auth/username",
      "/api/categories",
      "/api/categories/{id}",
      "/api/disputes/{disputeId}/evidence",
      "/api/disputes/{disputeId}/evidence/{evidenceId}",
      "/api/items",
      "/api/items/suggestions",
      "/api/items/{id}",
      "/api/items/{id}/photo",
      "/api/items/{id}/photos",
      "/api/items/{id}/photos/order",
      "/api/items/{id}/photos/{photoId}",
      "/api/items/{id}/price-history",
      "/api/items/{id}/similar",
      "/api/items/{id}/publish",
      "/api/items/{id}/renew",
      "/api/items/{id}/report",
      "/api/items/{id}/status",
      "/api/messages",
      "/api/messages/photo",
      "/api/messages/search",
      "/api/messages/unread-count",
      "/api/messages/{id}",
      "/api/notifications",
      "/api/notifications/stream",
      "/api/notifications/stream-ticket",
      "/api/notifications/unread-count",
      "/api/notifications/read-all",
      "/api/notifications/preferences",
      "/api/notifications/preferences/{type}",
      "/api/notifications/{id}",
      "/api/notifications/{id}/read",
      "/api/items/{itemId}/offers",
      "/api/offers/made",
      "/api/offers/received",
      "/api/offers/{id}/accept",
      "/api/offers/{id}/decline",
      "/api/offers/{id}/withdraw",
      "/api/offers/{id}/counter",
      "/api/offers/{id}/counter-accept",
      "/api/offers/{id}/counter-decline",
      "/api/orders",
      "/api/orders/{id}",
      "/api/orders/{id}/cancel",
      "/api/orders/{id}/complete",
      "/api/orders/{id}/ship",
      "/api/orders/{id}/confirm-receipt",
      "/api/orders/{id}/handover-code/regenerate",
      "/api/orders/{id}/dispute",
      "/api/orders/{id}/review",
      "/api/orders/{id}/pay",
      "/api/orders/{id}/receipt",
      "/api/orders/{id}/refund",
      "/api/seller/appeals",
      "/api/seller/orders",
      "/api/seller/orders/{id}",
      "/api/seller/orders/summary",
      "/api/seller/payouts",
      "/api/seller/payouts/totals",
      "/api/seller/reports",
      "/api/seller/reports/{id}/appeal",
      "/api/seller/sales/export.csv",
      "/api/users/me/export",
      "/api/users/me/export/confirm",
      "/api/users/me/favorites",
      "/api/users/me/favorites/{itemId}",
      "/api/users/me/recently-viewed",
      "/api/users/me/saved-searches",
      "/api/users/me/saved-searches/{id}",
      "/api/users/{id}/block",
      "/api/users/{id}/listings",
      "/api/users/{id}/profile",
      "/api/users/{id}/reviews",
      "/api/reviews/{id}",
      "/api/reviews/{id}/response",
      "/api/users/me/blocks",
      "/api/users/me/follows",
      "/api/users/me/follows/{sellerId}");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  private String accessToken;

  @BeforeEach
  void createUserAndToken() {
    User user = users.save(new User("openapi-reader", "openapi-reader@example.com",
        passwords.encode("s3cret-password")));
    accessToken = jwt.createTokenPair(user).accessToken();
  }

  private JsonNode apiDocs() throws Exception {
    MvcResult result = mockMvc.perform(
            get("/v3/api-docs").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private static Set<String> responseCodes(JsonNode docs, String path, String method) {
    Set<String> codes = new TreeSet<>();
    docs.path("paths").path(path).path(method).path("responses")
        .fieldNames().forEachRemaining(codes::add);
    return codes;
  }

  @Test
  void apiDocsIsBehindAuthForAnonymous() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void swaggerUiIsBehindAuthForAnonymous() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void apiDocsListsExactlyTheControllerPaths() throws Exception {
    mockMvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").value(
            org.hamcrest.Matchers.startsWith("3.")))
        .andExpect(jsonPath("$.info.title").value("Campus Second-hand Marketplace API"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(content().string(containsString("bearerAuth")));

    Set<String> actualPaths = new TreeSet<>();
    apiDocs().path("paths").fieldNames().forEachRemaining(actualPaths::add);
    assertThat(actualPaths).isEqualTo(EXPECTED_PATHS);
  }

  @Test
  void apiDocsCarriesAnnotatedNonInferrableCodes() throws Exception {
    JsonNode docs = apiDocs();

    // Payments: 402 is pay-only (a declined capture); the sibling lifecycle
    // transitions pin their 409/422 pair and must NOT claim a 402.
    assertThat(responseCodes(docs, "/api/orders/{id}/pay", "post"))
        .contains("402", "409");
    assertThat(responseCodes(docs, "/api/orders/{id}/complete", "post"))
        .contains("409", "422").doesNotContain("402");
    assertThat(responseCodes(docs, "/api/orders/{id}/refund", "post"))
        .contains("409", "422").doesNotContain("402");
    assertThat(responseCodes(docs, "/api/orders/{id}/cancel", "post"))
        .contains("409", "422").doesNotContain("402");
    // Order creation pins its idempotency-key reuse guard.
    assertThat(responseCodes(docs, "/api/orders", "post")).contains("409", "422");

    // Auth: login's challenge + lockout + throttle, and the throttle on
    // every other rate-limited surface (AuthRateLimitFilter buckets).
    assertThat(responseCodes(docs, "/api/auth/login", "post"))
        .contains("202", "423", "429");
    assertThat(responseCodes(docs, "/api/auth/register", "post")).contains("429");
    assertThat(responseCodes(docs, "/api/auth/refresh", "post")).contains("429");
    assertThat(responseCodes(docs, "/api/auth/password-reset", "post")).contains("429");
    assertThat(responseCodes(docs, "/api/auth/password-reset/confirm", "post"))
        .contains("400", "401", "429");
    // Email verification (#138): register pins its session-less 201, login
    // its unverified 403, and both verification endpoints their throttle.
    assertThat(responseCodes(docs, "/api/auth/register", "post")).contains("201");
    assertThat(responseCodes(docs, "/api/auth/login", "post")).contains("403");
    assertThat(responseCodes(docs, "/api/auth/email-verification/confirm", "post"))
        .contains("400", "429");
    assertThat(responseCodes(docs, "/api/auth/email-verification/resend", "post"))
        .contains("429");
    assertThat(responseCodes(docs, "/api/auth/2fa/authenticate", "post"))
        .contains("423", "429");
    // TOTP disable (#121) pins its not-enabled 422 and lockout 423.
    assertThat(responseCodes(docs, "/api/auth/2fa/disable", "post"))
        .contains("422", "423");
    // Recovery-code regeneration (#127) shares disable's proof bar, so
    // it pins the same not-enabled 422 and lockout 423.
    assertThat(responseCodes(docs, "/api/auth/2fa/recovery-codes/regenerate", "post"))
        .contains("422", "423");
    // Username change (#256) pins its validation 400 and taken-name 409;
    // #261 adds the freshness gate's dedicated step-up 403.
    assertThat(responseCodes(docs, "/api/auth/username", "post"))
        .contains("200", "400", "401", "403", "409");
    // Email change (#150) pins its wrong-password 401 and taken-address 409;
    // #272 adds the freshness gate's dedicated step-up 403.
    assertThat(responseCodes(docs, "/api/auth/email-change", "post"))
        .contains("400", "401", "403", "409");
    // Pending email change (#208): the confirm pins its identical-token
    // 401, its taken-in-between 409 and its shared token-bucket 429.
    assertThat(responseCodes(docs, "/api/auth/email-change/confirm", "post"))
        .contains("200", "401", "409", "429");
    // Account closure (#158) shares the /me path (no new path in the set
    // above) and pins its identical-proof 401 and lockout 423 on DELETE.
    assertThat(responseCodes(docs, "/api/auth/me", "delete"))
        .contains("200", "400", "401", "423");
    // Seller sales CSV export (#159) pins its inverted-window 400.
    assertThat(responseCodes(docs, "/api/seller/sales/export.csv", "get"))
        .contains("400", "401");
    // ADMIN operations dashboard (#196) pins its unknown-window 400
    // alongside the standard ADMIN 401/403 pair.
    assertThat(responseCodes(docs, "/api/admin/dashboard", "get"))
        .contains("400", "401", "403");
    // Listing reports (#160): creation pins its own-listing 422 and its
    // per-user rate-limit 429; the ADMIN transitions pin unknown-id 404
    // and the cross-terminal 422.
    assertThat(responseCodes(docs, "/api/items/{id}/report", "post"))
        .contains("404", "422", "429");
    assertThat(responseCodes(docs, "/api/admin/reports/{id}/dismiss", "post"))
        .contains("403", "404", "422");
    assertThat(responseCodes(docs, "/api/admin/reports/{id}/action", "post"))
        .contains("403", "404", "422");
    // Revoke-other-sessions (#163) pins its success + anonymous codes.
    assertThat(responseCodes(docs, "/api/auth/sessions/revoke-others", "post"))
        .contains("200", "401");
    // Step-up re-proof (#218) pins its success, its anonymous 401, its
    // lockout 423, and its own per-user rate-limit 429.
    assertThat(responseCodes(docs, "/api/auth/step-up", "post"))
        .contains("200", "401", "423", "429");
    // Login history (#171) pins its success + anonymous codes.
    assertThat(responseCodes(docs, "/api/auth/login-history", "get"))
        .contains("200", "401");
    // Trusted devices (#199) pin their success + anonymous codes, and
    // the revoke pins its no-oracle 404.
    assertThat(responseCodes(docs, "/api/auth/trusted-devices", "get"))
        .contains("200", "401");
    assertThat(responseCodes(docs, "/api/auth/trusted-devices/{id}", "delete"))
        .contains("200", "401", "404");
    // Account data export (#172) pins its success + anonymous codes and
    // its own per-user rate-limit 429.
    assertThat(responseCodes(docs, "/api/users/me/export", "get"))
        .contains("200", "401", "429");
    // Export step-up confirm (#188) pins its success, its identical
    // proof-failure 401 and the shared TOTP lockout 423.
    assertThat(responseCodes(docs, "/api/users/me/export/confirm", "post"))
        .contains("200", "401", "423");
    // Listing drafts (#198): publish pins its ownership matrix and its
    // not-a-draft 422.
    assertThat(responseCodes(docs, "/api/items/{id}/publish", "post"))
        .contains("401", "403", "404", "422");
    // Price history (#201): the read pins its success, its anonymous
    // 401 and its visibility 404 (unknown / draft / withdrawn /
    // expired) — deliberately no 403: a hidden listing never confirms
    // that it exists.
    assertThat(responseCodes(docs, "/api/items/{id}/price-history", "get"))
        .contains("200", "401", "404");
    // Similar listings (#202): the rail read pins its success, its
    // anonymous 401 and its source-visibility 404 (unknown / draft /
    // expired) — deliberately no 403, mirroring the detail read.
    assertThat(responseCodes(docs, "/api/items/{id}/similar", "get"))
        .contains("200", "401", "404");
    // Gallery reorder (#162): pins its not-a-permutation 400 and its
    // concurrent-edit 409 alongside the ownership matrix.
    assertThat(responseCodes(docs, "/api/items/{id}/photos/order", "put"))
        .contains("400", "401", "403", "404", "409");
    // Saved searches (#164): creation pins its browse-validation 400 and
    // its per-user cap 422; delete pins the no-existence-leak 404.
    assertThat(responseCodes(docs, "/api/users/me/saved-searches", "post"))
        .contains("200", "400", "401", "422");
    assertThat(responseCodes(docs, "/api/users/me/saved-searches/{id}", "delete"))
        .contains("200", "401", "404");
    // Public seller profile (#174): both reads pin their anonymous 401
    // and the unknown/disabled-seller 404.
    assertThat(responseCodes(docs, "/api/users/{id}/profile", "get"))
        .contains("200", "401", "404");
    assertThat(responseCodes(docs, "/api/users/{id}/listings", "get"))
        .contains("200", "401", "404");
    // Seller reviews (#175): creation pins the buyer-only 403, the
    // not-COMPLETED 422 and the duplicate 409; the list pins the same
    // anonymous 401 and unknown/disabled-seller 404 as the profile.
    assertThat(responseCodes(docs, "/api/orders/{id}/review", "post"))
        .contains("400", "401", "403", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/users/{id}/reviews", "get"))
        .contains("200", "401", "404");
    // Seller response (#246): PUT pins the seller-only 403 and the
    // blank/overlong 400 alongside the anonymous 401 and unknown 404;
    // the response never changes the rating, so no 422/409 exists here.
    assertThat(responseCodes(docs, "/api/reviews/{id}/response", "put"))
        .contains("200", "400", "401", "403", "404");
    // Buyer review edit (#254): PUT pins the buyer-only 403, the
    // rating/text 400, and the window-closed / already-answered 422
    // alongside the anonymous 401 and unknown 404.
    assertThat(responseCodes(docs, "/api/reviews/{id}", "put"))
        .contains("200", "400", "401", "403", "404", "422");
    // User blocking (#187): block pins unknown 404 + self-block 422,
    // unblock pins unknown 404, and the blocked list pins anonymous 401.
    assertThat(responseCodes(docs, "/api/users/{id}/block", "post"))
        .contains("200", "401", "404", "422");
    assertThat(responseCodes(docs, "/api/users/{id}/block", "delete"))
        .contains("200", "401", "404");
    assertThat(responseCodes(docs, "/api/users/me/blocks", "get"))
        .contains("200", "401");
    // Seller following (#217): follow pins unknown 404 + self-follow
    // 422 + blocked-seller 409, unfollow pins unknown 404, and the
    // following list pins anonymous 401.
    assertThat(responseCodes(docs, "/api/users/me/follows/{sellerId}", "post"))
        .contains("200", "401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/users/me/follows/{sellerId}", "delete"))
        .contains("200", "401", "404");
    assertThat(responseCodes(docs, "/api/users/me/follows", "get"))
        .contains("200", "401");
    // Price offers (#168): creation pins its not-available / duplicate
    // 409 pair and its own-listing 422; the decisions pin the
    // no-existence-leak 404 and the not-PENDING 422, and accept the
    // buy-now race 409. Backlog #274 adds the bare success 200 to
    // every offer action (third shape-pins tranche).
    assertThat(responseCodes(docs, "/api/items/{itemId}/offers", "post"))
        .contains("200", "400", "401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/accept", "post"))
        .contains("200", "401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/decline", "post"))
        .contains("200", "401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/withdraw", "post"))
        .contains("200", "401", "404", "409", "422");
    // Counter-offers (#193): counter pins its validation 400 and its
    // bounds/not-PENDING 422 pair; the buyer's counter decisions pin
    // the same no-existence-leak 404 and not-COUNTERED 422. Backlog
    // #274 adds the bare success 200 to all three (third tranche).
    assertThat(responseCodes(docs, "/api/offers/{id}/counter", "post"))
        .contains("200", "400", "401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/counter-accept", "post"))
        .contains("200", "401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/counter-decline", "post"))
        .contains("200", "401", "404", "409", "422");
    // Order disputes (#169): opening pins its buyer-only 403, unknown
    // order 404 and the not-disputable / window-closed 422; the ADMIN
    // queue pins 403 for non-admins and the resolutions pin the
    // unknown-dispute 404 and the cross-resolution 422. Backlog #274
    // adds the bare success 200 to the open, the queue list and the
    // by-order read (third shape-pins tranche).
    assertThat(responseCodes(docs, "/api/orders/{id}/dispute", "post"))
        .contains("200", "400", "401", "403", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/admin/disputes", "get"))
        .contains("200", "401", "403");
    assertThat(responseCodes(docs, "/api/admin/disputes/{id}/resolve-refund", "post"))
        .contains("400", "401", "403", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/admin/disputes/{id}/resolve-release", "post"))
        .contains("400", "401", "403", "404", "409", "422");
    // Dispute evidence (#200): the by-order read pins its
    // no-existence-leak 404; the evidence endpoints pin the bad-file
    // 400 and resolved-dispute 422 on upload, the not-uploader 403 on
    // removal, and the party-or-404 visibility rule throughout.
    // Backlog #274 adds the bare success 200 to the upload and the
    // list (the removal answers ApiResponseVoid, third tranche).
    assertThat(responseCodes(docs, "/api/orders/{id}/dispute", "get"))
        .contains("200", "401", "404");
    assertThat(responseCodes(docs, "/api/disputes/{disputeId}/evidence", "post"))
        .contains("200", "400", "401", "404", "422");
    assertThat(responseCodes(docs, "/api/disputes/{disputeId}/evidence", "get"))
        .contains("200", "401", "404");
    assertThat(responseCodes(docs, "/api/disputes/{disputeId}/evidence/{evidenceId}", "delete"))
        .contains("401", "403", "404");
    // CSP report intake (#204): pins its browser-report 204, its
    // malformed 400, its body-cap 413 and its per-IP throttle 429.
    assertThat(responseCodes(docs, "/api/security/csp-report", "post"))
        .contains("204", "400", "413", "429");
    // Frontend error telemetry intake (#223): pins the same code set —
    // beacon 204, malformed 400, body-cap 413, per-IP throttle 429.
    assertThat(responseCodes(docs, "/api/telemetry/errors", "post"))
        .contains("204", "400", "413", "429");
    // Webhook replay (#170): pins the ADMIN matrix plus the stale
    // event's 401 (the freshness re-check shares the unauthenticated
    // code, as on the live receiver) and the unknown-id 404.
    assertThat(responseCodes(docs, "/api/admin/payments/webhooks/{id}/replay", "post"))
        .contains("401", "403", "404");
    // Report appeals (#190): filing pins its seller-only 403, unknown
    // report 404 and the not-ACTIONED / already-resolved 422; the ADMIN
    // queue pins 403 for non-admins and the resolutions pin the
    // unknown-appeal 404, the version-conflict 409 and the
    // cross-resolution 422.
    assertThat(responseCodes(docs, "/api/seller/reports/{id}/appeal", "post"))
        .contains("400", "401", "403", "404", "422");
    assertThat(responseCodes(docs, "/api/admin/appeals", "get"))
        .contains("401", "403");
    assertThat(responseCodes(docs, "/api/admin/appeals/{id}/uphold", "post"))
        .contains("401", "403", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/admin/appeals/{id}/reject", "post"))
        .contains("401", "403", "404", "409", "422");
  }
}
