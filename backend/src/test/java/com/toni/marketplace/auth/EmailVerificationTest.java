package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email-verification flow (backlog #138) end-to-end via MockMvc, with the
 * gate explicitly ON (the shared test profile turns it off — see
 * {@code src/test/resources/application.properties}). The mail seam is
 * replaced with a capturing sender (the raw token reaches the test only
 * through it — exactly like a real user's mailbox), and the clock is a
 * {@link MutableClock} so token expiry is deterministic.
 *
 * <p>Covered: register answers 201 with no session (no access token, no
 * refresh cookie) and stores only the token's hash; login with the
 * CORRECT password answers 403 (not 401) while unverified, and a wrong
 * password still answers the identical 401; confirm → login works;
 * unknown / used / expired tokens are the identical 400; resend
 * supersedes the earlier token and never leaks whether an address exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = "app.auth.email-verification.enabled=true")
class EmailVerificationTest {

  /** Captures the raw token per user id, standing in for the mailbox. */
  static final Map<Long, String> MAILBOX = new ConcurrentHashMap<>();

  @TestConfiguration
  static class CapturingMail {
    @Bean
    @Primary
    EmailVerificationMailSender capturingSender() {
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
  private UserRepository users;

  @Autowired
  private EmailVerificationTokenRepository verificationTokens;

  @Autowired
  private MutableClock clock;

  @BeforeEach
  void clearMailbox() {
    MAILBOX.clear();
  }

  @Test
  void registerIssuesNoSessionAndLoginIsBlockedUntilConfirm() throws Exception {
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.emailVerificationRequired").value(true))
        .andExpect(jsonPath("$.data.user.username").value("alice"))
        .andExpect(jsonPath("$.data.accessToken").doesNotExist())
        .andExpect(cookie().doesNotExist("refresh_token"))
        .andReturn();
    long userId = ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();

    // The account exists, unverified; only the token's hash is stored.
    assertThat(users.findById(userId).orElseThrow().isEmailVerified()).isFalse();
    String token = MAILBOX.get(userId);
    assertThat(token).isNotBlank();
    assertThat(verificationTokens.count()).isEqualTo(1);
    assertThat(verificationTokens.findAll().get(0).getTokenHash()).isNotEqualTo(token);

    // Correct password, unverified address → 403 with the distinct
    // message (NOT the credential failure's 401).
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("email address is not verified"));
    // Wrong password → the identical 401 as everywhere (no oracle).
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"wr0ng-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    confirm(token).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    assertThat(users.findById(userId).orElseThrow().isEmailVerified()).isTrue();

    // Login now works and issues the normal pair + httpOnly cookie.
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"alice\",\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.accessToken").isString())
        .andExpect(cookie().exists("refresh_token"));
  }

  @Test
  void confirmRejectsUnknownUsedAndExpiredTokensIdentically() throws Exception {
    // Unknown token → 400.
    confirm("no-such-token")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("invalid or expired verification token"));

    // Used token: confirm once (200), replay → the identical 400.
    MvcResult registered = registerAlice();
    long userId = ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();
    String token = MAILBOX.get(userId);
    confirm(token).andExpect(status().isOk());
    confirm(token)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid or expired verification token"));

    // Expired token (TTL is 24 h): register a second account, let its
    // token age past the TTL → the identical 400.
    MvcResult second = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"bob\",\"email\":\"bob@example.com\","
                + "\"password\":\"s3cret-pass1\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    long bobId = ((Number) JsonPath.read(
        second.getResponse().getContentAsString(), "$.data.user.id")).longValue();
    clock.advance(Duration.ofHours(25));
    confirm(MAILBOX.get(bobId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid or expired verification token"));
  }

  @Test
  void resendSupersedesEarlierTokenAndNeverLeaksWhetherAnAddressExists() throws Exception {
    MvcResult registered = registerAlice();
    long userId = ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();
    String firstToken = MAILBOX.get(userId);

    // Resend (mixed case — the lookup is case-insensitive) issues a fresh
    // token and supersedes the first link.
    String resendBody = resend("ALICE@EXAMPLE.COM").getResponse().getContentAsString();
    String secondToken = MAILBOX.get(userId);
    assertThat(secondToken).isNotBlank().isNotEqualTo(firstToken);
    confirm(firstToken)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("invalid or expired verification token"));
    confirm(secondToken).andExpect(status().isOk());

    // Unknown address: the response body is byte-identical to a real
    // resend's, and nothing is mailed.
    int mailboxSize = MAILBOX.size();
    String unknownBody = resend("nobody@example.com").getResponse().getContentAsString();
    assertThat(unknownBody).isEqualTo(resendBody);
    assertThat(MAILBOX).hasSize(mailboxSize);

    // Already-verified account: identical body again, no fresh token.
    String verifiedBody = resend("alice@example.com").getResponse().getContentAsString();
    assertThat(verifiedBody).isEqualTo(resendBody);
    assertThat(MAILBOX.get(userId)).isEqualTo(secondToken);
  }

  private MvcResult registerAlice() throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER))
        .andExpect(status().isCreated())
        .andReturn();
  }

  private org.springframework.test.web.servlet.ResultActions confirm(String token)
      throws Exception {
    return mockMvc.perform(post("/api/auth/email-verification/confirm")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}"));
  }

  private MvcResult resend(String email) throws Exception {
    return mockMvc.perform(post("/api/auth/email-verification/resend")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
  }
}
