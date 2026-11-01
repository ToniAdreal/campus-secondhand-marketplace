package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of {@code POST /api/auth/password} through
 * {@code AuthRateLimitFilter} (backlog #290): the endpoint verifies the
 * current password, so it is a password-guessing surface for anyone
 * holding a stolen Bearer token — it gets its own per-<em>user</em>
 * bucket (production default 5/minute, like the step-up bucket; pinned
 * to 3/minute here so the burst arithmetic stays small), keyed on the
 * JWT principal id like the step-up/message buckets, with the per-IP
 * fallback for a missing/invalid token. The bucket never resets on
 * success — a completed change must not refill a guesser's budget —
 * and it is deliberately separate from the step-up bucket (which
 * covers only the re-proof) and the credential bucket, so guessing at
 * one surface can never starve the others.
 *
 * <p>Buckets are in-memory and survive across tests within this class, so
 * each test uses a fresh user (per-user keys), mirroring
 * {@code StepUpRateLimitTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the password-change surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.password-change-attempts-per-minute=3",
    // Pin the step-up bucket to the same small size so the
    // reverse-independence test can exhaust it without 5 re-proofs.
    "app.auth.rate-limit.step-up-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the
    // bucket assertions (changePassword feeds no lockout counter, but
    // the logins below must not trip one either).
    "app.auth.login-lockout.enabled=false"})
class PasswordChangeRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshUser() {
    String tag = "pwchange-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private ResultActions changeOnce(String bearer, String current, String next) throws Exception {
    return mockMvc.perform(post("/api/auth/password")
        .header("Authorization", "Bearer " + bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
  }

  private ResultActions changeWrongOnce(String bearer) throws Exception {
    return changeOnce(bearer, "wrong-pass", "n3w-s3cret12");
  }

  private ResultActions loginOnce(String username, String password) throws Exception {
    return mockMvc.perform(post("/api/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\"" + password + "\"}"));
  }

  @Test
  void singleLegitimateChangePasses() throws Exception {
    User user = freshUser();
    String bearer = jwt.createTokenPair(user).accessToken();
    changeOnce(bearer, "password", "n3w-s3cret12")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.accessToken").exists());
    // The new password works, the old one no longer does.
    loginOnce(user.getUsername(), "n3w-s3cret12").andExpect(status().isOk());
    loginOnce(user.getUsername(), "password").andExpect(status().isUnauthorized());
  }

  @Test
  void fourthAttemptIsRejectedWith429AndFamilyIntact() throws Exception {
    User user = freshUser();
    JwtTokenService.TokenPair pair = jwt.createTokenPair(user);
    String bearer = pair.accessToken();
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        });
    // The throttled attempts changed nothing server-side: the original
    // refresh family still rotates, and the old password still logs in.
    mockMvc.perform(post("/api/auth/refresh")
            .cookie(new Cookie("refresh_token", pair.refreshToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    loginOnce(user.getUsername(), "password").andExpect(status().isOk());
  }

  @Test
  void bucketIsPerUser() throws Exception {
    User first = freshUser();
    String firstBearer = jwt.createTokenPair(first).accessToken();
    changeWrongOnce(firstBearer).andExpect(status().isUnauthorized());
    changeWrongOnce(firstBearer).andExpect(status().isUnauthorized());
    changeWrongOnce(firstBearer).andExpect(status().isUnauthorized());
    changeWrongOnce(firstBearer).andExpect(status().isTooManyRequests());

    User second = freshUser();
    changeOnce(jwt.createTokenPair(second).accessToken(), "password", "n3w-s3cret12")
        .andExpect(status().isOk());
  }

  @Test
  void passwordBucketExhaustionLeavesStepUpIntact() throws Exception {
    User user = freshUser();
    String bearer = jwt.createTokenPair(user).accessToken();
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer).andExpect(status().isTooManyRequests());
    // The step-up bucket is untouched: a correct re-proof still passes.
    mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void stepUpBucketExhaustionLeavesPasswordChangeIntact() throws Exception {
    User user = freshUser();
    String bearer = jwt.createTokenPair(user).accessToken();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/step-up")
              .header("Authorization", "Bearer " + bearer)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"currentPassword\":\"password\"}"))
          .andExpect(status().isOk());
    }
    mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
    // The password-change bucket is untouched: a correct change passes.
    changeOnce(bearer, "password", "n3w-s3cret12")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void passwordBucketExhaustionLeavesCredentialBucketIntact() throws Exception {
    User user = freshUser();
    String bearer = jwt.createTokenPair(user).accessToken();
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer).andExpect(status().isUnauthorized());
    changeWrongOnce(bearer).andExpect(status().isTooManyRequests());
    // The credential bucket is untouched: a correct login still passes.
    loginOnce(user.getUsername(), "password").andExpect(status().isOk());
  }

  @Test
  void anonymousFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous changes are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.44";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/password")
              .with(request -> {
                request.setRemoteAddr(ip);
                return request;
              })
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"currentPassword\":\"password\",\"newPassword\":\"n3w-s3cret12\"}"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/auth/password")
            .with(request -> {
              request.setRemoteAddr(ip);
              return request;
            })
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }
}
