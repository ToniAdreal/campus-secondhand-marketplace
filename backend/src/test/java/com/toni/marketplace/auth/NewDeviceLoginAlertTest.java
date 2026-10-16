package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * New-device login alert (backlog #178) end-to-end via MockMvc: a
 * successful login whose (client IP, User-Agent) pair matches none of
 * the account's prior SUCCESS login_event rows writes exactly one
 * LOGIN_NEW_DEVICE notification for the account holder; the very first
 * login, a repeat login from a known pair, and every failed login write
 * none. The TOTP-completed login is a completing login too, so it
 * alerts from the authenticate exchange.
 *
 * <p>Each test runs in a rolled-back transaction, but login-event rows
 * are committed by the REQUIRES_NEW recorder and outlive it; assertions
 * are therefore scoped to each test's own user ids, never table counts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NewDeviceLoginAlertTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger();
  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpService totp;

  /** Distinct client IPs per call keep any per-IP bucket out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.178.0." + IP_SEQ.incrementAndGet());
      return request;
    };
  }

  private static RequestPostProcessor fixedIp(String remoteAddr) {
    return request -> {
      request.setRemoteAddr(remoteAddr);
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

  private MvcResult loginAttempt(String username, String password, String userAgent,
                                 String remoteAddr, int expectedStatus) throws Exception {
    var req = post("/api/auth/login")
        .with(fixedIp(remoteAddr))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\""
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

  /** The caller's LOGIN_NEW_DEVICE rows, in row-id order. */
  private List<Notification> alertsOf(long userId) {
    return notifications.findAll().stream()
        .filter(n -> n.getRecipientId() == userId)
        .filter(n -> n.getType() == NotificationType.LOGIN_NEW_DEVICE)
        .sorted(java.util.Comparator.comparing(Notification::getId))
        .toList();
  }

  @Test
  void firstLoginAndRepeatFromSameDeviceNeverAlert() throws Exception {
    register("dev-alice");
    long aliceId = userId("dev-alice");

    // The very first login has no prior pair to be new against.
    loginAttempt("dev-alice", PASSWORD, "device-a", "192.0.2.10", 200);
    assertThat(alertsOf(aliceId)).isEmpty();

    // A repeat from the same (IP, UA) pair matches the first SUCCESS row.
    loginAttempt("dev-alice", PASSWORD, "device-a", "192.0.2.10", 200);
    assertThat(alertsOf(aliceId)).isEmpty();
  }

  @Test
  void newUserAgentAlertsExactlyOnceAndIsExposedByTheReadApi() throws Exception {
    register("dev-bob");
    long bobId = userId("dev-bob");

    loginAttempt("dev-bob", PASSWORD, "device-a", "192.0.2.20", 200);
    MvcResult second = loginAttempt("dev-bob", PASSWORD, "device-b", "192.0.2.20", 200);

    List<Notification> alerts = alertsOf(bobId);
    assertThat(alerts).hasSize(1);
    // The row carries no order/offer and no device data of its own.
    assertThat(alerts.get(0).getOrderId()).isNull();
    assertThat(alerts.get(0).getOfferId()).isNull();

    // A third login from the now-known pair does not alert again.
    loginAttempt("dev-bob", PASSWORD, "device-b", "192.0.2.20", 200);
    assertThat(alertsOf(bobId)).hasSize(1);

    // The read API exposes the alert to the holder with a null order id.
    MvcResult list = mockMvc.perform(get("/api/notifications")
            .header("Authorization", "Bearer " + accessToken(second)))
        .andExpect(status().isOk())
        .andReturn();
    List<java.util.Map<String, Object>> content =
        JsonPath.read(list.getResponse().getContentAsString(), "$.data.content");
    assertThat(content).anySatisfy(row -> {
      assertThat(row.get("type")).isEqualTo("LOGIN_NEW_DEVICE");
      assertThat(row.get("orderId")).isNull();
    });
  }

  @Test
  void newIpWithSameUserAgentAlerts() throws Exception {
    register("dev-carol");
    long carolId = userId("dev-carol");

    loginAttempt("dev-carol", PASSWORD, "device-a", "192.0.2.30", 200);
    loginAttempt("dev-carol", PASSWORD, "device-a", "198.51.100.7", 200);

    assertThat(alertsOf(carolId)).hasSize(1);
  }

  @Test
  void failedLoginsFromANewDeviceNeverAlert() throws Exception {
    register("dev-dave");
    long daveId = userId("dev-dave");

    loginAttempt("dev-dave", PASSWORD, "device-a", "192.0.2.40", 200);
    // A bad password from a never-seen pair is history evidence, not an alert.
    loginAttempt("dev-dave", "wrong-password", "device-b", "198.51.100.9", 401);

    assertThat(alertsOf(daveId)).isEmpty();
  }

  @Test
  void totpCompletedLoginFromANewDeviceAlerts() throws Exception {
    String access = register("dev-erin");
    String secret = enrollTotp(access);
    long erinId = userId("dev-erin");

    // First completed login (device-a): nothing prior to compare against.
    completeTotpLogin("dev-erin", secret, "device-a", "192.0.2.50");
    assertThat(alertsOf(erinId)).isEmpty();

    // Second completed login from a new device alerts exactly once.
    completeTotpLogin("dev-erin", secret, "device-b", "192.0.2.50");
    assertThat(alertsOf(erinId)).hasSize(1);

    completeTotpLogin("dev-erin", secret, "device-b", "192.0.2.50");
    assertThat(alertsOf(erinId)).hasSize(1);
  }

  /** Runs the password stage (202 challenge) and exchanges a valid code, both from one device. */
  private void completeTotpLogin(String username, String secret, String userAgent,
                                 String remoteAddr) throws Exception {
    MvcResult challenged = loginAttempt(username, PASSWORD, userAgent, remoteAddr, 202);
    String challenge = JsonPath.read(challenged.getResponse().getContentAsString(),
        "$.data.challenge");
    mockMvc.perform(post("/api/auth/2fa/authenticate")
            .with(fixedIp(remoteAddr))
            .header("User-Agent", userAgent)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challenge + "\",\"code\":\""
                + currentCode(secret) + "\"}"))
        .andExpect(status().isOk());
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
}
