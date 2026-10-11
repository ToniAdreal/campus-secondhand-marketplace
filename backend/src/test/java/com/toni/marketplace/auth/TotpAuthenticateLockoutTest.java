package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Per-account second-factor lockout (backlog #101): consecutive failed
 * {@code POST /api/auth/2fa/authenticate} exchanges lock the exchange
 * for the account (423 + {@code Retry-After}), a success resets the
 * counter, and the lock never blocks password login.
 *
 * <p>Deliberately NOT {@code @Transactional}: the failure counter must
 * persist across separate requests in production, where each request
 * runs in its own service transaction and the 401/423 exception would
 * roll the increment back without the {@code noRollbackFor} declaration
 * on {@code AuthService.authenticateTotp}. Wrapping these tests in a
 * shared test transaction would mask exactly that failure mode, so each
 * test creates its own user and deletes it afterwards (refresh-token
 * and recovery-code rows cascade).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.auth.totp-lockout.max-attempts=3",
    "app.auth.totp-lockout.lock-duration=15m"
})
class TotpAuthenticateLockoutTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpService totp;

  @Autowired
  private MutableClock clock;

  private String username;
  private String secret;
  private List<String> recoveryCodes;

  @AfterEach
  void cleanUp() {
    if (username != null) {
      users.findByUsernameIgnoreCase(username).ifPresent(u -> users.deleteById(u.getId()));
    }
  }

  /** Registers a fresh user and enables 2FA; fills {@link #secret} / {@link #recoveryCodes}. */
  private void enroll() throws Exception {
    username = "totplock" + SEQ.incrementAndGet();
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String access = JsonPath.read(registered.getResponse().getContentAsString(),
        "$.data.accessToken");
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    MvcResult enabled = mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + currentCode() + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    recoveryCodes = JsonPath.read(enabled.getResponse().getContentAsString(),
        "$.data.recoveryCodes");
  }

  /** Password login — asserts the 202 challenge shape and returns the challenge token. */
  private String loginChallenge() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username
                + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.challenge");
  }

  private void authenticate(String challenge, String code, int expected) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + code + "\"}"))
        .andExpect(status().is(expected));
  }

  private void authenticateWrong(int expected) throws Exception {
    authenticate(loginChallenge(), wrongCode(), expected);
  }

  private String currentCode() {
    return totp.codeAt(secret, clock.instant());
  }

  /** A 6-digit code that verifies in no accepted time step (and is no recovery code). */
  private String wrongCode() {
    Set<String> valid = Set.of(
        totp.codeAt(secret, clock.instant().minusSeconds(30)),
        totp.codeAt(secret, clock.instant()),
        totp.codeAt(secret, clock.instant().plusSeconds(30)));
    for (String candidate : List.of("000000", "111111", "222222", "333333", "444444")) {
      if (!valid.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no wrong code candidate");
  }

  @Test
  void nthFailureLocksAndCorrectCodeUnderFreshChallengeStillGets423() throws Exception {
    enroll();
    authenticateWrong(401);
    authenticateWrong(401);
    // The 3rd consecutive failure locks the exchange: 423 + Retry-After.
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + loginChallenge()
                + "\",\"code\":\"" + wrongCode() + "\"}"))
        .andExpect(status().is(423))
        .andExpect(header().string("Retry-After", "900"))
        .andExpect(jsonPath("$.code").value(423))
        .andExpect(jsonPath("$.data").doesNotExist());
    // Even the correct code under a FRESH challenge is refused while locked.
    authenticate(loginChallenge(), currentCode(), 423);
  }

  @Test
  void passwordLoginStillIssuesAChallengeWhileTotpLocked() throws Exception {
    enroll();
    authenticateWrong(401);
    authenticateWrong(401);
    authenticateWrong(423);
    // The design decision of #101: the TOTP lock is NOT the password
    // lockout — login still verifies the password and answers 202 with
    // a challenge (loginChallenge() asserts exactly that shape).
    loginChallenge();
  }

  @Test
  void successfulExchangeResetsTheFailureCounter() throws Exception {
    enroll();
    authenticateWrong(401);
    authenticateWrong(401);
    authenticate(loginChallenge(), currentCode(), 200);
    // Counter was reset by the success: two more failures are plain 401s…
    authenticateWrong(401);
    authenticateWrong(401);
    // …and only the third consecutive one locks again.
    authenticateWrong(423);
  }

  @Test
  void recoveryCodeExchangeCountsAsSuccessAndResets() throws Exception {
    enroll();
    authenticateWrong(401);
    authenticateWrong(401);
    authenticate(loginChallenge(), recoveryCodes.get(0), 200);
    authenticateWrong(401);
    authenticateWrong(401);
    authenticateWrong(423);
  }

  @Test
  void lockExpiresThenCorrectCodeSucceeds() throws Exception {
    enroll();
    authenticateWrong(401);
    authenticateWrong(401);
    authenticateWrong(423);
    clock.advance(Duration.ofMinutes(16));
    // Fresh challenge (the old one expired with its 5-minute TTL) and a
    // code for the advanced clock: the expired lock cleared the counter.
    authenticate(loginChallenge(), currentCode(), 200);
  }
}
