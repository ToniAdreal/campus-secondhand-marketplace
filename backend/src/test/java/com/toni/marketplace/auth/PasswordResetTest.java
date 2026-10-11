package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
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
 * Password-reset flow (backlog #90) end-to-end via MockMvc: the mail seam
 * is replaced with a capturing sender (the raw token reaches the test only
 * through it — exactly like a real user's mailbox), and the clock is a
 * {@link MutableClock} so token expiry is deterministic.
 *
 * <p>Covered: happy path (new password logs in; every pre-reset session —
 * refresh cookie AND Bearer access token — is dead), enumeration-identical
 * request responses, single-use tokens, a new request invalidating the
 * prior token, expiry, a weak password not consuming the token, and a
 * reset clearing a login lockout (the locked-out user is who this flow
 * exists for).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
class PasswordResetTest {

  /** Captures the raw token per user id, standing in for the mailbox. */
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

  private static final String REGISTER =
      "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"s3cret-pass1\"}";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PasswordResetTokenRepository resetTokens;

  @Autowired
  private MutableClock clock;

  @BeforeEach
  void clearMailbox() {
    MAILBOX.clear();
  }

  @Test
  void happyPathResetsPasswordAndKillsEveryPreResetSession() throws Exception {
    MvcResult registered = register();
    String access = accessToken(registered);
    Cookie refreshCookie = registered.getResponse().getCookie("refresh_token");
    assertThat(refreshCookie).isNotNull();
    long userId = ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();

    String token = requestResetAndCapture("ALICE@EXAMPLE.COM");
    // Only the hash is stored: the row's hash is not the raw token.
    assertThat(resetTokens.count()).isEqualTo(1);
    assertThat(resetTokens.findAll().get(0).getTokenHash()).isNotEqualTo(token);

    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // Every pre-reset session dies: the refresh cookie 401s (family
    // revoked) and the old Bearer token 401s (tokenVersion bumped).
    mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // Old password is gone, new password logs in.
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    assertThat(MAILBOX).containsKey(userId);
  }

  @Test
  void requestResponseIsIdenticalForUnknownIdentifiers() throws Exception {
    register();
    long tokensBefore = resetTokens.count();

    MvcResult known = requestReset("alice");
    MvcResult unknownName = requestReset("nosuchuser");
    MvcResult unknownEmail = requestReset("ghost@example.com");

    String body = known.getResponse().getContentAsString();
    assertThat(unknownName.getResponse().getContentAsString()).isEqualTo(body);
    assertThat(unknownEmail.getResponse().getContentAsString()).isEqualTo(body);
    // Only the real account produced a token row (and a mail).
    assertThat(resetTokens.count()).isEqualTo(tokensBefore + 1);
    assertThat(MAILBOX).hasSize(1);
  }

  @Test
  void tokenIsSingleUse() throws Exception {
    register();
    String token = requestResetAndCapture("alice");

    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk());

    // Replay of the consumed token: the identical 401 as an unknown token.
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"an0ther-one\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("invalid credentials"));
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"totally-unknown-token\",\"newPassword\":\"an0ther-one\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("invalid credentials"));

    // The replay changed nothing: the reset password still logs in, the
    // replay's password does not.
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void newRequestInvalidatesThePriorToken() throws Exception {
    register();
    String first = requestResetAndCapture("alice");
    String second = requestResetAndCapture("alice");
    assertThat(second).isNotEqualTo(first);
    assertThat(resetTokens.count()).isEqualTo(1);

    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + first + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + second + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void expiredTokenReturns401AndChangesNothing() throws Exception {
    register();
    String token = requestResetAndCapture("alice");

    clock.advance(Duration.ofMinutes(31));

    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("invalid credentials"));

    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void weakNewPasswordReturns400AndDoesNotConsumeTheToken() throws Exception {
    register();
    String token = requestResetAndCapture("alice");

    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"abcdefghijkl\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message")
            .value("password must contain both letters and digits"));

    // The token survived the rejected attempt.
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void resetClearsALoginLockout() throws Exception {
    register();
    // Five failed logins lock the account (app.auth.login-lockout defaults).
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(post("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"usernameOrEmail\":\"alice\",\"password\":\"wrong-pass\"}"))
          .andReturn();
    }
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isLocked());

    String token = requestResetAndCapture("alice");
    mockMvc.perform(post("/api/auth/password-reset/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\",\"newPassword\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk());

    // The recovered user logs in immediately — no 15-minute lock remains.
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"n3w-s3cret12\"}"))
        .andExpect(status().isOk());
  }

  private MvcResult register() throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isOk())
        .andReturn();
  }

  private MvcResult requestReset(String identifier) throws Exception {
    return mockMvc.perform(post("/api/auth/password-reset")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + identifier + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
  }

  /** Requests a reset and returns the raw token from the captured mail. */
  private String requestResetAndCapture(String identifier) throws Exception {
    requestReset(identifier);
    assertThat(MAILBOX).hasSize(1);
    return MAILBOX.values().iterator().next();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }
}
