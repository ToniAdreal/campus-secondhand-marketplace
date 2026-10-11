package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
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
 * Password history (backlog #139) end-to-end via MockMvc: the
 * authenticated change path and the reset-confirm path enforce the SAME
 * history through the shared {@link PasswordHistoryService} — cycling
 * back to a recent password is a 400 on both, the sliding window lets
 * the sixth-oldest password in again, and a rejected reset confirm does
 * not consume the token. The reset mail seam is replaced with a
 * capturing sender, exactly as in {@code PasswordResetTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class PasswordHistoryTest {

  /** Captures the raw reset token per user id, standing in for the mailbox. */
  static final Map<Long, String> MAILBOX = new ConcurrentHashMap<>();

  @TestConfiguration
  static class CapturingMail {
    @Bean
    @Primary
    PasswordResetMailSender capturingSender() {
      return (user, rawToken) -> MAILBOX.put(user.getId(), rawToken);
    }

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final String A = "s3cret-pass1";
  private static final String B = "n3w-s3cret12";
  private static final String C = "th1rd-s3cret9";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PasswordHistoryRepository historyRepo;

  @Autowired
  private UserRepository users;

  @BeforeEach
  void clearMailbox() {
    MAILBOX.clear();
  }

  @Test
  void changePathRejectsCyclingBackToAPreviousPassword() throws Exception {
    String access = accessToken(register("alice", "alice@example.com", A));
    long userId = userId("alice");

    access = changePassword(access, A, B);
    assertThat(historyRepo.countByUserId(userId)).isEqualTo(1);

    // A→B→A: A is no longer current, but it is the newest history row.
    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content(changeJson(B, A)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("new password must differ from a recent password"));

    // The rejection changed nothing: no new history row, B still logs in.
    assertThat(historyRepo.countByUserId(userId)).isEqualTo(1);
    login("alice", B);
  }

  @Test
  void sixthOldestPasswordIsAllowedAgain() throws Exception {
    String[] seq = {A, B, C, "f0urth-s3cret", "f1fth-s3cret8", "s1xth-s3cret7",
        "s3venth-s3cret"};
    String access = accessToken(register("alice", "alice@example.com", seq[0]));
    long userId = userId("alice");

    // Five changes: history [P0..P4], current P5 — P0 is still inside
    // the last-5 window and cycling back to it is rejected.
    for (int i = 0; i < 5; i++) {
      access = changePassword(access, seq[i], seq[i + 1]);
    }
    assertThat(historyRepo.countByUserId(userId)).isEqualTo(5);
    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content(changeJson(seq[5], seq[0])))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message")
            .value("new password must differ from a recent password"));

    // One more change pushes P0 out of the window (history [P0..P5],
    // last 5 = P5..P1): the sixth-oldest password is allowed again.
    access = changePassword(access, seq[5], seq[6]);
    changePassword(access, seq[6], seq[0]);
    login("alice", seq[0]);
  }

  @Test
  void resetConfirmEnforcesTheHistoryIdentically() throws Exception {
    String access = accessToken(register("alice", "alice@example.com", A));
    long userId = userId("alice");
    changePassword(access, A, B);
    assertThat(historyRepo.countByUserId(userId)).isEqualTo(1);

    String token = requestResetAndCapture("alice");

    // Resetting back to A (a recent password) is the same 400 as on the
    // change path — and, like a weak password, it does NOT consume the
    // token: the same token still confirms with a fresh password.
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + A + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("new password must differ from a recent password"));
    // Resetting to the CURRENT password hits the current-password rule.
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + B + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message")
            .value("new password must differ from the current password"));
    assertThat(historyRepo.countByUserId(userId)).isEqualTo(1);

    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + C + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // The successful confirm appended the outgoing (B) hash in its
    // transaction, and C is now the login password.
    assertThat(historyRepo.countByUserId(userId)).isEqualTo(2);
    login("alice", C);
  }

  private long userId(String username) {
    return users.findByUsernameIgnoreCase(username).orElseThrow().getId();
  }

  private MvcResult register(String username, String email, String password) throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  /** Changes the password, expecting 200, and returns the fresh access token. */
  private String changePassword(String access, String current, String next) throws Exception {
    MvcResult changed = mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content(changeJson(current, next)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    return accessToken(changed);
  }

  private static String changeJson(String current, String next) {
    return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
  }

  private void login(String usernameOrEmail, String password) throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + usernameOrEmail
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  private String requestResetAndCapture(String identifier) throws Exception {
    mockMvc.perform(post("/api/auth/password-reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + identifier + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    assertThat(MAILBOX).hasSize(1);
    return MAILBOX.values().iterator().next();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }
}
