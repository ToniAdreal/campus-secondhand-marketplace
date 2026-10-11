package com.toni.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Error-envelope contract (backlog #142): every error status family the API
 * can answer must come back as the {@code {code, message, data}} envelope —
 * HTTP status == envelope {@code code}, a non-blank {@code message},
 * {@code data} null, a JSON content type, and never the servlet container's
 * HTML error page. Per-feature tests have always pinned only their own
 * status (e.g. {@code PaymentDeclineTest} pins 402, {@code LoginLockoutTest}
 * pins 423); nothing stopped a refactor from dropping one
 * {@link GlobalExceptionHandler} method and leaking an HTML page for that
 * family. This class is the single sweep that pins them all at once.
 *
 * <p>Two layers:
 * <ul>
 *   <li>A table-driven sweep over a test-only controller that throws the
 *       exception behind each family, so the advice mapping itself is pinned
 *       for every family in one place — including the generic-500 fallback
 *       (whose message must stay the generic {@code "internal error"}, never
 *       the exception's own text) and the 413 upload family, which MockMvc
 *       cannot trigger through a real multipart request because it bypasses
 *       the container's size check (see {@code ItemPhotoUploadTest}). A plain
 *       loop rather than {@code @ParameterizedTest}, mirroring
 *       {@code PasswordStrengthValidatorTest}: junit-jupiter-params is only a
 *       transitive detail of spring-boot-starter-test, not a declared
 *       dependency.</li>
 *   <li>One real-endpoint drive per principal family (validation 400, login
 *       401, anonymous 401, decline 402, {@code @PreAuthorize} 403, unknown
 *       item 404, duplicate registration 409, invalid transition 422,
 *       lockout 423), proving the exceptions above are what production code
 *       actually throws on those paths.</li>
 * </ul>
 *
 * The test-only controller exists only in this class's application context
 * (a {@link TestConfiguration} bean), so it never appears in the production
 * route map or the OpenAPI contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.auth.login-lockout.max-attempts=3",
    "app.auth.login-lockout.lock-duration=15m"
})
class ErrorEnvelopeContractTest {

  /** Test-only endpoint that throws the exception behind each family. */
  @RestController
  @RequestMapping("/api/test-only/error-envelope")
  static class ThrowingController {

    @GetMapping("/{family}")
    void throwFor(@PathVariable String family) {
      switch (family) {
        case "weak-password" -> throw new WeakPasswordException("password is too weak");
        case "invalid-credentials" -> throw new InvalidCredentialsException();
        case "payment-declined" -> throw new PaymentDeclinedException();
        case "access-denied" -> throw new AccessDeniedException("denied");
        case "not-found" -> throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "item not found");
        case "duplicate-user" -> throw new DuplicateUserException("username is already taken");
        case "upload-too-large" -> throw new MaxUploadSizeExceededException(1024L);
        case "invalid-transition" -> throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "invalid state transition");
        case "account-locked" -> throw new AccountLockedException(60L);
        default -> throw new IllegalStateException("boom-test-only-detail");
      }
    }
  }

  @TestConfiguration
  static class ThrowingControllerConfig {
    @Bean
    ThrowingController throwingController() {
      return new ThrowingController();
    }
  }

  private record Family(String segment, int expectedStatus) {}

  private static final List<Family> FAMILIES = List.of(
      new Family("weak-password", 400),
      new Family("invalid-credentials", 401),
      new Family("payment-declined", 402),
      new Family("access-denied", 403),
      new Family("not-found", 404),
      new Family("duplicate-user", 409),
      new Family("upload-too-large", 413),
      new Family("invalid-transition", 422),
      new Family("account-locked", 423),
      new Family("boom", 500));

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  private String userToken;
  private User seller;
  private String sellerToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    User user = users.save(new User("env-plain", "env-plain@example.com",
        passwords.encode(PASSWORD)));
    userToken = jwt.createTokenPair(user).accessToken();

    seller = users.save(new User("env-seller", "env-seller@example.com",
        passwords.encode(PASSWORD)));
    sellerToken = jwt.createTokenPair(seller).accessToken();

    User admin = new User("env-admin", "env-admin@example.com",
        passwords.encode(PASSWORD));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  /**
   * Performs {@code request}, expects {@code expectedStatus}, and asserts
   * the full envelope contract on the body: it parses as JSON, carries
   * exactly the envelope shape with {@code code} == the HTTP status, a
   * non-blank message and a null data, and contains no HTML.
   */
  private JsonNode assertEnvelope(MockHttpServletRequestBuilder request, int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request)
        .andExpect(status().is(expectedStatus))
        .andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(result.getResponse().getContentType())
        .as("content type for status %s", expectedStatus)
        .contains(MediaType.APPLICATION_JSON_VALUE);
    assertThat(body)
        .as("body for status %s must not be the container HTML error page", expectedStatus)
        .doesNotContain("<html");
    JsonNode json = objectMapper.readTree(body);
    assertThat(json.has("code") && json.has("message") && json.has("data"))
        .as("envelope shape for status %s: %s", expectedStatus, body)
        .isTrue();
    assertThat(json.get("code").asInt())
        .as("envelope code must equal the HTTP status")
        .isEqualTo(expectedStatus);
    assertThat(json.get("message").asText()).isNotBlank();
    assertThat(json.get("data").isNull())
        .as("error envelopes carry no data")
        .isTrue();
    return json;
  }

  private MockHttpServletRequestBuilder throwing(String family) {
    return get("/api/test-only/error-envelope/{family}", family)
        .header("Authorization", "Bearer " + userToken);
  }

  @Test
  void everyStatusFamilyAnswersWithTheEnvelope() throws Exception {
    for (Family family : FAMILIES) {
      JsonNode json = assertEnvelope(throwing(family.segment()), family.expectedStatus());
      if (family.expectedStatus() == 500) {
        // The fallback must not leak the exception's own detail.
        assertThat(json.get("message").asText()).isEqualTo("internal error");
      }
      if (family.expectedStatus() == 423) {
        // The lockout family also pins its Retry-After header.
        mockMvc.perform(throwing(family.segment()))
            .andExpect(status().is(423))
            .andExpect(header().string("Retry-After", "60"));
      }
    }
  }

  @Test
  void validationFailureOnCreateListingIs400Envelope() throws Exception {
    assertEnvelope(post("/api/items")
        .header("Authorization", "Bearer " + sellerToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"   \",\"description\":\"good battery\",\"priceCents\":25000}"),
        400);
  }

  @Test
  void wrongPasswordLoginIs401Envelope() throws Exception {
    assertEnvelope(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"usernameOrEmail\":\"env-seller\",\"password\":\"wrong-password\"}"),
        401);
  }

  @Test
  void anonymousCallToAuthenticatedEndpointIs401Envelope() throws Exception {
    assertEnvelope(get("/api/auth/me"), 401);
  }

  @Test
  void declinedPaymentIs402Envelope() throws Exception {
    User buyer = users.save(new User("env-buyer", "env-buyer@example.com",
        passwords.encode(PASSWORD)));
    String buyerToken = jwt.createTokenPair(buyer).accessToken();
    // Seeded directly rather than via POST /api/orders: order creation runs
    // in a REQUIRES_NEW transaction (backlog #34), which cannot see this
    // test's uncommitted fixtures. The pay endpoint itself is driven over
    // HTTP, which is the family under contract here.
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.RESERVED);
    item = items.save(item);
    long orderId = orders.save(new Order(item, buyer.getId(), item.getPriceCents())).getId();

    assertEnvelope(post("/api/orders/{id}/pay", orderId)
        .header("Authorization", "Bearer " + buyerToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"paymentToken\":\"tok_decline\"}"),
        402);
  }

  @Test
  void nonAdminDeleteIs403Envelope() throws Exception {
    Item item = items.save(new Item("Used ThinkPad", "good battery", 25000L, seller.getId()));
    assertEnvelope(delete("/api/items/{id}", item.getId())
        .header("Authorization", "Bearer " + sellerToken),
        403);
  }

  @Test
  void unknownItemIs404Envelope() throws Exception {
    assertEnvelope(patch("/api/items/{id}/status", 999999L)
        .header("Authorization", "Bearer " + adminToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"SOLD\"}"),
        404);
  }

  @Test
  void duplicateRegistrationIs409Envelope() throws Exception {
    String body = "{\"username\":\"env-dupe\",\"email\":\"env-dupe@example.com\""
        + ",\"password\":\"" + PASSWORD + "\"}";
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().is2xxSuccessful());
    assertEnvelope(post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body),
        409);
  }

  @Test
  void invalidListingTransitionIs422Envelope() throws Exception {
    Item item = new Item("Used ThinkPad", "good battery", 25000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    assertEnvelope(patch("/api/items/{id}/status", item.getId())
        .header("Authorization", "Bearer " + sellerToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"AVAILABLE\"}"),
        422);
  }

  @Test
  void lockedAccountLoginIs423EnvelopeWithRetryAfter() throws Exception {
    users.save(new User("env-locked", "env-locked@example.com",
        passwords.encode(PASSWORD)));
    String attempt = "{\"usernameOrEmail\":\"env-locked\",\"password\":\"wrong-password\"}";
    // max-attempts=3 for this class: two 401s, the third failure locks.
    assertEnvelope(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON).content(attempt), 401);
    assertEnvelope(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON).content(attempt), 401);
    MvcResult locked = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(attempt))
        .andExpect(status().is(423))
        .andExpect(header().exists("Retry-After"))
        .andReturn();
    JsonNode json = objectMapper.readTree(locked.getResponse().getContentAsString());
    assertThat(json.get("code").asInt()).isEqualTo(423);
    assertThat(json.get("message").asText()).isNotBlank();
    assertThat(json.get("data").isNull()).isTrue();
  }
}
