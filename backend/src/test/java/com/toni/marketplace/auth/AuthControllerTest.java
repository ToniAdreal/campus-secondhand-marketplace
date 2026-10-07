package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthController end-to-end via MockMvc: register → login → refresh with
 * rotating httpOnly cookies, including the replay-theft revocation rule and
 * the concurrent-refresh grace window.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class AuthControllerTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MutableClock clock;

  @Autowired
  private UserRepository users;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private JwtTokenService jwt;

  private static final String REGISTER =
      "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"s3cret-pass\"}";

  @Test
  void registerCreatesUserAndSetsHttpOnlyRefreshCookie() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.data.expiresInSeconds").value(900))
        .andExpect(jsonPath("$.data.user.username").value("alice"))
        .andExpect(jsonPath("$.data.user.roles[0]").value("USER"))
        .andExpect(header().exists("Set-Cookie"))
        .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie)
        .contains("refresh_token=")
        .contains("HttpOnly")
        .contains("SameSite=Lax");

    User saved = users.findByUsername("alice").orElseThrow();
    assertThat(saved.getPasswordHash()).startsWith("$2a$");
    assertThat(saved.getPasswordHash()).doesNotContain("s3cret-pass");
  }

  @Test
  void registerRejectsDuplicateUsername() throws Exception {
    register(REGISTER);
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice\",\"email\":\"other@example.com\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("username is already taken"));
  }

  @Test
  void registerRejectsDuplicateEmail() throws Exception {
    register(REGISTER);
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice2\",\"email\":\"alice@example.com\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));
  }

  @Test
  void registerRejectsShortPasswordAndBadEmail() throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"bob\",\"email\":\"not-an-email\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void loginSucceedsWithUsernameAndWithEmail() throws Exception {
    register(REGISTER);

    for (String id : new String[]{"alice", "alice@example.com"}) {
      mockMvc.perform(post("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"usernameOrEmail\":\"" + id + "\",\"password\":\"s3cret-pass\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.code").value(0))
          .andExpect(jsonPath("$.data.accessToken").exists())
          .andExpect(header().exists("Set-Cookie"));
    }
  }

  @Test
  void loginFailsIdenticallyForWrongPasswordAndUnknownUser() throws Exception {
    register(REGISTER);

    // Wrong password…
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"wrong-pass\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("invalid credentials"));

    // …and unknown user return the identical envelope (no enumeration oracle).
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"nobody\",\"password\":\"wrong-pass\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("invalid credentials"));
  }

  @Test
  void refreshRotatesTokensAndConcurrentReplayIsGracedOnce() throws Exception {
    Cookie first = register(REGISTER).getResponse().getCookie("refresh_token");
    assertThat(first).isNotNull();

    MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(first))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andExpect(header().exists("Set-Cookie"))
        .andReturn();
    Cookie second = refreshed.getResponse().getCookie("refresh_token");
    assertThat(second).isNotNull();
    assertThat(second.getValue()).isNotEqualTo(first.getValue());

    // Concurrent-tab race: the just-rotated token replays immediately (still
    // inside the grace window) → accepted once more, chain stays linear.
    MvcResult graced = mockMvc.perform(post("/api/auth/refresh").cookie(first))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    Cookie third = graced.getResponse().getCookie("refresh_token");
    assertThat(third).isNotNull();
    assertThat(third.getValue()).isNotEqualTo(second.getValue());
    assertThat(refreshTokens.count()).isEqualTo(3); // family intact

    // Past the grace window the same replay is theft: family revoked.
    clock.advance(Duration.ofSeconds(61));
    mockMvc.perform(post("/api/auth/refresh").cookie(first))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message")
            .value("refresh token reused or expired; session revoked"));

    // The revoked family kills the remaining tokens too.
    mockMvc.perform(post("/api/auth/refresh").cookie(third))
        .andExpect(status().isUnauthorized());
    assertThat(refreshTokens.count()).isZero();
  }

  @Test
  void refreshWithForgedTokenReturns401WithoutSideEffects() throws Exception {
    register(REGISTER);
    long before = refreshTokens.count();

    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new Cookie("refresh_token", "forged.token.value")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // Unknown token: no family to revoke, existing sessions untouched.
    assertThat(refreshTokens.count()).isEqualTo(before);
  }

  @Test
  void refreshWithoutCookieReturns401() throws Exception {
    mockMvc.perform(post("/api/auth/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void issuedAccessTokenFromLoginPassesSecurityFilter() throws Exception {
    register(REGISTER);
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String access = com.jayway.jsonpath.JsonPath.read(
        login.getResponse().getContentAsString(), "$.data.accessToken");

    // The claims carried by the login-issued token match the registered user.
    JwtTokenService.AccessClaims claims = jwt.parseAccessToken(access);
    assertThat(claims.username()).isEqualTo("alice");
    assertThat(claims.roles()).contains("USER");

    // And the token authorizes a protected resource through the filter chain.
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/items")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
  }

  @Test
  void logoutRevokesFamilyAndSubsequentRefreshFails() throws Exception {
    MvcResult registered = register(REGISTER);
    String access = com.jayway.jsonpath.JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.accessToken");
    Cookie first = registered.getResponse().getCookie("refresh_token");
    assertThat(first).isNotNull();

    // A second session for the same user (e.g. another device).
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isOk())
        .andReturn();
    Cookie second = login.getResponse().getCookie("refresh_token");
    assertThat(second).isNotNull();
    assertThat(refreshTokens.count()).isEqualTo(2);

    // Logout with the Bearer access token: server-side revocation + cleared cookie.
    MvcResult logout = mockMvc.perform(post("/api/auth/logout")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();

    String setCookie = logout.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie)
        .contains("refresh_token=")
        .contains("Max-Age=0");

    // The whole family is gone server-side: both sessions' refresh cookies are dead.
    assertThat(refreshTokens.count()).isZero();
    mockMvc.perform(post("/api/auth/refresh").cookie(first))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(post("/api/auth/refresh").cookie(second))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void logoutWithoutBearerTokenReturns401() throws Exception {
    // The route is behind authentication; an anonymous caller has no session to kill.
    mockMvc.perform(post("/api/auth/logout"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("unauthorized"));
  }

  private MvcResult register(String body) throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andReturn();
  }
}
