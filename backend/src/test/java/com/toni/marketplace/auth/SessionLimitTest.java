package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrent-session limit with oldest-session eviction (backlog #191)
 * end-to-end via MockMvc, with the cap pinned to 3 for this class
 * ({@code app.auth.max-sessions-per-user=3}): a login under the cap
 * evicts nothing, a login over the cap evicts exactly the oldest live
 * family (its refresh cookie 401s, the newer sessions keep rotating),
 * each eviction records one SESSION_EVICTED notification for the
 * account holder, other users are untouched, and a TOTP-completed
 * login counts toward the cap exactly like a password login. The
 * registration session counts as a family like any other — it is
 * usually the oldest, so it is the first eviction candidate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.auth.max-sessions-per-user=3")
class SessionLimitTest {

  private static final String PASSWORD = "s3cret-pass1";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private UserRepository users;

  @Autowired
  private TotpService totp;

  @Autowired
  private SessionLimitProperties sessionLimit;

  @Test
  void capPropertyIsBoundFromConfiguration() {
    assertThat(sessionLimit.getMaxSessionsPerUser()).isEqualTo(3);
  }

  @Test
  void loginUnderCapEvictsNothingAndEverySessionKeepsRotating() throws Exception {
    Cookie registered = register("cap-una");
    Cookie first = login("cap-una");
    MvcResult second = loginResult("cap-una");

    assertThat(sessionCount(accessToken(second))).isEqualTo(3);
    assertThat(evictionsOf(userId("cap-una"))).isEmpty();

    // Every family's refresh still rotates — nothing was signed out.
    refresh(registered, 200);
    refresh(first, 200);
    refresh(refreshCookie(second), 200);
  }

  @Test
  void loginOverCapEvictsExactlyTheOldestFamilyAndNotifiesOnce() throws Exception {
    Cookie registered = register("cap-ulla");
    Cookie first = login("cap-ulla");
    Cookie second = login("cap-ulla");
    // The fourth live family (cap is 3) evicts the oldest: registration's.
    MvcResult fourth = loginResult("cap-ulla");

    assertThat(sessionCount(accessToken(fourth))).isEqualTo(3);
    List<Notification> evictions = evictionsOf(userId("cap-ulla"));
    assertThat(evictions).hasSize(1);
    // The row carries no order and no device data of its own.
    assertThat(evictions.get(0).getOrderId()).isNull();
    assertThat(evictions.get(0).getOfferId()).isNull();

    // The evicted family's refresh cookie is dead…
    refresh(registered, 401);
    // …while the surviving sessions keep rotating.
    refresh(first, 200);
    refresh(second, 200);
    refresh(refreshCookie(fourth), 200);
  }

  @Test
  void eachFurtherLoginEvictsTheNextOldestFamily() throws Exception {
    Cookie registered = register("cap-vera");
    Cookie first = login("cap-vera");
    login("cap-vera");
    login("cap-vera");
    MvcResult fifth = loginResult("cap-vera");

    // Five families founded, cap 3: exactly the two oldest were evicted.
    assertThat(sessionCount(accessToken(fifth))).isEqualTo(3);
    assertThat(evictionsOf(userId("cap-vera"))).hasSize(2);
    refresh(registered, 401);
    refresh(first, 401);
    refresh(refreshCookie(fifth), 200);
  }

  @Test
  void evictionNeverTouchesAnotherUsersSessions() throws Exception {
    register("cap-wanda");
    Cookie bobs = register("cap-xander");
    long bobId = userId("cap-xander");

    login("cap-wanda");
    login("cap-wanda");
    login("cap-wanda");

    assertThat(evictionsOf(userId("cap-wanda"))).hasSize(1);
    assertThat(evictionsOf(bobId)).isEmpty();
    refresh(bobs, 200);
  }

  @Test
  void totpCompletedLoginsCountTowardTheCap() throws Exception {
    MvcResult registration = registerResult("cap-yara");
    Cookie registered = refreshCookie(registration);
    String access = accessToken(registration);
    String secret = setupTotp(access);
    enableTotp(access, secret);
    long yaraId = userId("cap-yara");

    authenticateTotp("cap-yara", secret);
    authenticateTotp("cap-yara", secret);
    // Registration + two TOTP sessions = cap; no eviction yet.
    assertThat(evictionsOf(yaraId)).isEmpty();
    MvcResult third = authenticateTotp("cap-yara", secret);

    // The third TOTP-completed login founds the fourth family and
    // evicts the oldest — the registration session.
    assertThat(sessionCount(accessToken(third))).isEqualTo(3);
    assertThat(evictionsOf(yaraId)).hasSize(1);
    refresh(registered, 401);
    refresh(refreshCookie(third), 200);
  }

  // -- helpers -------------------------------------------------------

  private long userId(String username) {
    return users.findByUsernameIgnoreCase(username).orElseThrow().getId();
  }

  private List<Notification> evictionsOf(long userId) {
    return notifications.findAll().stream()
        .filter(n -> n.getRecipientId() == userId)
        .filter(n -> n.getType() == NotificationType.SESSION_EVICTED)
        .sorted(Comparator.comparing(Notification::getId))
        .toList();
  }

  private int sessionCount(String accessToken) throws Exception {
    MvcResult listed = mockMvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();
    List<?> sessions = JsonPath.read(listed.getResponse().getContentAsString(), "$.data");
    return sessions.size();
  }

  private void refresh(Cookie cookie, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
        .andExpect(status().is(expectedStatus));
  }

  private Cookie register(String username) throws Exception {
    return refreshCookie(registerResult(username));
  }

  private MvcResult registerResult(String username) throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private Cookie login(String username) throws Exception {
    return refreshCookie(loginResult(username));
  }

  private MvcResult loginResult(String username) throws Exception {
    return mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\""
                + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String setupTotp(String accessToken) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.secret");
  }

  private void enableTotp(String accessToken, String secret) throws Exception {
    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk());
  }

  private MvcResult authenticateTotp(String username, String secret) throws Exception {
    MvcResult challenge = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"" + username + "\",\"password\":\""
                + PASSWORD + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    String challengeToken =
        JsonPath.read(challenge.getResponse().getContentAsString(), "$.data.challenge");
    return mockMvc.perform(post("/api/auth/2fa/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"challenge\":\"" + challengeToken + "\",\"code\":\""
                + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private Cookie refreshCookie(MvcResult result) {
    return result.getResponse().getCookie("refresh_token");
  }
}
