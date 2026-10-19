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
 * {@code SellerOrderController}, {@code SellerSalesExportController},
 * {@code FavoriteController}, {@code RecentlyViewedController},
 * {@code SavedSearchController} and
 * {@code AccountDataExportController}.
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
      "/api/admin/dashboard",
      "/api/admin/disputes",
      "/api/admin/disputes/{id}/resolve-refund",
      "/api/admin/disputes/{id}/resolve-release",
      "/api/admin/reports",
      "/api/admin/reports/{id}/action",
      "/api/admin/reports/{id}/dismiss",
      "/api/admin/payments/settlement",
      "/api/admin/payments/webhooks",
      "/api/admin/payments/webhooks/{id}/replay",
      "/api/payments/webhooks/mock-psp",
      "/api/admin/users",
      "/api/admin/users/{id}/disable",
      "/api/admin/users/{id}/enable",
      "/api/auth/2fa/authenticate",
      "/api/auth/2fa/disable",
      "/api/auth/2fa/enable",
      "/api/auth/2fa/recovery-codes/count",
      "/api/auth/2fa/recovery-codes/regenerate",
      "/api/auth/2fa/setup",
      "/api/auth/email-change",
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
      "/api/categories",
      "/api/categories/{id}",
      "/api/items",
      "/api/items/{id}",
      "/api/items/{id}/photo",
      "/api/items/{id}/photos",
      "/api/items/{id}/photos/order",
      "/api/items/{id}/photos/{photoId}",
      "/api/items/{id}/renew",
      "/api/items/{id}/report",
      "/api/items/{id}/status",
      "/api/messages",
      "/api/messages/unread-count",
      "/api/notifications",
      "/api/notifications/unread-count",
      "/api/notifications/read-all",
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
      "/api/users/me/blocks");

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
    // Email change (#150) pins its wrong-password 401 and taken-address 409.
    assertThat(responseCodes(docs, "/api/auth/email-change", "post"))
        .contains("400", "401", "409");
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
    // Login history (#171) pins its success + anonymous codes.
    assertThat(responseCodes(docs, "/api/auth/login-history", "get"))
        .contains("200", "401");
    // Account data export (#172) pins its success + anonymous codes and
    // its own per-user rate-limit 429.
    assertThat(responseCodes(docs, "/api/users/me/export", "get"))
        .contains("200", "401", "429");
    // Export step-up confirm (#188) pins its success, its identical
    // proof-failure 401 and the shared TOTP lockout 423.
    assertThat(responseCodes(docs, "/api/users/me/export/confirm", "post"))
        .contains("200", "401", "423");
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
    // User blocking (#187): block pins unknown 404 + self-block 422,
    // unblock pins unknown 404, and the blocked list pins anonymous 401.
    assertThat(responseCodes(docs, "/api/users/{id}/block", "post"))
        .contains("200", "401", "404", "422");
    assertThat(responseCodes(docs, "/api/users/{id}/block", "delete"))
        .contains("200", "401", "404");
    assertThat(responseCodes(docs, "/api/users/me/blocks", "get"))
        .contains("200", "401");
    // Price offers (#168): creation pins its not-available / duplicate
    // 409 pair and its own-listing 422; the decisions pin the
    // no-existence-leak 404 and the not-PENDING 422, and accept the
    // buy-now race 409.
    assertThat(responseCodes(docs, "/api/items/{itemId}/offers", "post"))
        .contains("400", "401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/accept", "post"))
        .contains("401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/decline", "post"))
        .contains("401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/withdraw", "post"))
        .contains("401", "404", "409", "422");
    // Counter-offers (#193): counter pins its validation 400 and its
    // bounds/not-PENDING 422 pair; the buyer's counter decisions pin
    // the same no-existence-leak 404 and not-COUNTERED 422.
    assertThat(responseCodes(docs, "/api/offers/{id}/counter", "post"))
        .contains("400", "401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/counter-accept", "post"))
        .contains("401", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/offers/{id}/counter-decline", "post"))
        .contains("401", "404", "409", "422");
    // Order disputes (#169): opening pins its buyer-only 403, unknown
    // order 404 and the not-disputable / window-closed 422; the ADMIN
    // queue pins 403 for non-admins and the resolutions pin the
    // unknown-dispute 404 and the cross-resolution 422.
    assertThat(responseCodes(docs, "/api/orders/{id}/dispute", "post"))
        .contains("400", "401", "403", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/admin/disputes", "get"))
        .contains("401", "403");
    assertThat(responseCodes(docs, "/api/admin/disputes/{id}/resolve-refund", "post"))
        .contains("401", "403", "404", "409", "422");
    assertThat(responseCodes(docs, "/api/admin/disputes/{id}/resolve-release", "post"))
        .contains("401", "403", "404", "409", "422");
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
