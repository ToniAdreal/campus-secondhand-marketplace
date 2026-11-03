package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of the account-identity writes through
 * {@code AuthRateLimitFilter} (backlog #310): {@code POST
 * /api/auth/username} (rename the caller's account) and {@code DELETE
 * /api/auth/me} (close the caller's account) share one per-<em>user</em>
 * bucket (production default 5/minute, like the credential buckets —
 * pinned to 3/minute here so the burst arithmetic stays small), keyed
 * on the JWT principal id like the message/report buckets, with the
 * per-IP fallback for a missing/invalid token. The bucket never resets
 * on success — a completed rename must not refill the allowance — and
 * it is deliberately separate from the #290 password-change bucket
 * (changing the password stays there) and the #296 session-revocation
 * bucket (killing sessions stays there). A throttled attempt never
 * reaches the controller, so it renames nothing and closes nothing;
 * the profile-read sibling ({@code GET /api/auth/me}) stays an
 * unthrottled read.
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses fresh users (per-user keys), mirroring
 * {@code NotificationRateLimitTest}. Renames are repeatable by using a
 * fresh name per call (a repeat of the current name is a 409, not a
 * 200); the closure shape is terminal per user, so burst tests exhaust
 * the bucket with renames and spend the throttled call on the closure.
 * Tokens come from {@code JwtTokenService.createTokenPair}, whose
 * access token carries a fresh credential proof, so the step-up gate
 * on both shapes never masks the bucket assertions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the account-identity surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.account-identity-attempts-per-minute=3",
    // Pin the neighbouring #290 and #296 buckets to the same small size
    // so the independence tests can exhaust them without a long burst.
    "app.auth.rate-limit.password-change-attempts-per-minute=3",
    "app.auth.rate-limit.session-revocation-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions.
    "app.auth.login-lockout.enabled=false"})
class AccountIdentityRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshUser() {
    String tag = "acctid-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String bearer(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Cookie refreshCookie(User user) {
    return new Cookie("refresh_token", jwt.createTokenPair(user).refreshToken());
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private void renameOnce(String token, String name, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void closeOnce(String token, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/auth/me")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"password\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void changePasswordOnce(String token, String current, String next,
      int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void revokeOthersOnce(String token, Cookie cookie, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/sessions/revoke-others")
            .header("Authorization", "Bearer " + token)
            .cookie(cookie))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateRenamePasses() throws Exception {
    User user = freshUser();
    renameOnce(bearer(user), "rename-one", 200);
    assertThat(users.findById(user.getId()).orElseThrow().getUsername())
        .isEqualTo("rename-one");
  }

  @Test
  void singleLegitimateClosurePasses() throws Exception {
    User user = freshUser();
    closeOnce(bearer(user), 200);
    User row = users.findById(user.getId()).orElseThrow();
    assertThat(row.getUsername()).isEqualTo("deleted-" + user.getId());
    assertThat(row.isDisabled()).isTrue();
  }

  @Test
  void accountIdentityBurstIs429WithEnvelope() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    renameOnce(token, "rename-one", 200);
    renameOnce(token, "rename-two", 200);
    renameOnce(token, "rename-three", 200);
    // The bucket is empty: the fourth write is throttled before the
    // controller ever sees it, so the name stays at the third rename.
    mockMvc.perform(post("/api/auth/username")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"rename-four\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        });
    assertThat(users.findById(user.getId()).orElseThrow().getUsername())
        .isEqualTo("rename-three");
  }

  @Test
  void accountIdentityBucketIsSharedAcrossRenameAndClosure() throws Exception {
    // Exhausting the bucket with renames leaves a closure throttled
    // too — one bucket for the whole identity surface, not one per
    // endpoint. The filter short-circuits before the controller, so
    // the account is NOT closed: no tombstone, not disabled.
    User user = freshUser();
    String token = bearer(user);
    renameOnce(token, "rename-one", 200);
    renameOnce(token, "rename-two", 200);
    renameOnce(token, "rename-three", 200);
    closeOnce(token, 429);
    User row = users.findById(user.getId()).orElseThrow();
    assertThat(row.getUsername()).isEqualTo("rename-three");
    assertThat(row.isDisabled()).isFalse();
  }

  @Test
  void accountIdentityBucketIsPerUser() throws Exception {
    User first = freshUser();
    String firstToken = bearer(first);
    renameOnce(firstToken, "rename-one", 200);
    renameOnce(firstToken, "rename-two", 200);
    renameOnce(firstToken, "rename-three", 200);
    renameOnce(firstToken, "rename-four", 429);

    User second = freshUser();
    renameOnce(bearer(second), "rename-one", 200);
  }

  @Test
  void anonymousRenameFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous renames are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.76";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/auth/username")
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"anon-rename\"}"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/auth/username")
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"anon-rename\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void accountIdentityBucketExhaustionLeavesPasswordChangeIntact() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    renameOnce(token, "rename-one", 200);
    renameOnce(token, "rename-two", 200);
    renameOnce(token, "rename-three", 200);
    renameOnce(token, "rename-four", 429);
    // The #290 bucket is untouched: a correct password change passes.
    changePasswordOnce(token, "password", "n3w-s3cret12", 200);
    assertThat(passwords.matches("n3w-s3cret12",
        users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
  }

  @Test
  void passwordChangeBucketExhaustionLeavesAccountIdentityIntact() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    // Wrong-password changes still consume the #290 bucket (the filter
    // runs before the controller), but change nothing server-side.
    for (int i = 0; i < 3; i++) {
      changePasswordOnce(token, "wrong-pass", "n3w-s3cret12", 401);
    }
    changePasswordOnce(token, "wrong-pass", "n3w-s3cret12", 429);
    assertThat(passwords.matches("password",
        users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
    // The #310 bucket is untouched: a rename passes.
    renameOnce(token, "rename-one", 200);
  }

  @Test
  void accountIdentityBucketExhaustionLeavesSessionRevocationIntact() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    Cookie cookie = refreshCookie(user);
    renameOnce(token, "rename-one", 200);
    renameOnce(token, "rename-two", 200);
    renameOnce(token, "rename-three", 200);
    renameOnce(token, "rename-four", 429);
    // The #296 bucket is untouched: revoking other sessions passes.
    revokeOthersOnce(token, cookie, 200);
  }

  @Test
  void sessionRevocationBucketExhaustionLeavesAccountIdentityIntact() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    Cookie cookie = refreshCookie(user);
    // Revoke-others is repeatable with a single session (nothing else
    // to revoke), so the burst can repeat one shape without tripping a
    // state guard.
    for (int i = 0; i < 3; i++) {
      revokeOthersOnce(token, cookie, 200);
    }
    revokeOthersOnce(token, cookie, 429);
    // The #310 bucket is untouched: a rename passes.
    renameOnce(token, "rename-one", 200);
  }

  @Test
  void accountIdentityExhaustionLeavesProfileReadUnthrottled() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    renameOnce(token, "rename-one", 200);
    renameOnce(token, "rename-two", 200);
    renameOnce(token, "rename-three", 200);
    renameOnce(token, "rename-four", 429);
    // The GET profile sibling never matches the identity matchers and
    // stays an unthrottled read even with an empty bucket.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }
}
