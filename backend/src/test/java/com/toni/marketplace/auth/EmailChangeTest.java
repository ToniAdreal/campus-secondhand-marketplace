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
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pending email change end-to-end via MockMvc (backlog #208, reworking
 * #150), with the email-verification gate explicitly ON (the shared test
 * profile turns it off — see {@code src/test/resources/application.properties})
 * and both mail seams replaced with capturing senders, like
 * {@link EmailVerificationTest}.
 *
 * <p>Covered: the REQUEST only stages the change — the address does not
 * move, the account stays verified, every session stays alive, no
 * EMAIL_CHANGED audit row is written, the change token goes to the NEW
 * address through its own seam, and the holder gets an
 * EMAIL_CHANGE_REQUESTED in-app notification (the old-address alert).
 * The CONFIRM performs the switch: address flips (canonicalized), the
 * account is verified for the new address, the staging field clears,
 * every pre-switch session dies (old Bearer 401s, old refresh cookie
 * 401s, the response clears the cookie), the old address becomes an
 * unknown identifier (401) while the new one logs in, and exactly one
 * EMAIL_CHANGED audit row exists. An address taken between request and
 * confirm answers 409 at confirm and consumes nothing; unknown, used
 * and expired tokens are the identical 401; a second request replaces
 * the pending row and invalidates the first token. A wrong current
 * password is the identical 401 with NOTHING staged; a taken address
 * and the account's own current address are both 409 at request time;
 * an anonymous request is 401 (the SecurityConfig matcher).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = "app.auth.email-verification.enabled=true")
class EmailChangeTest {

  /** Captures the raw verification token per user id — the registration mailbox. */
  static final Map<Long, String> MAILBOX = new ConcurrentHashMap<>();

  /** Captures the raw email-change token per user id — the NEW-address mailbox. */
  static final Map<Long, String> CHANGE_MAILBOX = new ConcurrentHashMap<>();

  @TestConfiguration
  static class CapturingMail {
    @Bean
    @Primary
    EmailVerificationMailSender capturingSender() {
      return (user, rawToken) -> MAILBOX.put(user.getId(), rawToken);
    }

    @Bean
    @Primary
    EmailChangeMailSender capturingChangeSender() {
      return (user, pendingEmail, rawToken) -> CHANGE_MAILBOX.put(user.getId(), rawToken);
    }
  }

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private JdbcTemplate jdbc;

  @PersistenceContext
  private EntityManager entityManager;

  @BeforeEach
  void clearMailboxes() {
    MAILBOX.clear();
    CHANGE_MAILBOX.clear();
  }

  @Test
  void requestStagesPendingChangeWithoutSwitching() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("alice", "alice@example.com");
    long userId = session.userId();
    String registrationToken = MAILBOX.get(userId);

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"Alice.New@Example.COM\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    // The row: address UNCHANGED, still verified, pending staged canonical.
    User user = users.findById(userId).orElseThrow();
    assertThat(user.getEmail()).isEqualTo("alice@example.com");
    assertThat(user.isEmailVerified()).isTrue();
    assertThat(user.getPendingEmail()).isEqualTo("alice.new@example.com");

    // Every pre-request session is still alive: the same Bearer works
    // and the old address still logs in (the switch has not happened).
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + session.accessToken()))
        .andExpect(status().isOk());
    login("alice@example.com", PASSWORD, 200);

    // The change token went through its OWN seam to the new address;
    // no fresh verification token was issued.
    assertThat(CHANGE_MAILBOX.get(userId)).isNotBlank();
    assertThat(MAILBOX.get(userId)).isEqualTo(registrationToken);

    // The old-address alert: exactly one EMAIL_CHANGE_REQUESTED
    // notification, and NO audit row — the audit belongs to the switch.
    assertThat(notifications.findByRecipientId(userId, PageRequest.of(0, 10)).getContent())
        .extracting(row -> row.getType())
        .containsExactly(NotificationType.EMAIL_CHANGE_REQUESTED);
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId)).isEmpty();
  }

  @Test
  void confirmSwitchesRevokesAndAudits() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("alice", "alice@example.com");
    long userId = session.userId();

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"Alice.New@Example.COM\"}"))
        .andExpect(status().isOk());
    String token = CHANGE_MAILBOX.get(userId);
    assertThat(token).isNotBlank();

    MvcResult confirmed = mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    // The refresh cookie is cleared, exactly like logout's expired Set-Cookie.
    Cookie cleared = confirmed.getResponse().getCookie("refresh_token");
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();

    // The row: canonical new address, verified, staging cleared.
    User user = users.findById(userId).orElseThrow();
    assertThat(user.getEmail()).isEqualTo("alice.new@example.com");
    assertThat(user.getPendingEmail()).isNull();
    assertThat(user.isEmailVerified()).isTrue();

    // Every pre-switch session is dead: the old Bearer 401s and the old
    // refresh cookie 401s.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + session.accessToken()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/refresh").cookie(session.refreshCookie()))
        .andExpect(status().isUnauthorized());

    // The old address is now an unknown identifier — the identical 401;
    // the new one logs in directly (the confirm proved the inbox).
    login("alice@example.com", PASSWORD, 401);
    login("alice.new@example.com", PASSWORD, 200);

    // Exactly one audit row, actor = target = the account holder.
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId))
        .extracting(row -> row.getAction())
        .containsExactly(AuditAction.EMAIL_CHANGED);
    assertThat(auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.USER, userId)).hasSize(1);

    // Token reuse is the identical 401 as an unknown token.
    mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void confirmAfterAddressTakenIs409AndSwitchesNothing() throws Exception {
    VerifiedSession alice = registerVerifyAndLogin("alice", "alice@example.com");
    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + alice.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"contested@example.com\"}"))
        .andExpect(status().isOk());
    String token = CHANGE_MAILBOX.get(alice.userId());
    assertThat(token).isNotBlank();

    // In between, someone else registers with the contested address.
    mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"frank\",\"email\":\"contested@example.com\","
                + "\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isCreated());

    mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    User user = users.findById(alice.userId()).orElseThrow();
    assertThat(user.getEmail()).isEqualTo("alice@example.com");
    assertThat(user.getPendingEmail()).isEqualTo("contested@example.com");
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(alice.userId())).isEmpty();
    // The session is untouched — no switch, no revocation.
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + alice.accessToken()))
        .andExpect(status().isOk());
  }

  @Test
  void unknownAndExpiredTokensAreTheIdentical401() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("gina", "gina@example.com");

    mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"no-such-token\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"gina.new@example.com\"}"))
        .andExpect(status().isOk());
    String token = CHANGE_MAILBOX.get(session.userId());
    assertThat(token).isNotBlank();
    // Age the token past its expiry directly in the table. The JDBC
    // update bypasses JPA, so flush + clear the persistence context —
    // otherwise the confirm would read the still-managed entity with
    // its original (unexpired) expiresAt from the first-level cache.
    jdbc.update("update email_change_token set expires_at = ? where user_id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), session.userId());
    entityManager.flush();
    entityManager.clear();

    mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    assertThat(users.findById(session.userId()).orElseThrow().getEmail())
        .isEqualTo("gina@example.com");
  }

  @Test
  void secondRequestReplacesPendingAndInvalidatesFirstToken() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("hank", "hank@example.com");

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"hank.one@example.com\"}"))
        .andExpect(status().isOk());
    String firstToken = CHANGE_MAILBOX.get(session.userId());

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"hank.two@example.com\"}"))
        .andExpect(status().isOk());
    String secondToken = CHANGE_MAILBOX.get(session.userId());
    assertThat(secondToken).isNotBlank().isNotEqualTo(firstToken);
    assertThat(users.findById(session.userId()).orElseThrow().getPendingEmail())
        .isEqualTo("hank.two@example.com");

    mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + firstToken + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + secondToken + "\"}"))
        .andExpect(status().isOk());
    assertThat(users.findById(session.userId()).orElseThrow().getEmail())
        .isEqualTo("hank.two@example.com");
  }

  @Test
  void wrongCurrentPasswordIs401AndStagesNothing() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("bob", "bob@example.com");

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wr0ng-password\","
                + "\"newEmail\":\"bob.new@example.com\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    User user = users.findById(session.userId()).orElseThrow();
    assertThat(user.getEmail()).isEqualTo("bob@example.com");
    assertThat(user.getPendingEmail()).isNull();
    assertThat(CHANGE_MAILBOX.get(session.userId())).isNull();
    assertThat(notifications.findByRecipientId(
        session.userId(), PageRequest.of(0, 10))).isEmpty();
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(session.userId())).isEmpty();
    mockMvc.perform(get("/api/auth/me")
            .header("Authorization", "Bearer " + session.accessToken()))
        .andExpect(status().isOk());
  }

  @Test
  void takenAddressIs409AtRequestAndStagesNothing() throws Exception {
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
    assertThat(user.getPendingEmail()).isNull();
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

    assertThat(users.findById(erin.userId()).orElseThrow().getPendingEmail()).isNull();
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
