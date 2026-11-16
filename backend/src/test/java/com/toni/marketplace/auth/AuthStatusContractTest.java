package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 403-vs-401 contract matrix for the auth endpoints (backlog #47).
 *
 * <p>The two statuses answer different questions and must never be confused:
 * 401 means "no usable credential was presented" (the filter rejects the call
 * before any controller or method-security check runs, with the JSON {@code
 * {code: 401, message: "unauthorized"}} envelope); 403 means "the credential
 * is valid but the caller may not do this" (a {@code @PreAuthorize} denial,
 * answered by the same envelope shape with {@code code: 403, message:
 * "forbidden"}). A regression that swapped them would send the SPA to the
 * login page for a permission problem, or show a permission error for an
 * expired session.
 *
 * <p>Pinned here, in one table instead of scattered across the auth suites:
 * <ul>
 *   <li>anonymous callers get the 401 envelope on every authenticated auth
 *       endpoint (all {@code .authenticated()} matchers in
 *       {@code SecurityConfig}, plus the ADMIN-only {@code /api/admin/users}
 *       surface, whose filter-level 401 precedes its {@code @PreAuthorize}
 *       check);</li>
 *   <li>an authenticated non-admin gets the 403 envelope on all four admin
 *       endpoints, while an admin passes the gate (200 on the list, 404 for
 *       an unknown user id on the three writes);</li>
 *   <li>an authenticated user reaches their own read endpoints (200);</li>
 *   <li>the public auth endpoints stay public — an anonymous caller with an
 *       empty body gets a 400 validation envelope, never a 401, from
 *       register, password-reset, the 2FA exchange and the passkey assertion
 *       options.</li>
 * </ul>
 *
 * <p>Deliberately NOT re-pinned here (covered by their own suites): the
 * step-up 403 flavour — a valid token whose credential proof is stale —
 * lives in {@code StepUpTest}; the wrong-credentials 401 ({@code message:
 * "invalid credentials"}) lives in {@code AuthControllerTest}; the
 * cross-user session delete answering 404 rather than 403 lives in
 * {@code SessionManagementTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class AuthStatusContractTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private String userToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    User admin = new User("matrix-admin", "matrix-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    User alice = users.save(new User("matrix-alice", "matrix-alice@example.com",
        passwords.encode("alice-password")));

    adminToken = jwt.createTokenPair(admin).accessToken();
    userToken = jwt.createTokenPair(alice).accessToken();
  }

  /**
   * Matrix A — anonymous callers get the JSON 401 envelope (code 401,
   * message "unauthorized", no data) on every authenticated auth endpoint.
   * The request body is irrelevant here: the filter rejects the call before
   * any controller or validation runs, so an empty JSON body is sent for the
   * POST/PATCH rows.
   */
  @ParameterizedTest(name = "anonymous {0} {1} -> 401")
  @MethodSource("authenticatedEndpoints")
  void anonymousCallerGets401Envelope(String method, String path) throws Exception {
    mockMvc.perform(request(method, path))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("unauthorized"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  static Stream<Arguments> authenticatedEndpoints() {
    return Stream.of(
        // AuthController: authenticated POSTs (SecurityConfig matchers).
        Arguments.of("POST", "/api/auth/logout"),
        Arguments.of("POST", "/api/auth/password"),
        Arguments.of("POST", "/api/auth/username"),
        Arguments.of("POST", "/api/auth/email-change"),
        Arguments.of("POST", "/api/auth/sessions/revoke-others"),
        Arguments.of("POST", "/api/auth/step-up"),
        Arguments.of("POST", "/api/auth/2fa/setup"),
        Arguments.of("POST", "/api/auth/2fa/enable"),
        Arguments.of("POST", "/api/auth/2fa/disable"),
        Arguments.of("POST", "/api/auth/2fa/recovery-codes/regenerate"),
        Arguments.of("POST", "/api/auth/2fa/passkeys/registration/options"),
        Arguments.of("POST", "/api/auth/2fa/passkeys/registration/verify"),
        // AuthController: authenticated GETs.
        Arguments.of("GET", "/api/auth/me"),
        Arguments.of("GET", "/api/auth/sessions"),
        Arguments.of("GET", "/api/auth/login-history"),
        Arguments.of("GET", "/api/auth/trusted-devices"),
        Arguments.of("GET", "/api/auth/2fa/recovery-codes/count"),
        Arguments.of("GET", "/api/auth/2fa/passkeys"),
        // AuthController: authenticated DELETEs + PATCH.
        Arguments.of("DELETE", "/api/auth/me"),
        Arguments.of("DELETE", "/api/auth/sessions/999999"),
        Arguments.of("DELETE", "/api/auth/trusted-devices/999999"),
        Arguments.of("DELETE", "/api/auth/2fa/passkeys/999999"),
        Arguments.of("PATCH", "/api/auth/2fa/passkeys/999999"),
        // AdminUserController: the filter-level 401 precedes @PreAuthorize.
        Arguments.of("GET", "/api/admin/users"),
        Arguments.of("POST", "/api/admin/users/999999/disable"),
        Arguments.of("POST", "/api/admin/users/999999/enable"),
        Arguments.of("POST", "/api/admin/users/999999/totp-reset"));
  }

  /**
   * Matrix B — an authenticated non-admin gets the JSON 403 envelope (code
   * 403, message "forbidden") on all four admin endpoints, while an admin
   * passes the gate: 200 on the list, 404 for an unknown user id on the
   * three writes.
   */
  @ParameterizedTest(name = "non-admin {0} {1} -> 403, admin passes")
  @MethodSource("adminEndpoints")
  void adminEndpointsForbidNonAdminButPassAdmin(String method, String path,
      int adminStatus) throws Exception {
    mockMvc.perform(request(method, path).header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("forbidden"))
        .andExpect(jsonPath("$.data").doesNotExist());

    mockMvc.perform(request(method, path).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(adminStatus));
  }

  static Stream<Arguments> adminEndpoints() {
    return Stream.of(
        Arguments.of("GET", "/api/admin/users", 200),
        Arguments.of("POST", "/api/admin/users/999999/disable", 404),
        Arguments.of("POST", "/api/admin/users/999999/enable", 404),
        Arguments.of("POST", "/api/admin/users/999999/totp-reset", 404));
  }

  /**
   * Matrix C — an authenticated user reaches their own read endpoints: no
   * 401, no 403, just the caller's own data.
   */
  @ParameterizedTest(name = "authenticated user GET {0} -> 200")
  @MethodSource("ownReadEndpoints")
  void authenticatedUserReachesOwnReads(String path) throws Exception {
    mockMvc.perform(get(path).header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("ok"));
  }

  static Stream<Arguments> ownReadEndpoints() {
    return Stream.of(
        Arguments.of("/api/auth/me"),
        Arguments.of("/api/auth/sessions"),
        Arguments.of("/api/auth/login-history"),
        Arguments.of("/api/auth/trusted-devices"),
        Arguments.of("/api/auth/2fa/recovery-codes/count"),
        Arguments.of("/api/auth/2fa/passkeys"));
  }

  /**
   * Matrix D — the public auth endpoints stay public: an anonymous caller
   * with an empty body reaches validation (400), never the 401 filter.
   */
  @ParameterizedTest(name = "anonymous POST {0} with empty body -> 400, not 401")
  @MethodSource("publicAuthEndpoints")
  void publicAuthEndpointsAnswer400Not401ForAnonymousEmptyBody(String path)
      throws Exception {
    mockMvc.perform(post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  static Stream<Arguments> publicAuthEndpoints() {
    return Stream.of(
        Arguments.of("/api/auth/register"),
        Arguments.of("/api/auth/password-reset"),
        Arguments.of("/api/auth/2fa/authenticate"),
        Arguments.of("/api/auth/2fa/passkeys/assertion/options"));
  }

  @Test
  void malformedBearerTokenGets401EnvelopeOnAuthMe() throws Exception {
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer <redacted>"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("unauthorized"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void authenticatedLogoutReturns200() throws Exception {
    // The logout row of matrix A pins the anonymous 401; the other side of
    // the same endpoint is a plain 200 for a valid token (no refresh cookie
    // is required — logout only revokes the caller's families).
    mockMvc.perform(post("/api/auth/logout")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  private static MockHttpServletRequestBuilder request(String method, String path) {
    return switch (method) {
      case "GET" -> get(path);
      case "DELETE" -> delete(path);
      case "PATCH" -> patch(path)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}");
      case "POST" -> post(path)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}");
      default -> throw new IllegalArgumentException("unsupported method: " + method);
    };
  }
}
