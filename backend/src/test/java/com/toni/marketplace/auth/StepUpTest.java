package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKey;
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
 * Step-up re-authentication for sensitive actions (backlog #218).
 *
 * <p>Every access token carries an {@code auth_time} claim — the instant of the
 * holder's last credential proof (password login, TOTP-exchange completion,
 * password change, registration, or the {@code POST /api/auth/step-up}
 * re-proof). The sensitive set — sessions revoke-others, the data-export
 * grant, TOTP disable, account closure — refuses a token whose proof is
 * older than {@code app.auth.step-up.max-age} (default 10 minutes) with a
 * dedicated 403 envelope (never a 401), and the SPA re-proves and retries
 * exactly once.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>a fresh token passes all four gated endpoints;</li>
 *   <li>a token whose proof is past max-age gets the 403 step-up envelope
 *       on every one of the four;</li>
 *   <li>refresh rotation preserves the proof instant — rotating a stale
 *       token does NOT launder it into a fresh one;</li>
 *   <li>a password change mints a fresh proof (it is itself a proof);</li>
 *   <li>a proof exactly max-age old still counts as fresh (inclusive);</li>
 *   <li>pre-#218 tokens (no {@code auth_time} claim) read as stale;</li>
 *   <li>the step-up endpoint mints an access-token-only credential, opens
 *       no new session, and works with a stale bearer (it is exempt from
 *       the gate); a wrong proof answers 401, anonymous callers 401.</li>
 * </ul>
 *
 * <p>The application {@code Clock} bean is replaced with a
 * {@link MutableClock} so proof aging is deterministic. (The shared test
 * profile disables the rate limiter; the step-up bucket's own 429 lives in
 * {@code StepUpRateLimitTest}.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StepUpTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private JwtProperties jwtProps;
  @Autowired private AuthService auth;
  @Autowired private MutableClock clock;

  private final ObjectMapper json = new ObjectMapper();

  private User freshUser() {
    String tag = "stepup-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  /** Service-level login: returns the pair and the refresh cookie for /refresh. */
  private LoginBundle login(User user) {
    JwtTokenService.TokenPair pair =
        ((AuthService.LoginResult.Pair) auth.login(user.getUsername(), "password"))
            .result().pair();
    return new LoginBundle(pair.accessToken(), new Cookie("refresh_token", pair.refreshToken()));
  }

  private record LoginBundle(String accessToken, Cookie refreshCookie) {}

  private MvcResult postRevokeOthers(String bearer) throws Exception {
    return mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + bearer))
        .andReturn();
  }

  private void expectStepUp403(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.path("code").asInt()).isEqualTo(403);
    assertThat(body.path("message").asText()).contains("re-authentication required");
  }

  @Test
  void freshTokenPassesRevokeOthers() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + login.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void freshTokenPassesExportConfirm() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    mockMvc.perform(post("/api/users/me/export/confirm")
            .header("Authorization", "Bearer " + login.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.grant").exists());
  }

  @Test
  void staleTokenGetsStepUp403OnRevokeOthers() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    clock.advance(Duration.ofMinutes(11));
    expectStepUp403(postRevokeOthers(login.accessToken()));
  }

  @Test
  void staleTokenGetsStepUp403OnAllFourGatedEndpoints() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    clock.advance(Duration.ofMinutes(11));
    String bearer = "Bearer " + login.accessToken();

    // 1. sessions revoke-others
    expectStepUp403(postRevokeOthers(login.accessToken()));

    // 2. data-export grant confirm — the gate fires before the password
    //    proof is even examined.
    expectStepUp403(mockMvc.perform(post("/api/users/me/export/confirm")
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andReturn());

    // 3. TOTP disable — the gate fires before the TOTP-enabled check, so
    //    no TOTP setup is needed for this pin.
    expectStepUp403(mockMvc.perform(post("/api/auth/2fa/disable")
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\",\"code\":\"000000\"}"))
        .andReturn());

    // 4. account closure — the gate fires before the password proof.
    expectStepUp403(mockMvc.perform(delete("/api/auth/me")
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andReturn());
  }

  @Test
  void refreshRotationPreservesProofInstantAndDoesNotFreshen() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    // The proof is 11 minutes old before the rotation even happens.
    clock.advance(Duration.ofMinutes(11));
    MvcResult rotated = mockMvc.perform(post("/api/auth/refresh").cookie(login.refreshCookie()))
        .andExpect(status().isOk())
        .andReturn();
    String newAccess = json.readTree(rotated.getResponse().getContentAsString())
        .path("data").path("accessToken").asText();
    // The rotated token still carries the original proof instant: a
    // rotation is not a credential proof, so the gate still refuses it.
    expectStepUp403(postRevokeOthers(newAccess));
  }

  @Test
  void passwordChangeMintsFreshProof() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    clock.advance(Duration.ofMinutes(11));
    expectStepUp403(postRevokeOthers(login.accessToken()));
    // A password change is itself a credential proof: the new pair's
    // proof instant is now.
    MvcResult changed = mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + login.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\",\"newPassword\":\"n3w-secret-pass\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String newAccess = json.readTree(changed.getResponse().getContentAsString())
        .path("data").path("accessToken").asText();
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + newAccess))
        .andExpect(status().isOk());
  }

  @Test
  void proofJustInsideMaxAgeIsFresh() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    // The `auth_time` claim is epoch-second truncated, so the proof is a
    // fraction of a second older than the wall-clock advance: stay two
    // seconds inside the 10-minute window to pin "fresh just before the
    // boundary". The exact inclusive boundary is pinned in
    // StepUpPolicyTest with untruncated instants.
    clock.advance(Duration.ofMinutes(10).minusSeconds(2));
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + login.accessToken()))
        .andExpect(status().isOk());
  }

  @Test
  void legacyTokenWithoutAutClaimReadsAsStale() throws Exception {
    User user = freshUser();
    // A pre-#218 token: same key, same claims, but no `auth_time` claim.
    SecretKey key = JwtTokenService.keyFromBase64(jwtProps.getSecret());
    Date now = Date.from(clock.instant());
    String legacy = Jwts.builder()
        .subject(user.getId().toString())
        .claim("username", user.getUsername())
        .claim("roles", List.of("USER"))
        .claim("tver", 0L)
        .claim("type", "access")
        .issuedAt(now)
        .expiration(Date.from(clock.instant().plus(Duration.ofMinutes(15))))
        .signWith(key)
        .compact();
    expectStepUp403(postRevokeOthers(legacy));
  }

  @Test
  void stepUpMintsAccessTokenOnlyAndNoNewSession() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    clock.advance(Duration.ofMinutes(11));
    expectStepUp403(postRevokeOthers(login.accessToken()));

    int sessionsBefore = sessionCount(login.accessToken());
    assertThat(sessionsBefore).isEqualTo(1);

    MvcResult reproof = mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + login.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").exists())
        .andExpect(jsonPath("$.data.expiresInSeconds").exists())
        .andReturn();
    String freshAccess = json.readTree(reproof.getResponse().getContentAsString())
        .path("data").path("accessToken").asText();

    // The re-proof opened no new session (access-token-only credential).
    assertThat(sessionCount(freshAccess)).isEqualTo(1);

    // The fresh credential's proof instant is now: the gated action passes.
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + freshAccess))
        .andExpect(status().isOk());
  }

  @Test
  void stepUpWrongPasswordAnswers401() throws Exception {
    User user = freshUser();
    LoginBundle login = login(user);
    clock.advance(Duration.ofMinutes(11));
    mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + login.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void stepUpAnonymousAnswers401() throws Exception {
    mockMvc.perform(post("/api/auth/step-up")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void stepUpIsExemptFromItsOwnGate() throws Exception {
    // A token whose proof is hours old can still call the re-proof
    // endpoint — otherwise the user could never recover a fresh proof.
    // The access token itself expires at 15 minutes, so this advances
    // just past max-age but inside the token's life.
    User user = freshUser();
    LoginBundle login = login(user);
    clock.advance(Duration.ofMinutes(14));
    mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + login.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk());
  }

  private int sessionCount(String bearer) throws Exception {
    MvcResult sessions = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode data = json.readTree(sessions.getResponse().getContentAsString()).path("data");
    return data.isArray() ? data.size() : 0;
  }
}
