package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import jakarta.servlet.http.Cookie;
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
 * POST /api/auth/email-change end-to-end via MockMvc (backlog #150), with
 * the email-verification gate explicitly ON (the shared test profile turns
 * it off — see {@code src/test/resources/application.properties}) and the
 * mail seam replaced with a capturing sender, like
 * {@link EmailVerificationTest}.
 *
 * <p>Covered: the happy path flips the address (canonicalized), flips the
 * account back to unverified, issues the verification token to the NEW
 * address only, kills every pre-change session (the old Bearer 401s, the
 * old refresh cookie 401s, the response clears the cookie), blocks login
 * at the new address with the #138 403 until the token round trip
 * confirms it, makes the old address an unknown identifier (401), and
 * writes exactly one EMAIL_CHANGED audit row. A wrong current password is
 * the identical 401 with NOTHING changed; a taken address and the
 * account's own current address are both 409; an anonymous call is 401
 * (the SecurityConfig matcher — the #127 fall-through class).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = "app.auth.email-verification.enabled=true")
class EmailChangeTest {

  /** Captures the raw verification token per user id — the mailbox. */
  static final Map<Long, String> MAILBOX = new ConcurrentHashMap<>();

  @TestConfiguration
  static class CapturingMail {
    @Bean
    @Primary
    EmailVerificationMailSender capturingSender() {
      return (user, rawToken) -> MAILBOX.put(user.getId(), rawToken);
    }
  }

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private AuditLogRepository auditLog;

  @BeforeEach
  void clearMailbox() {
    MAILBOX.clear();
  }

  @Test
  void happyPathFlipsAddressReverifiesAndKillsEverySession() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("alice", "alice@example.com");
    long userId = session.userId();

    MvcResult changed = mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"Alice.New@Example.COM\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    // The refresh cookie is cleared, exactly like logout's expired Set-Cookie.
    Cookie cleared = changed.getResponse().getCookie("refresh_token");
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();

    // The row: canonical new address, unverified again.
    User user = users.findById(userId).orElseThrow();
    assertThat(user.getEmail()).isEqualTo("alice.new@example.com");
    assertThat(user.isEmailVerified()).isFalse();

    // Every pre-change session is dead: the old Bearer 401s and the old
    // refresh cookie 401s.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + session.accessToken()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/refresh").cookie(session.refreshCookie()))
        .andExpect(status().isUnauthorized());

    // The old address is now an unknown identifier — the identical 401.
    login("alice@example.com", PASSWORD, 401);
    // The new address answers the #138 403 until it is verified.
    login("alice.new@example.com", PASSWORD, 403);

    // The verification token went to the NEW address (the capturing sender
    // keys by user id, and issue() replaced the registration token).
    String token = MAILBOX.get(userId);
    assertThat(token).isNotBlank();
    mockMvc.perform(post("/api/auth/email-verification/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk());
    login("alice.new@example.com", PASSWORD, 200);

    // Exactly one audit row, actor = target = the account holder.
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId))
        .extracting(row -> row.getAction())
        .containsExactly(AuditAction.EMAIL_CHANGED);
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, userId)).hasSize(1);
  }

  @Test
  void wrongCurrentPasswordIs401AndChangesNothing() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("bob", "bob@example.com");
    String registrationToken = MAILBOX.get(session.userId());

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wr0ng-password\","
                + "\"newEmail\":\"bob.new@example.com\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    User user = users.findById(session.userId()).orElseThrow();
    assertThat(user.getEmail()).isEqualTo("bob@example.com");
    assertThat(user.isEmailVerified()).isTrue();
    // No fresh token was issued and no audit row written.
    assertThat(MAILBOX.get(session.userId())).isEqualTo(registrationToken);
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(session.userId())).isEmpty();
    // The session is untouched — the same Bearer still works.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + session.accessToken()))
        .andExpect(status().isOk());
  }

  @Test
  void takenAddressIs409AndChangesNothing() throws Exception {
    registerVerifyAndLogin("carol", "carol@example.com");
    VerifiedSession dave = registerVerifyAndLogin("dave", "dave@example.com");

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + dave.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"CAROL@example.com\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    User user = users.findById(dave.userId()).orElseThrow();
    assertThat(user.getEmail()).isEqualTo("dave@example.com");
    assertThat(user.isEmailVerified()).isTrue();
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(dave.userId())).isEmpty();
  }

  @Test
  void sameAsCurrentAddressIs409() throws Exception {
    VerifiedSession erin = registerVerifyAndLogin("erin", "erin@example.com");

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + erin.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"erin@example.com\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    assertThat(users.findById(erin.userId()).orElseThrow().isEmailVerified()).isTrue();
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(erin.userId())).isEmpty();
  }

  @Test
  void anonymousCallerIs401() throws Exception {
    // No Bearer: the SecurityConfig matcher must authenticate this route —
    // without it the call would fall through to the public /api/auth/**
    // rule and 500 on the null principal (the backlog #127 hole class).
    mockMvc.perform(post("/api/auth/email-change")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"nobody@example.com\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void malformedNewAddressIs400() throws Exception {
    VerifiedSession fred = registerVerifyAndLogin("fred", "fred@example.com");

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + fred.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"not-an-email\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(users.findById(fred.userId()).orElseThrow().getEmail())
        .isEqualTo("fred@example.com");
  }

  private record VerifiedSession(long userId, String accessToken, Cookie refreshCookie) {}

  /** Registers (gate on → 201, no session), confirms via the mailbox token, logs in. */
  private VerifiedSession registerVerifyAndLogin(String username, String email)
      throws Exception {
    MvcResult registered = mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    long userId = ((Number) JsonPath.read(
        registered.getResponse().getContentAsString(), "$.data.user.id")).longValue();
    String token = MAILBOX.get(userId);
    assertThat(token).isNotBlank();
    mockMvc.perform(post("/api/auth/email-verification/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk());
    MvcResult loggedIn = login(email, PASSWORD, 200);
    String access = JsonPath.read(
        loggedIn.getResponse().getContentAsString(), "$.data.accessToken");
    Cookie refresh = loggedIn.getResponse().getCookie("refresh_token");
    assertThat(refresh).isNotNull();
    return new VerifiedSession(userId, access, refresh);
  }

  private MvcResult login(String identifier, String password, int expectedStatus)
      throws Exception {
    return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + identifier
                + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }
}
