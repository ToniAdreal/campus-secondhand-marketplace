package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymous-access sweep over every controller route (backlog #151).
 *
 * <p>Why this exists: backlog #127 shipped a real hole of this class — a
 * new authenticated POST path with no {@code SecurityConfig} matcher fell
 * through to the public {@code /api/auth/**} rule and 500'd on the null
 * principal; that item's own tests caught it, but no systematic guard did.
 * {@code SecurityChainTest} samples known paths and
 * {@code OpenApiContractTest} pins the path set, but neither enumerates
 * every route anonymously. This sweep takes the OpenAPI document (the
 * contract test's source of truth) as the route inventory and asserts,
 * for every operation in it:
 *
 * <ul>
 *   <li>the operation set equals the declared set below — a future route
 *       added without updating this classification fails by
 *       construction, before any probe runs;</li>
 *   <li>every operation classified authenticated answers exactly 401 to
 *       an anonymous call (never 200 — the route is not accidentally
 *       public; never 5xx — it does not fall through to a controller
 *       that dereferences a null principal);</li>
 *   <li>every operation classified public answers the exact status its
 *       declared probe produces (see {@link #PUBLIC_PROBE_STATUS}):
 *       the enumeration-safe public flows answer 200, validation
 *       failures 400, and the credential-bearing public flows (login,
 *       refresh, the 2FA exchange, password-reset confirm and the
 *       HMAC-signed webhook) answer their <em>semantic</em> 401 — the
 *       request reached the controller and was rejected there, which
 *       dedicated tests for each flow distinguish from a chain 401.
 *       A chain regression that swallowed a public route would change
 *       at least the 200/400 probes' answers.</li>
 * </ul>
 *
 * <p>Classification source of truth is {@code SecurityConfig}, verified
 * against the chain as built — not the backlog text: the existing
 * public set is exactly the {@code /api/auth/**} flows that carry their
 * own credential (password, mailed token, challenge, refresh cookie),
 * plus the HMAC-signed webhook. Notably {@code GET /api/categories}
 * and {@code GET /api/items} are <em>authenticated</em> in the current
 * chain (see {@code SecurityChainTest} and
 * {@code CategoryControllerTest.anonymousRequestGets401Envelope}), so
 * the sweep pins them as 401-for-anonymous; loosening either to public
 * is a deliberate {@code SecurityConfig} change that must update this
 * classification in the same commit.
 *
 * <p>Non-controller surfaces are probed too: the actuator health/info
 * probes are public (200) and {@code /actuator/metrics} plus
 * {@code /v3/api-docs} stay behind auth (401), mirroring
 * {@code ActuatorSecurityTest} / {@code OpenApiContractTest}.
 *
 * <p>Probes are side-effect free apart from the register probe, which
 * creates one throwaway account inside this test's transaction (rolled
 * back like every other {@code @SpringBootTest} user here, with the
 * email-verification gate off in the shared test profile, hence its
 * 200). Path variables are filled with the dummy id {@code 1}: the
 * security chain answers before routing resolves anything, and the
 * public set contains no path variables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnonymousAccessSweepTest {

  /** Every controller operation, as {@code "METHOD /path"}, from the OpenAPI doc. */
  private static final Set<String> EXPECTED_OPERATIONS = Set.of(
      "GET /api/admin/appeals",
      "POST /api/admin/appeals/{id}/uphold",
      "POST /api/admin/appeals/{id}/reject",
      "GET /api/admin/audit-log",
      "GET /api/admin/dashboard",
      "GET /api/admin/disputes",
      "POST /api/admin/disputes/{id}/resolve-refund",
      "POST /api/admin/disputes/{id}/resolve-release",
      "GET /api/admin/reports",
      "POST /api/admin/reports/{id}/dismiss",
      "POST /api/admin/reports/{id}/action",
      "GET /api/admin/payments/attempts",
      "GET /api/admin/payments/settlement",
      "GET /api/admin/payments/webhooks",
      "POST /api/admin/payments/webhooks/{id}/replay",
      "POST /api/payments/webhooks/mock-psp",
      "POST /api/security/csp-report",
      "GET /api/admin/users",
      "POST /api/admin/users/{id}/disable",
      "POST /api/admin/users/{id}/enable",
      "POST /api/admin/users/{id}/totp-reset",
      "POST /api/auth/2fa/authenticate",
      "POST /api/auth/2fa/disable",
      "POST /api/auth/2fa/enable",
      "GET /api/auth/2fa/recovery-codes/count",
      "POST /api/auth/2fa/recovery-codes/regenerate",
      "POST /api/auth/2fa/setup",
      "POST /api/auth/email-change",
      "POST /api/auth/email-change/confirm",
      "POST /api/auth/email-verification/confirm",
      "POST /api/auth/email-verification/resend",
      "POST /api/auth/login",
      "GET /api/auth/login-history",
      "POST /api/auth/logout",
      "GET /api/auth/me",
      "DELETE /api/auth/me",
      "POST /api/auth/password",
      "POST /api/auth/password-reset",
      "POST /api/auth/password-reset/confirm",
      "POST /api/auth/refresh",
      "POST /api/auth/register",
      "GET /api/auth/sessions",
      "POST /api/auth/sessions/revoke-others",
      "DELETE /api/auth/sessions/{id}",
      "POST /api/auth/step-up",
      "GET /api/auth/trusted-devices",
      "DELETE /api/auth/trusted-devices/{id}",
      "GET /api/categories",
      "POST /api/categories",
      "PATCH /api/categories/{id}",
      "DELETE /api/categories/{id}",
      "GET /api/items",
      "GET /api/items/{id}",
      "POST /api/items",
      "POST /api/items/{id}/photo",
      "POST /api/items/{id}/photos",
      "PUT /api/items/{id}/photos/order",
      "DELETE /api/items/{id}/photos/{photoId}",
      "GET /api/items/{id}/price-history",
      "GET /api/items/{id}/similar",
      "POST /api/items/{id}/publish",
      "POST /api/items/{id}/renew",
      "POST /api/items/{id}/report",
      "PATCH /api/items/{id}",
      "PATCH /api/items/{id}/status",
      "DELETE /api/items/{id}",
      "POST /api/messages",
      "GET /api/messages",
      "GET /api/messages/unread-count",
      "PUT /api/messages/{id}",
      "DELETE /api/messages/{id}",
      "GET /api/notifications",
      "GET /api/notifications/stream",
      "POST /api/notifications/stream-ticket",
      "GET /api/notifications/unread-count",
      "POST /api/notifications/read-all",
      "GET /api/notifications/preferences",
      "PUT /api/notifications/preferences/{type}",
      "DELETE /api/notifications/{id}",
      "POST /api/notifications/{id}/read",
      "POST /api/items/{itemId}/offers",
      "GET /api/offers/made",
      "GET /api/offers/received",
      "POST /api/offers/{id}/accept",
      "POST /api/offers/{id}/decline",
      "POST /api/offers/{id}/withdraw",
      "POST /api/offers/{id}/counter",
      "POST /api/offers/{id}/counter-accept",
      "POST /api/offers/{id}/counter-decline",
      "GET /api/orders",
      "GET /api/orders/{id}",
      "POST /api/orders",
      "POST /api/orders/{id}/cancel",
      "POST /api/orders/{id}/complete",
      "POST /api/orders/{id}/handover-code/regenerate",
      "POST /api/orders/{id}/dispute",
      "GET /api/orders/{id}/dispute",
      "POST /api/disputes/{disputeId}/evidence",
      "GET /api/disputes/{disputeId}/evidence",
      "DELETE /api/disputes/{disputeId}/evidence/{evidenceId}",
      "POST /api/orders/{id}/review",
      "POST /api/orders/{id}/pay",
      "GET /api/orders/{id}/receipt",
      "POST /api/orders/{id}/refund",
      "GET /api/seller/reports",
      "POST /api/seller/reports/{id}/appeal",
      "GET /api/seller/reports/{id}/appeal",
      "GET /api/seller/appeals",
      "GET /api/seller/orders",
      "GET /api/seller/orders/{id}",
      "GET /api/seller/orders/summary",
      "GET /api/seller/sales/export.csv",
      "GET /api/users/me/export",
      "POST /api/users/me/export/confirm",
      "GET /api/users/me/favorites",
      "GET /api/users/me/recently-viewed",
      "PUT /api/users/me/favorites/{itemId}",
      "DELETE /api/users/me/favorites/{itemId}",
      "POST /api/users/me/saved-searches",
      "GET /api/users/me/saved-searches",
      "DELETE /api/users/me/saved-searches/{id}",
      "GET /api/users/{id}/profile",
      "GET /api/users/{id}/listings",
      "GET /api/users/{id}/reviews",
      "POST /api/users/{id}/block",
      "DELETE /api/users/{id}/block",
      "GET /api/users/me/blocks",
      "POST /api/users/me/follows/{sellerId}",
      "DELETE /api/users/me/follows/{sellerId}",
      "GET /api/users/me/follows");

  /**
   * The public operations and the exact status the anonymous probe in
   * {@link #PROBE_BODIES} earns. The 401 entries are semantic (bad
   * credentials / token / signature presented to a public controller),
   * never the chain's entry-point 401 — each flow's own test suite
   * proves reachability with a valid credential.
   */
  private static final Map<String, Integer> PUBLIC_PROBE_STATUS = Map.ofEntries(
      Map.entry("POST /api/auth/register", 200),
      Map.entry("POST /api/auth/login", 401),
      Map.entry("POST /api/auth/refresh", 401),
      Map.entry("POST /api/auth/email-verification/confirm", 400),
      Map.entry("POST /api/auth/email-change/confirm", 401),
      Map.entry("POST /api/auth/email-verification/resend", 200),
      Map.entry("POST /api/auth/password-reset", 200),
      Map.entry("POST /api/auth/password-reset/confirm", 401),
      Map.entry("POST /api/auth/2fa/authenticate", 401),
      Map.entry("POST /api/payments/webhooks/mock-psp", 401),
      Map.entry("POST /api/security/csp-report", 204));

  /** Minimal well-formed bodies for the public probes that take one. */
  private static final Map<String, String> PROBE_BODIES = Map.of(
      "POST /api/auth/register",
      "{\"username\":\"anon-sweep-probe\",\"email\":\"anon-sweep-probe@example.com\","
          + "\"password\":\"s3cret-password\"}",
      "POST /api/auth/login",
      "{\"usernameOrEmail\":\"anon-sweep-nonexistent\",\"password\":\"wrong-password\"}",
      "POST /api/auth/email-verification/confirm",
      "{\"token\":\"anon-sweep-dummy-token\"}",
      "POST /api/auth/email-change/confirm",
      "{\"token\":\"anon-sweep-dummy-token\"}",
      "POST /api/auth/email-verification/resend",
      "{\"email\":\"anon-sweep@example.com\"}",
      "POST /api/auth/password-reset",
      "{\"usernameOrEmail\":\"anon-sweep-nonexistent\"}",
      "POST /api/auth/password-reset/confirm",
      "{\"token\":\"anon-sweep-dummy-token\",\"newPassword\":\"Anon-Sweep-N3w!Pass\"}",
      "POST /api/auth/2fa/authenticate",
      "{\"challenge\":\"anon-sweep-dummy\",\"code\":\"000000\"}",
      "POST /api/payments/webhooks/mock-psp",
      "{}",
      "POST /api/security/csp-report",
      "{\"csp-report\":{\"violated-directive\":\"img-src\",\"blocked-uri\":\"inline\"}}");

  private static final Set<String> HTTP_METHODS =
      Set.of("get", "post", "put", "patch", "delete");

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
    User user = users.save(new User("anon-sweep-reader", "anon-sweep-reader@example.com",
        passwords.encode("s3cret-password")));
    accessToken = jwt.createTokenPair(user).accessToken();
  }

  /** The live route inventory: operations springdoc renders from the controllers. */
  private Set<String> openApiOperations() throws Exception {
    MvcResult result = mockMvc.perform(
            get("/v3/api-docs").header("Authorization", "Bearer " + accessToken))
        .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    JsonNode paths = objectMapper.readTree(result.getResponse().getContentAsString())
        .path("paths");
    Set<String> operations = new TreeSet<>();
    paths.fieldNames().forEachRemaining(path ->
        paths.path(path).fieldNames().forEachRemaining(method -> {
          if (HTTP_METHODS.contains(method)) {
            operations.add(method.toUpperCase() + " " + path);
          }
        }));
    return operations;
  }

  private static String concretePath(String operation) {
    String path = operation.substring(operation.indexOf(' ') + 1);
    return path.replaceAll("\\{[^}]+\\}", "1");
  }

  private int anonymousStatus(String operation, String jsonBody) throws Exception {
    HttpMethod method = HttpMethod.valueOf(operation.substring(0, operation.indexOf(' ')));
    var request = MockMvcRequestBuilders.request(method, concretePath(operation));
    if (jsonBody != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
    }
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  @Test
  void openApiOperationSetMatchesTheDeclaredClassification() throws Exception {
    assertThat(openApiOperations()).isEqualTo(EXPECTED_OPERATIONS);
    // Every declared operation is classified exactly once: public (with a
    // probe expectation) or authenticated (the remainder, probed for 401).
    assertThat(PUBLIC_PROBE_STATUS.keySet()).isSubsetOf(EXPECTED_OPERATIONS);
  }

  @Test
  void everyAuthenticatedOperationAnswers401Anonymously() throws Exception {
    List<String> failures = new ArrayList<>();
    for (String operation : new TreeSet<>(EXPECTED_OPERATIONS)) {
      if (PUBLIC_PROBE_STATUS.containsKey(operation)) {
        continue;
      }
      String method = operation.substring(0, operation.indexOf(' '));
      String body = ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))
          ? "{}" : null;
      int status = anonymousStatus(operation, body);
      if (status != 401) {
        failures.add(operation + " -> " + status + " (expected 401)");
      }
    }
    assertThat(failures)
        .as("authenticated operations that did not answer the chain 401 anonymously")
        .isEmpty();
  }

  @Test
  void everyPublicOperationAnswersItsDeclaredProbeStatusAnonymously() throws Exception {
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, Integer> probe : PUBLIC_PROBE_STATUS.entrySet()) {
      int status = anonymousStatus(probe.getKey(), PROBE_BODIES.get(probe.getKey()));
      if (status != probe.getValue()) {
        failures.add(probe.getKey() + " -> " + status
            + " (expected " + probe.getValue() + ")");
      }
    }
    assertThat(failures)
        .as("public operations whose anonymous probe status drifted")
        .isEmpty();
  }

  @Test
  void nonControllerSurfacesKeepTheirAnonymousAnswers() throws Exception {
    // Public probes for the compose stack.
    assertThat(mockMvc.perform(get("/actuator/health")).andReturn()
        .getResponse().getStatus()).isEqualTo(200);
    assertThat(mockMvc.perform(get("/actuator/health/liveness")).andReturn()
        .getResponse().getStatus()).isEqualTo(200);
    assertThat(mockMvc.perform(get("/actuator/health/readiness")).andReturn()
        .getResponse().getStatus()).isEqualTo(200);
    assertThat(mockMvc.perform(get("/actuator/info")).andReturn()
        .getResponse().getStatus()).isEqualTo(200);
    // Everything else actuator / docs stays behind auth.
    assertThat(mockMvc.perform(get("/actuator/metrics")).andReturn()
        .getResponse().getStatus()).isEqualTo(401);
    assertThat(mockMvc.perform(get("/v3/api-docs")).andReturn()
        .getResponse().getStatus()).isEqualTo(401);
  }
}
