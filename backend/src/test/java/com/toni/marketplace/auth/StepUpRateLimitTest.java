package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of {@code POST /api/auth/step-up} through the
 * {@code AuthRateLimitFilter} (backlog #218): the endpoint verifies the
 * current password, so it is a password-guessing surface for anyone
 * holding a stolen Bearer <redacted> own per-<em>user</em> bucket
 * (production default 5/minute; pinned to 3/minute here so the burst
 * arithmetic stays small), keyed on the JWT principal id like the
 * message/report buckets. The bucket never resets on success — a correct
 * proof does not buy more guesses.
 *
 * <p>Buckets are in-memory and survive across tests within this class, so
 * each test uses a fresh user (per-user keys), mirroring
 * {@code AccountDataExportRateLimitTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the step-up surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.step-up-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // step-up-bucket assertions.
    "app.auth.login-lockout.enabled=false"
})
class StepUpRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshUser() {
    String tag = "stepuprl-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private void stepUpOnce(String bearer, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void burstUnderTheLimitPasses() throws Exception {
    User user = freshUser();
    String bearer = jwt.createTokenPair(user).accessToken();
    stepUpOnce(bearer, 200);
    stepUpOnce(bearer, 200);
    stepUpOnce(bearer, 200);
  }

  @Test
  void fourthAttemptIsRejectedWith429Envelope() throws Exception {
    User user = freshUser();
    String bearer = jwt.createTokenPair(user).accessToken();
    stepUpOnce(bearer, 200);
    stepUpOnce(bearer, 200);
    stepUpOnce(bearer, 200);
    mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void bucketIsPerUser() throws Exception {
    // Exhausting one user's bucket leaves another user's untouched.
    User first = freshUser();
    String firstBearer = jwt.createTokenPair(first).accessToken();
    stepUpOnce(firstBearer, 200);
    stepUpOnce(firstBearer, 200);
    stepUpOnce(firstBearer, 200);
    stepUpOnce(firstBearer, 429);

    User second = freshUser();
    stepUpOnce(jwt.createTokenPair(second).accessToken(), 200);
  }
}
