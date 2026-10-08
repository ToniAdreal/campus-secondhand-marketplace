package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #61 — absolute lifetime for refresh-token families. A family that
 * keeps rotating is still forced to re-authenticate {@code refresh-max-age}
 * after the original login (default 30 d; 10 s in this suite): a refresh
 * past the cap returns 401 and revokes the family — even when the presented
 * token is unexpired — instead of deleting every session on the account.
 *
 * <p>The application {@code Clock} bean is replaced with a
 * {@link MutableClock} so the cap boundary is deterministic. Logins go
 * through {@link AuthService} (the rate limiter is disabled in the shared
 * test profile); refreshes go through HTTP so the cookie flow and the
 * 401 envelope are exercised end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.refresh-max-age=10s")
@Transactional // each test rolls back the users it creates
class RefreshFamilyLifetimeTest {

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
  private AuthService auth;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    // No token pair is issued here (register would create one and skew the
    // row counts); the logins below mint the starting tokens.
    users.save(new User("family-tester", "family@example.com", passwords.encode("s3cret-pass")));
  }

  /** Service-level login, outside the HTTP budget. */
  private Cookie freshRefreshCookie() {
    String token = auth.login("family-tester", "s3cret-pass").pair().refreshToken();
    return new Cookie("refresh_token", token);
  }

  /** One refresh attempt; expects 200 and returns the rotated cookie. */
  private Cookie refreshOk(Cookie cookie) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andReturn();
    Cookie rotated = result.getResponse().getCookie("refresh_token");
    assertThat(rotated).isNotNull();
    return rotated;
  }

  /** One refresh attempt; expects the 401 family-cap envelope. */
  private void refreshExpectingCapExpiry(Cookie cookie) throws Exception {
    mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("refresh session lifetime exceeded; login again"));
  }

  /** One refresh attempt; expects 401 with any message (family already gone). */
  private void refreshExpecting401(Cookie cookie) throws Exception {
    mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void rotationWithinCapSucceeds() throws Exception {
    Cookie first = freshRefreshCookie();

    clock.advance(Duration.ofSeconds(5));

    Cookie second = refreshOk(first);
    assertThat(second.getValue()).isNotEqualTo(first.getValue());

    // The rotated row inherits the family's root (issued-at and family jti)
    // from the consumed token — the cap is measured from the original
    // login, not from the last rotation.
    List<RefreshToken> rows = refreshTokens.findAll();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).getFamilyIssuedAt()).isEqualTo(rows.get(0).getFamilyIssuedAt());
    assertThat(rows.get(1).getFamilyJti()).isEqualTo(rows.get(0).getFamilyJti());
    assertThat(rows.get(1).getFamilyJti()).isEqualTo(rows.get(0).getJti());
  }

  @Test
  void refreshPastCapRevokesFamilyAnd401s() throws Exception {
    Cookie first = freshRefreshCookie();
    assertThat(refreshTokens.count()).isEqualTo(1);

    // 11 s past a 10 s cap. The token itself is unexpired (7 d TTL) — the
    // cap still bites.
    clock.advance(Duration.ofSeconds(11));

    refreshExpectingCapExpiry(first);
    assertThat(refreshTokens.count()).isZero();

    // A further refresh is an unknown token (the family rows are gone).
    refreshExpecting401(first);
  }

  @Test
  void capExpiryRevokesOnlyThatFamily() throws Exception {
    Cookie familyA = freshRefreshCookie();
    Cookie familyB = freshRefreshCookie();
    assertThat(refreshTokens.count()).isEqualTo(2);

    clock.advance(Duration.ofSeconds(11));

    // Family A's cap expiry kills only family A's rows — the other
    // session's row survives, unlike the theft path (deleteByUserId).
    refreshExpectingCapExpiry(familyA);
    assertThat(refreshTokens.count()).isEqualTo(1);

    // Family B has its own cap expired too (same login instant), but it is
    // revoked only when it is presented — family-scoped, on demand.
    refreshExpectingCapExpiry(familyB);
    assertThat(refreshTokens.count()).isZero();
  }
}
