package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Backlog #127 × #101 — the recovery-code regeneration endpoint shares
 * the second-factor lockout with {@code POST /api/auth/2fa/authenticate}
 * and {@code /2fa/disable} (one factored proof seam in
 * {@code AuthService}): failed second-factor proofs at regeneration feed
 * the same per-account counter, reaching the ceiling locks every
 * second-factor endpoint (423 + {@code Retry-After}), and an active lock
 * refuses even correct proofs at regeneration — otherwise regeneration
 * would be a fresh brute-force oracle bypassing the existing locks.
 * Wrong-PASSWORD attempts are not second-factor guesses and never feed
 * the counter.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link TotpDisableLockoutTest}: the counter must persist across
 * separate requests, where each request runs in its own service
 * transaction and the 401/423 would roll the increment back without the
 * {@code noRollbackFor} declaration on
 * {@code AuthService.regenerateRecoveryCodes}. Each test creates its own
 * user and deletes it afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.auth.totp-lockout.max-attempts=3",
    "app.auth.totp-lockout.lock-duration=15m"
})
class TotpRegenerateLockoutTest {

  private static final AtomicInteger SEQ = new AtomicInteger();
  private static final String PASSWORD = "s3cret-pass";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpService totp;

  private String username;
  private String access;
  private String secret;

  @AfterEach
  void cleanUp() {
    if (username != null) {
      users.findByUsernameIgnoreCase(username).ifPresent(u -> users.deleteById(u.getId()));
    }
  }

  /** Registers a fresh user and enables 2FA; fills {@link #access} / {@link #secret}. */
  private void enroll() throws Exception {
    username = "regenlock" + SEQ.incrementAndGet();
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    access = JsonPath.read(registered.getResponse().getContentAsString(), "$.data.accessToken");
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + currentCode() + "\"}"))
        .andExpect(status().isOk());
  }

  private void regenerate(String password, String code, int expected) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/recovery-codes/regenerate")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + password + "\",\"code\":\"" + code + "\"}"))
        .andExpect(status().is(expected));
  }

  private String currentCode() {
    return totp.codeAt(secret, java.time.Instant.now());
  }

  /** A 6-digit code that verifies in no accepted time step (and is no recovery code). */
  private String wrongCode() {
    java.time.Instant now = java.time.Instant.now();
    Set<String> valid = Set.of(
        totp.codeAt(secret, now.minusSeconds(30)),
        totp.codeAt(secret, now),
        totp.codeAt(secret, now.plusSeconds(30)));
    for (String candidate : List.of("000000", "111111", "222222", "333333", "444444")) {
      if (!valid.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no wrong code candidate");
  }

  @Test
  void failedRegenerateAttemptsFeedTheSharedLockout() throws Exception {
    enroll();
    regenerate(PASSWORD, wrongCode(), 401);
    regenerate(PASSWORD, wrongCode(), 401);
    // The 3rd consecutive second-factor failure locks: 423 + Retry-After.
    mockMvc.perform(post("/api/auth/2fa/recovery-codes/regenerate")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\""
                + wrongCode() + "\"}"))
        .andExpect(status().is(423))
        .andExpect(header().string("Retry-After", "900"));
    // Even the correct proofs are refused while the lock is active.
    regenerate(PASSWORD, currentCode(), 423);
    // And the lock is the SHARED one: the authenticate exchange — fed a
    // fresh challenge from a password login that still answers 202 —
    // is locked too.
    MvcResult login = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\""
                + PASSWORD + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    String challenge = JsonPath.read(login.getResponse().getContentAsString(), "$.data.challenge");
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\"" + currentCode() + "\"}"))
        .andExpect(status().is(423));
  }

  @Test
  void wrongPasswordAttemptsNeverFeedTheTotpCounter() throws Exception {
    enroll();
    // Three wrong-password attempts: plain 401s, never a lock — the
    // password proof is not a second-factor guess.
    regenerate("wr0ng-pass", currentCode(), 401);
    regenerate("wr0ng-pass", currentCode(), 401);
    regenerate("wr0ng-pass", currentCode(), 401);
    // And the correct proofs still regenerate immediately afterwards.
    regenerate(PASSWORD, currentCode(), 200);
  }
}
