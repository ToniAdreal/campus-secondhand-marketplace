package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * HTTP surface of the per-account login lockout (backlog #60): the
 * {@code 423 Locked} envelope, the {@code Retry-After} header, and the
 * no-enumeration-oracle guarantee for unknown identifiers. The clock is a
 * {@link MutableClock} so lock expiry is deterministic. (The shared test
 * profile disables the per-IP limiter; this class exercises the
 * per-account mechanism only.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.auth.login-lockout.max-attempts=3",
    "app.auth.login-lockout.lock-duration=15m"
})
class LoginLockoutTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    users.save(new User("victim", "victim@example.com", passwords.encode(PASSWORD)));
  }

  private void failedLogin(String usernameOrEmail) throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + usernameOrEmail
                + "\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("invalid credentials"));
  }

  private void assertLocked(String usernameOrEmail, String password) throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + usernameOrEmail
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().is(423))
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value(423))
        .andExpect(jsonPath("$.message")
            .value("account temporarily locked — too many failed sign-in attempts"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void nthFailureReturns423EnvelopeWithRetryAfter() throws Exception {
    failedLogin("victim");
    failedLogin("victim");
    assertLocked("victim", "wrong-password");
  }

  @Test
  void correctPasswordIsRejectedWhileLocked() throws Exception {
    failedLogin("victim");
    failedLogin("victim");
    // The 3rd consecutive failure locks the account.
    assertLocked("victim", "wrong-password");
    // Even the right password gets 423 while the account is locked.
    assertLocked("victim", PASSWORD);
  }

  @Test
  void lockExpiresThenLoginSucceeds() throws Exception {
    failedLogin("victim");
    failedLogin("victim");
    assertLocked("victim", "wrong-password");
    clock.advance(Duration.ofMinutes(16));
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void retryAfterDecreasesAsTheLockAges() throws Exception {
    failedLogin("victim");
    failedLogin("victim");
    assertLocked("victim", "wrong-password");
    clock.advance(Duration.ofMinutes(5));
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"victim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().is(423))
        .andExpect(header().string("Retry-After", "600"));
  }

  @Test
  void unknownIdentifierNeverLocks() throws Exception {
    for (int i = 0; i < 8; i++) {
      mockMvc.perform(post("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"usernameOrEmail\":\"ghost\",\"password\":\"wrong-password\"}"))
          .andExpect(status().isUnauthorized())
          .andExpect(header().doesNotExist("Retry-After"))
          .andExpect(jsonPath("$.code").value(401))
          .andExpect(jsonPath("$.message").value("invalid credentials"));
    }
  }
}
