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
 * {@code AuditLogController}, {@code CategoryController},
 * {@code ItemController}, {@code MessageController}, {@code OrderController}
 * and {@code SellerOrderController}. The set is asserted by equality, so
 * adding a controller endpoint without extending this contract — or removing
 * one silently — fails the test until the contract is updated deliberately.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpenApiContractTest {

  /** Every path the eight controllers expose, as springdoc renders them. */
  private static final Set<String> EXPECTED_PATHS = Set.of(
      "/api/admin/audit-log",
      "/api/admin/users",
      "/api/admin/users/{id}/disable",
      "/api/admin/users/{id}/enable",
      "/api/auth/2fa/authenticate",
      "/api/auth/2fa/disable",
      "/api/auth/2fa/enable",
      "/api/auth/2fa/recovery-codes/count",
      "/api/auth/2fa/setup",
      "/api/auth/login",
      "/api/auth/logout",
      "/api/auth/me",
      "/api/auth/password",
      "/api/auth/password-reset",
      "/api/auth/password-reset/confirm",
      "/api/auth/refresh",
      "/api/auth/register",
      "/api/auth/sessions",
      "/api/auth/sessions/{id}",
      "/api/categories",
      "/api/categories/{id}",
      "/api/items",
      "/api/items/{id}",
      "/api/items/{id}/photo",
      "/api/items/{id}/photos",
      "/api/items/{id}/photos/{photoId}",
      "/api/items/{id}/status",
      "/api/messages",
      "/api/messages/unread-count",
      "/api/orders",
      "/api/orders/{id}",
      "/api/orders/{id}/cancel",
      "/api/orders/{id}/complete",
      "/api/orders/{id}/pay",
      "/api/orders/{id}/refund",
      "/api/seller/orders",
      "/api/seller/orders/{id}",
      "/api/seller/orders/summary");

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
    assertThat(responseCodes(docs, "/api/auth/2fa/authenticate", "post"))
        .contains("423", "429");
    // TOTP disable (#121) pins its not-enabled 422 and lockout 423.
    assertThat(responseCodes(docs, "/api/auth/2fa/disable", "post"))
        .contains("422", "423");
  }
}
