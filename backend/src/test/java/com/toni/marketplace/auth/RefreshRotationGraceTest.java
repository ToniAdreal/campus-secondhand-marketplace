package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #39 — refresh-rotation grace window. A just-rotated refresh token
 * replayed inside the grace window (default 60 s) is accepted once more by
 * rotating the live successor, so two tabs refreshing concurrently no longer
 * kill each other's session. Replays after the window, or of a token whose
 * successor is no longer live, still trigger theft detection and revoke the
 * whole family.
 *
 * <p>The application {@code Clock} bean is replaced with a {@link MutableClock}
 * so the window boundary is deterministic. The user row is created directly
 * (registration would already issue a token pair and skew the row counts);
 * logins go through {@link AuthService} (no HTTP budget concerns — the rate
 * limiter is disabled in the shared test profile); the refresh calls
 * themselves go through HTTP so the cookie flow is exercised end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class RefreshRotationGraceTest {

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
    // row counts); the login below mints the single starting token.
    users.save(new User("grace-tester", "grace@example.com", passwords.encode("s3cret-pass")));
  }

  /** Service-level login, outside the HTTP budget. */
  private Cookie freshRefreshCookie() {
    String token = auth.login("grace-tester", "s3cret-pass").pair().refreshToken();
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

  /** One refresh attempt; expects the 401 theft/reuse envelope. */
  private void refreshExpectingTheft(Cookie cookie) throws Exception {
    mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message")
            .value("refresh token reused or expired; session revoked"));
  }

  /** One refresh attempt; expects 401 with any message (family already gone). */
  private void refreshExpecting401(Cookie cookie) throws Exception {
    mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void doubleRefreshInsideWindowBothSucceed() throws Exception {
    Cookie first = freshRefreshCookie();
    Cookie second = refreshOk(first);
    assertThat(second.getValue()).isNotEqualTo(first.getValue());

    // The concurrent tab replays the just-replaced token inside the window:
    // accepted once more, rotating the live successor. Both tabs hold
    // working tokens and the family is untouched.
    Cookie third = refreshOk(first);
    assertThat(third.getValue()).isNotEqualTo(second.getValue());
    assertThat(refreshTokens.count()).isEqualTo(3);

    // The chain continues normally from the grace-issued token.
    Cookie fourth = refreshOk(third);
    assertThat(fourth.getValue()).isNotEqualTo(third.getValue());
    assertThat(refreshTokens.count()).isEqualTo(4);
  }

  @Test
  void replayAfterGraceWindowKillsFamily() throws Exception {
    Cookie first = freshRefreshCookie();
    Cookie second = refreshOk(first);
    assertThat(refreshTokens.count()).isEqualTo(2);

    clock.advance(Duration.ofSeconds(61));

    // Past the window the replay is indistinguishable from theft.
    refreshExpectingTheft(first);
    assertThat(refreshTokens.count()).isZero();

    // The family revocation kills the live token too.
    refreshExpecting401(second);
  }

  @Test
  void secondReplayOfSameStaleTokenKillsFamily() throws Exception {
    Cookie first = freshRefreshCookie();
    refreshOk(first);

    // First replay inside the window: graced once, rotating the successor.
    refreshOk(first);
    assertThat(refreshTokens.count()).isEqualTo(3);

    // The same stale token's successor is no longer live, so its single
    // grace grant is spent — replaying it again is theft.
    refreshExpectingTheft(first);
    assertThat(refreshTokens.count()).isZero();
  }

  @Test
  void graceBoundaryIsInclusiveAtSixtySeconds() throws Exception {
    Cookie first = freshRefreshCookie();
    refreshOk(first);

    clock.advance(Duration.ofSeconds(60));

    // Exactly at the window edge the grace still applies.
    Cookie third = refreshOk(first);
    assertThat(refreshTokens.count()).isEqualTo(3);

    clock.advance(Duration.ofSeconds(1));

    // One second past the edge the same token is theft.
    refreshExpectingTheft(first);
    assertThat(refreshTokens.count()).isZero();
    refreshExpecting401(third);
  }
}
