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
 * Backlog #197 end-to-end: the breached-password screen fires on ALL
 * THREE credential-setting paths through the one shared validator —
 * register, authenticated password change, and reset confirm — with the
 * production default bean (the offline local denylist). The denylisted
 * candidate {@code password1234} is 12 characters with letters and
 * digits, so it passes every other strength rule; the breach screen is
 * the only thing that can reject it, and its 400 uses the same envelope
 * as every other strength failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BreachedPasswordScreeningTest {

  private static final String BREACHED = "password1234";
  private static final String BREACHED_REASON =
      "password has appeared in a known data breach, choose a different one";
  private static final String CLEAN = "s3cret-pass1";
  private static final String CLEAN_NEW = "n3w-s3cret12";

  /** Captures the raw reset token per user id, standing in for the mailbox. */
  static final Map<Long, String> MAILBOX = new ConcurrentHashMap<>();

  @TestConfiguration
  static class CapturingMail {
    @Bean
    @Primary
    PasswordResetMailSender capturingSender() {
      return (user, rawToken) -> MAILBOX.put(user.getId(), rawToken);
    }
  }

  @Autowired
  private MockMvc mockMvc;

  @BeforeEach
  void clearMailbox() {
    MAILBOX.clear();
  }

  @Test
  void registerRejectsABreachedPasswordWithTheStrengthEnvelope() throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("breacheduser", BREACHED)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value(BREACHED_REASON));

    // The same username registers fine with a clean password — the
    // rejection created no account and reserved nothing.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("breacheduser", CLEAN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void passwordChangeRejectsABreachedPassword() throws Exception {
    String access = accessToken(registerAlice());

    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + CLEAN
                + "\",\"newPassword\":\"" + BREACHED + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value(BREACHED_REASON));

    // Nothing changed: the old password still logs in, the breached one does not.
    login("alice", CLEAN);
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"" + BREACHED + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void resetConfirmRejectsABreachedPasswordAndKeepsTheTokenUsable() throws Exception {
    MvcResult registered = registerAlice();
    long userId = ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();

    mockMvc.perform(post("/api/auth/password-reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\"}"))
        .andExpect(status().isOk());
    assertThat(MAILBOX).containsKey(userId);
    String token = MAILBOX.get(userId);

    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + BREACHED + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value(BREACHED_REASON));

    // Like every strength rejection, the token is NOT consumed.
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + CLEAN_NEW + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    login("alice", CLEAN_NEW);
  }

  private String registerBody(String username, String password) {
    return "{\"username\":\"" + username + "\",\"email\":\"" + username
        + "@example.com\",\"password\":\"" + password + "\"}";
  }

  private MvcResult registerAlice() throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("alice", CLEAN)))
        .andExpect(status().isOk())
        .andReturn();
  }

  private MvcResult login(String usernameOrEmail, String password) throws Exception {
    return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + usernameOrEmail
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }
}
