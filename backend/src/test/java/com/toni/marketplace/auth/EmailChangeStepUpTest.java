package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step-up freshness gate on the email-change REQUEST stage (backlog
 * #272), mirroring {@link UsernameChangeTest}'s #261 coverage: before
 * #272 the request checked only the current password, so a
 * password-only compromise could stage a change to an
 * attacker-controlled inbox and confirm it from the mailed token —
 * while the far less sensitive username change already demanded a
 * fresh proof.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>a stale token's request answers the dedicated 403 step-up
 *       envelope and stages nothing — and when a change is already
 *       pending, the refused request leaves that pending address and
 *       its mailed token untouched;</li>
 *   <li>a re-proofed token stages exactly as before;</li>
 *   <li>for a TOTP-enrolled account the request body's password alone
 *       is not a proof: a stale token is refused even with the right
 *       password, a password-only step-up answers 401, and only a
 *       password-plus-code step-up lets the retry through;</li>
 *   <li>the confirm step is unchanged: an unknown token still answers
 *       401 and the staged token still performs the switch.</li>
 * </ul>
 *
 * <p>The application {@code Clock} bean is replaced with a
 * {@link MutableClock} so proof aging is deterministic, the StepUpTest
 * pattern. The email-verification gate is explicitly ON (the shared
 * test profile turns it off) and both mail seams are capturing
 * senders, like {@link EmailChangeTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users it creates
@TestPropertySource(properties = "app.auth.email-verification.enabled=true")
class EmailChangeStepUpTest {

  /** Captures the raw verification token per user id — the registration mailbox. */
  static final Map<Long, String> MAILBOX = new ConcurrentHashMap<>();

  /** Captures the raw email-change token per user id — the NEW-address mailbox. */
  static final Map<Long, String> CHANGE_MAILBOX = new ConcurrentHashMap<>();

  @TestConfiguration
  static class TestBeans {
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

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MutableClock clock;

  @Autowired
  private TotpService totp;

  @Autowired
  private UserRepository users;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private NotificationRepository notifications;

  @BeforeEach
  void clearMailboxes() {
    MAILBOX.clear();
    CHANGE_MAILBOX.clear();
  }

  @Test
  void staleTokenAnswersStepUp403AndLeavesPriorStagingUntouched() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("alice", "alice@example.com");
    long userId = session.userId();

    // A fresh request stages the first change.
    requestEmailChange(session.accessToken(), "alice.one@example.com", 200);
    String firstToken = CHANGE_MAILBOX.get(userId);
    assertThat(firstToken).isNotBlank();
    assertThat(users.findById(userId).orElseThrow().getPendingEmail())
        .isEqualTo("alice.one@example.com");

    // Past the 10-minute proof freshness window.
    clock.advance(Duration.ofMinutes(11));

    // The refused request carries the RIGHT password — the gate, not
    // the password check, must refuse it.
    MvcResult refused = mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"alice.two@example.com\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andReturn();
    assertThat(JsonPath.<String>read(
        refused.getResponse().getContentAsString(), "$.message"))
        .contains("re-authentication required");

    // Nothing moved: the first pending address and its mailed token
    // are untouched, no second alert was recorded, no audit row exists.
    User user = users.findById(userId).orElseThrow();
    assertThat(user.getPendingEmail()).isEqualTo("alice.one@example.com");
    assertThat(user.getEmail()).isEqualTo("alice@example.com");
    assertThat(CHANGE_MAILBOX.get(userId)).isEqualTo(firstToken);
    assertThat(notifications.findByRecipientId(userId, PageRequest.of(0, 10)).getContent())
        .extracting(row -> row.getType())
        .containsExactly(NotificationType.EMAIL_CHANGE_REQUESTED);
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId)).isEmpty();
  }

  @Test
  void staleTokenWithNoPriorStagingAnswers403AndStagesNothing() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("bob", "bob@example.com");
    long userId = session.userId();
    clock.advance(Duration.ofMinutes(11));

    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"bob.new@example.com\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    User user = users.findById(userId).orElseThrow();
    assertThat(user.getPendingEmail()).isNull();
    assertThat(CHANGE_MAILBOX.get(userId)).isNull();
    assertThat(notifications.findByRecipientId(
        userId, PageRequest.of(0, 10))).isEmpty();
    assertThat(auditLog.findByActorUserIdOrderByIdAsc(userId)).isEmpty();
  }

  @Test
  void stepUpReproofThenRequestStages() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("carol", "carol@example.com");
    long userId = session.userId();
    clock.advance(Duration.ofMinutes(11));
    requestEmailChange(session.accessToken(), "carol.new@example.com", 403);

    // Re-prove with the current password (the step-up modal's path),
    // then retry the same request with the fresh token.
    MvcResult reproof = mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + session.accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String freshAccess = JsonPath.read(
        reproof.getResponse().getContentAsString(), "$.data.accessToken");
    requestEmailChange(freshAccess, "carol.new@example.com", 200);

    assertThat(users.findById(userId).orElseThrow().getPendingEmail())
        .isEqualTo("carol.new@example.com");
    assertThat(CHANGE_MAILBOX.get(userId)).isNotBlank();
    assertThat(notifications.findByRecipientId(userId, PageRequest.of(0, 10)).getContent())
        .extracting(row -> row.getType())
        .containsExactly(NotificationType.EMAIL_CHANGE_REQUESTED);
  }

  @Test
  void totpAccountPasswordOnlyProofIsRefused() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("dave", "dave@example.com");
    long userId = session.userId();

    // Enroll TOTP, then complete a second-factor login: the fresh
    // token's proof comes from the TOTP exchange, not the password.
    String secret = enrollTotp(session.accessToken());
    MvcResult challenged = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"dave@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    String challenge = JsonPath.read(
        challenged.getResponse().getContentAsString(), "$.data.challenge");
    MvcResult completed = mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\""
                + totp.codeAt(secret, clock.instant()) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String access = JsonPath.read(
        completed.getResponse().getContentAsString(), "$.data.accessToken");

    clock.advance(Duration.ofMinutes(11));

    // The request body's correct password is not a step-up proof.
    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"dave.new@example.com\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    assertThat(users.findById(userId).orElseThrow().getPendingEmail()).isNull();
    assertThat(CHANGE_MAILBOX.get(userId)).isNull();

    // A password-only step-up is refused for the TOTP account (the
    // code is part of the proof); password-plus-code re-proves.
    mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isUnauthorized());
    MvcResult reproof = mockMvc.perform(post("/api/auth/step-up")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"code\":\""
                + totp.codeAt(secret, clock.instant()) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String freshAccess = JsonPath.read(
        reproof.getResponse().getContentAsString(), "$.data.accessToken");
    requestEmailChange(freshAccess, "dave.new@example.com", 200);
    assertThat(users.findById(userId).orElseThrow().getPendingEmail())
        .isEqualTo("dave.new@example.com");
    assertThat(CHANGE_MAILBOX.get(userId)).isNotBlank();
  }

  @Test
  void confirmFlowUnchangedAfterGate() throws Exception {
    VerifiedSession session = registerVerifyAndLogin("erin", "erin@example.com");
    long userId = session.userId();
    requestEmailChange(session.accessToken(), "erin.new@example.com", 200);
    String token = CHANGE_MAILBOX.get(userId);
    assertThat(token).isNotBlank();

    // Unknown tokens still answer the identical 401, and the staged
    // token still performs the switch.
    mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"no-such-token\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(post("/api/auth/email-change/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk());
    User user = users.findById(userId).orElseThrow();
    assertThat(user.getEmail()).isEqualTo("erin.new@example.com");
    assertThat(user.getPendingEmail()).isNull();
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

  private void requestEmailChange(String accessToken, String newEmail, int expectedStatus)
      throws Exception {
    mockMvc.perform(post("/api/auth/email-change")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + PASSWORD + "\","
                + "\"newEmail\":\"" + newEmail + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  /** Enrolls TOTP on the caller's account; returns the shared secret. */
  private String enrollTotp(String access) throws Exception {
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    String secret = JsonPath.read(
        setup.getResponse().getContentAsString(), "$.data.secret");
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, clock.instant()) + "\"}"))
        .andExpect(status().isOk());
    return secret;
  }
}
