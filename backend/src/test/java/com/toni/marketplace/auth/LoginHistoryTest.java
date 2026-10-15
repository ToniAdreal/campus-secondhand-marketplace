package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user login history (backlog #171) end-to-end via MockMvc: every
 * login outcome lands in {@code login_event} through the REQUIRES_NEW
 * {@link LoginHistoryRecorder} — so a row survives the 401/423 its own
 * attempt answers — and {@code GET /api/auth/login-history} returns only
 * the caller's own rows, newest first. Assertions read the repository
 * directly (the committed recorder rows) as well as the API.
 *
 * <p>Each test runs in a rolled-back transaction, but recorder rows are
 * committed by design and outlive it; every assertion below is therefore
 * scoped to its own test's user ids / identifiers, never to table-wide
 * counts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginHistoryTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger();
  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private LoginEventRepository loginEvents;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpService totp;

  /** Distinct client IPs per call keep any per-IP bucket out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.171.0." + IP_SEQ.incrementAndGet());
      return request;
    };
  }

  private String register(String username) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private MvcResult loginAttempt(String usernameOrEmail, String password, String userAgent,
                                 int expectedStatus) throws Exception {
    return loginAttempt(usernameOrEmail, password, userAgent, null, expectedStatus);
  }

  /** Login from a fixed client IP (a null {@code remoteAddr} keeps a fresh unique one). */
  private MvcResult loginAttempt(String usernameOrEmail, String password, String userAgent,
                                 String remoteAddr, int expectedStatus) throws Exception {
    RequestPostProcessor ip = remoteAddr == null
        ? uniqueIp()
        : request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
          };
    var req = post("/api/auth/login")
        .with(ip)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"usernameOrEmail\":\"" + usernameOrEmail + "\",\"password\":\""
            + password + "\"}");
    if (userAgent != null) {
      req.header("User-Agent", userAgent);
    }
    return mockMvc.perform(req).andExpect(status().is(expectedStatus)).andReturn();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private long userId(String username) {
    return users.findByUsernameIgnoreCase(username).orElseThrow().getId();
  }

  /** All recorded outcomes for one user, in row-id (recording) order. */
  private List<LoginOutcome> outcomesOf(long userId) {
    return loginEvents.findAll().stream()
        .filter(row -> java.util.Objects.equals(row.getUserId(), userId))
        .sorted(java.util.Comparator.comparing(LoginEvent::getId))
        .map(LoginEvent::getOutcome)
        .toList();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> historyOf(String accessToken) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/auth/login-history")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content");
  }

  @Test
  void successfulLoginIsRecordedAndReturnedByTheHistoryApi() throws Exception {
    String access = register("hist-alice");
    MvcResult login = loginAttempt("hist-alice", PASSWORD, "device-a", "192.0.2.55", 200);
    String loginAccess = accessToken(login);
    assertThat(loginAccess).isNotBlank();
    assertThat(access).isNotBlank();

    long aliceId = userId("hist-alice");
    List<LoginEvent> rows = loginEvents
        .findByUserIdOrderByCreatedAtDesc(aliceId, PageRequest.of(0, 20)).getContent();
    assertThat(rows).hasSize(1);
    LoginEvent row = rows.get(0);
    assertThat(row.getOutcome()).isEqualTo(LoginOutcome.SUCCESS);
    // The identifier is recorded exactly as typed, and the device info
    // comes from the request: the fixed client IP this login was sent
    // from, resolved through the #104 path (no proxy trusted here, so
    // the remote address itself).
    assertThat(row.getIdentifier()).isEqualTo("hist-alice");
    assertThat(row.getUserAgent()).isEqualTo("device-a");
    assertThat(row.getIpAddress()).isEqualTo("192.0.2.55");
    assertThat(row.getCreatedAt()).isNotNull();

    List<Map<String, Object>> history = historyOf(loginAccess);
    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("outcome")).isEqualTo("SUCCESS");
    assertThat(history.get(0).get("identifier")).isEqualTo("hist-alice");
    assertThat(history.get(0).get("userAgent")).isEqualTo("device-a");
    assertThat(history.get(0).get("ipAddress")).isEqualTo("192.0.2.55");
  }

  @Test
  void badPasswordRowSurvivesThe401() throws Exception {
    register("hist-bob");
    loginAttempt("hist-bob", "wrong-password", "device-b", 401);

    // The 401's transaction rolled back — the row is still here, which
    // is only possible because the recorder commits in REQUIRES_NEW.
    long bobId = userId("hist-bob");
    assertThat(outcomesOf(bobId)).containsExactly(LoginOutcome.BAD_PASSWORD);

    // And it shows in the history a later successful login reads back.
    MvcResult login = loginAttempt("hist-bob", PASSWORD, "device-b", 200);
    List<Map<String, Object>> history = historyOf(accessToken(login));
    assertThat(history).extracting(row -> row.get("outcome"))
        .containsExactly("SUCCESS", "BAD_PASSWORD");
  }

  @Test
  void unknownUserIsRecordedWithNullUserAndNeverExposed() throws Exception {
    loginAttempt("hist-ghost", "wrong-password", null, 401);

    List<LoginEvent> ghostRows = loginEvents.findAll().stream()
        .filter(row -> "hist-ghost".equals(row.getIdentifier()))
        .toList();
    assertThat(ghostRows).hasSize(1);
    assertThat(ghostRows.get(0).getOutcome()).isEqualTo(LoginOutcome.UNKNOWN_USER);
    assertThat(ghostRows.get(0).getUserId()).isNull();

    // No user's history ever contains the ownerless row.
    register("hist-carol");
    MvcResult login = loginAttempt("hist-carol", PASSWORD, null, 200);
    List<Map<String, Object>> history = historyOf(accessToken(login));
    assertThat(history).hasSize(1);
    assertThat(history).extracting(row -> row.get("identifier"))
        .containsExactly("hist-carol");
  }

  @Test
  void lockedFlowRecordsBadPasswordsThenLockedRows() throws Exception {
    register("hist-dave");
    long daveId = userId("hist-dave");

    // Default lockout: 5 consecutive failures lock the account.
    for (int i = 0; i < 4; i++) {
      loginAttempt("hist-dave", "wrong-password", null, 401);
    }
    // The fifth failure trips the lock and itself answers 423.
    loginAttempt("hist-dave", "wrong-password", null, 423);
    // Even the correct password is refused while the lock is active.
    loginAttempt("hist-dave", PASSWORD, null, 423);

    assertThat(outcomesOf(daveId)).containsExactly(
        LoginOutcome.BAD_PASSWORD, LoginOutcome.BAD_PASSWORD,
        LoginOutcome.BAD_PASSWORD, LoginOutcome.BAD_PASSWORD,
        LoginOutcome.LOCKED, LoginOutcome.LOCKED);
  }

  @Test
  void totpFlowRecordsRequiredFailedAndSuccessRows() throws Exception {
    String access = register("hist-erin");
    String secret = enrollTotp(access);

    MvcResult challenged = loginAttempt("hist-erin", PASSWORD, null, 202);
    String challenge = JsonPath.read(challenged.getResponse().getContentAsString(),
        "$.data.challenge");

    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\""
                + wrongCode(secret) + "\"}"))
        .andExpect(status().isUnauthorized());

    // A wrong code does not consume the challenge: the right code under
    // the same challenge still completes the login.
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\""
                + currentCode(secret) + "\"}"))
        .andExpect(status().isOk());

    long erinId = userId("hist-erin");
    assertThat(outcomesOf(erinId)).containsExactly(
        LoginOutcome.TOTP_REQUIRED, LoginOutcome.TOTP_FAILED, LoginOutcome.SUCCESS);
    // Second-factor rows carry the account's username as the identifier
    // (the challenge holds no typed identifier).
    List<LoginEvent> rows = loginEvents
        .findByUserIdOrderByCreatedAtDesc(erinId, PageRequest.of(0, 20)).getContent();
    assertThat(rows).extracting(LoginEvent::getIdentifier)
        .containsOnly("hist-erin");
  }

  @Test
  void historyIsIsolatedPerUser() throws Exception {
    register("hist-frank");
    register("hist-grace");
    loginAttempt("hist-frank", "wrong-password", null, 401);
    MvcResult frankLogin = loginAttempt("hist-frank", PASSWORD, null, 200);
    MvcResult graceLogin = loginAttempt("hist-grace", PASSWORD, null, 200);

    List<Map<String, Object>> frankHistory = historyOf(accessToken(frankLogin));
    assertThat(frankHistory).extracting(row -> row.get("outcome"))
        .containsExactly("SUCCESS", "BAD_PASSWORD");
    assertThat(frankHistory).extracting(row -> row.get("identifier"))
        .containsOnly("hist-frank");

    List<Map<String, Object>> graceHistory = historyOf(accessToken(graceLogin));
    assertThat(graceHistory).extracting(row -> row.get("outcome"))
        .containsExactly("SUCCESS");
    assertThat(graceHistory).extracting(row -> row.get("identifier"))
        .containsOnly("hist-grace");
  }

  @Test
  void anonymousHistoryGetIs401() throws Exception {
    mockMvc.perform(get("/api/auth/login-history"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void historyIsNewestFirst() throws Exception {
    register("hist-heidi");
    loginAttempt("hist-heidi", "wrong-password", null, 401);
    loginAttempt("hist-heidi", PASSWORD, null, 200);
    MvcResult second = loginAttempt("hist-heidi", PASSWORD, null, 200);

    List<Map<String, Object>> history = historyOf(accessToken(second));
    assertThat(history).extracting(row -> row.get("outcome"))
        .containsExactly("SUCCESS", "SUCCESS", "BAD_PASSWORD");
  }

  /** Enrolls the caller in TOTP; returns the plaintext Base32 secret. */
  private String enrollTotp(String access) throws Exception {
    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    String secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + currentCode(secret) + "\"}"))
        .andExpect(status().isOk());
    return secret;
  }

  private String currentCode(String plainSecret) {
    return totp.codeAt(plainSecret, Instant.now());
  }

  /** A 6-digit code that verifies in no accepted time step (and is no recovery code). */
  private String wrongCode(String plainSecret) {
    Set<String> valid = Set.of(
        totp.codeAt(plainSecret, Instant.now().minusSeconds(30)),
        totp.codeAt(plainSecret, Instant.now()),
        totp.codeAt(plainSecret, Instant.now().plusSeconds(30)));
    for (String candidate : List.of("000000", "111111", "222222", "333333", "444444")) {
      if (!valid.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("no wrong code candidate");
  }
}
