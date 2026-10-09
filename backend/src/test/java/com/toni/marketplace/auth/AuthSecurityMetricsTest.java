package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #79 — auth-domain Micrometer counters. Each test measures the
 * delta on the test {@link MeterRegistry} across its own flow (the
 * registry is shared by the whole application context, so absolute
 * values would couple tests to execution order). The lockout threshold
 * is pinned to 3 via properties so the trigger test stays short; the
 * {@link MutableClock} drives the refresh grace window for the theft
 * test, mirroring {@link RefreshRotationGraceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.auth.login-lockout.max-attempts=3",
    "app.auth.login-lockout.lock-duration=15m"
})
class AuthSecurityMetricsTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final String PASSWORD = "s3cret-pass";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MeterRegistry registry;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private MutableClock clock;

  /** Current count for a counter, 0 when it has never been registered/fired. */
  private double count(String name) {
    Counter counter = registry.find(name).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private void saveUser(String username) {
    users.save(new User(username, username + "@example.com", passwords.encode(PASSWORD)));
  }

  private MvcResult login(String usernameOrEmail, String password, int expectedStatus)
      throws Exception {
    return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + usernameOrEmail
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  @Test
  void loginSuccessAndFailureCountersIncrement() throws Exception {
    saveUser("metrics-login");
    double successBefore = count("auth.login.success");
    double failureBefore = count("auth.login.failure");

    login("metrics-login", PASSWORD, 200);
    login("metrics-login", "wrong-password", 401);
    // Unknown identifiers count as failures too (identical 401, #60).
    login("no-such-user", "wrong-password", 401);

    assertThat(count("auth.login.success")).isEqualTo(successBefore + 1);
    assertThat(count("auth.login.failure")).isEqualTo(failureBefore + 2);
  }

  @Test
  void lockoutCounterFiresOnceWhenTheLockTriggers() throws Exception {
    saveUser("metrics-lockout");
    double failureBefore = count("auth.login.failure");
    double lockoutBefore = count("auth.lockout.triggered");

    login("metrics-lockout", "wrong-password", 401);
    login("metrics-lockout", "wrong-password", 401);
    // The 3rd consecutive failure trips the lock (423, not 401).
    login("metrics-lockout", "wrong-password", 423);
    // An attempt while already locked is neither a new failure nor a
    // new trigger — it must not inflate either counter.
    login("metrics-lockout", PASSWORD, 423);

    assertThat(count("auth.login.failure")).isEqualTo(failureBefore + 3);
    assertThat(count("auth.lockout.triggered")).isEqualTo(lockoutBefore + 1);
  }

  @Test
  void passwordChangedCounterIncrementsOnlyOnSuccess() throws Exception {
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"metrics-pw\",\"email\":\"metrics-pw@example.com\""
                + ",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String access = JsonPath.read(registered.getResponse().getContentAsString(),
        "$.data.accessToken");
    double before = count("auth.password.changed");

    // Wrong current password → 401, no increment.
    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"not-the-password\""
                + ",\"newPassword\":\"n3w-s3cret\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(count("auth.password.changed")).isEqualTo(before);

    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\""
                + ",\"newPassword\":\"n3w-s3cret\"}"))
        .andExpect(status().isOk());
    assertThat(count("auth.password.changed")).isEqualTo(before + 1);
  }

  @Test
  void theftDetectedCounterIncrementsOnPostGraceReplayOnly() throws Exception {
    saveUser("metrics-theft");
    MvcResult login = login("metrics-theft", PASSWORD, 200);
    Cookie first = login.getResponse().getCookie("refresh_token");
    assertThat(first).isNotNull();
    double before = count("auth.refresh.theft_detected");

    // One legitimate rotation, then a grace-window replay: no theft.
    MvcResult rotated = mockMvc.perform(post("/api/auth/refresh").cookie(first))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(rotated.getResponse().getCookie("refresh_token")).isNotNull();
    mockMvc.perform(post("/api/auth/refresh").cookie(first))
        .andExpect(status().isOk());
    assertThat(count("auth.refresh.theft_detected")).isEqualTo(before);

    // Replay the same stale token after the 60 s grace window: theft.
    clock.advance(Duration.ofSeconds(61));
    mockMvc.perform(post("/api/auth/refresh").cookie(first))
        .andExpect(status().isUnauthorized());
    assertThat(count("auth.refresh.theft_detected")).isEqualTo(before + 1);
  }
}
