package com.toni.marketplace.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Per-type notification preferences (backlog #216): the catalogue is
 * default-on for every type, a muted type writes no row and fires no
 * SSE event at the single {@code NotificationService} record seam,
 * unmuted types are unaffected, and security types can never be muted
 * (422 at the API, delivered even over a hand-written muted row).
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link NotificationTest}: preference writes and notification records
 * must be committed by the calls themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationPreferenceTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private NotificationPreferenceRepository preferences;

  @Autowired
  private NotificationService notificationService;

  @Autowired
  private NotificationStreamService streamService;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() {
    preferences.deleteAll();
    notifications.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "pref-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> catalogue(User user) throws Exception {
    String body = mockMvc.perform(get("/api/notifications/preferences")
            .header("Authorization", "Bearer " + token(user)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return (List<Map<String, Object>>) JsonPath.read(body, "$.data");
  }

  private Map<String, Object> catalogueRow(User user, String type) throws Exception {
    return catalogue(user).stream()
        .filter(row -> type.equals(row.get("type")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no catalogue row for " + type));
  }

  private void putPreference(User user, String type, boolean enabled, int expectedStatus)
      throws Exception {
    mockMvc.perform(put("/api/notifications/preferences/" + type)
            .header("Authorization", "Bearer " + token(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":" + enabled + "}"))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void catalogueDefaultsEveryTypeOnForANewUser() throws Exception {
    User user = newUser();

    List<Map<String, Object>> rows = catalogue(user);

    assertThat(rows).hasSize(NotificationType.values().length);
    assertThat(rows).allSatisfy(row -> assertThat(row.get("enabled")).isEqualTo(true));
    Map<String, Object> security = catalogueRow(user, "LOGIN_NEW_DEVICE");
    assertThat(security.get("security")).isEqualTo(true);
    assertThat(security.get("label")).isEqualTo("New-device sign-in alert");
    Map<String, Object> mutable = catalogueRow(user, "SAVED_SEARCH_MATCH");
    assertThat(mutable.get("security")).isEqualTo(false);
    // No toggles yet: the default-on state is the ABSENCE of rows.
    assertThat(preferences.findByUserId(user.getId())).isEmpty();
  }

  @Test
  void preferencesRequireAuthentication() throws Exception {
    mockMvc.perform(get("/api/notifications/preferences"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(put("/api/notifications/preferences/ORDER_PAID")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void mutedTypeWritesNoRowAndFiresNoSseEventUnmutedTypeUnaffected() throws Exception {
    User user = newUser();
    putPreference(user, "ORDER_PAID", false, 200);
    assertThat(catalogueRow(user, "ORDER_PAID").get("enabled")).isEqualTo(false);

    // Open the live stream the way the SPA does (one-time ticket).
    String ticketBody = mockMvc.perform(post("/api/notifications/stream-ticket")
            .header("Authorization", "Bearer " + token(user)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String ticket = JsonPath.read(ticketBody, "$.data.ticket");
    MvcResult stream = mockMvc.perform(get("/api/notifications/stream").param("ticket", ticket))
        .andExpect(request().asyncStarted())
        .andReturn();

    // Direct service call: no ambient transaction, so a publish would
    // fire immediately — a muted type must publish nothing at all.
    notificationService.record(user.getId(), NotificationType.ORDER_PAID, 4242L);
    assertThat(notifications.findAll()).isEmpty();

    assertThat(streamService.completeStreamsForUser(user.getId())).isEqualTo(1);
    String body = mockMvc.perform(asyncDispatch(stream))
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("event:notification");

    // The seam is selective: an unmuted type for the same user records.
    notificationService.record(user.getId(), NotificationType.ORDER_COMPLETED, 4242L);
    assertThat(notifications.findAll())
        .extracting(Notification::getType)
        .containsExactly(NotificationType.ORDER_COMPLETED);
  }

  @Test
  void unmutingRestoresDeliveryAndTogglesUpsertOneRow() throws Exception {
    User user = newUser();
    putPreference(user, "ORDER_PAID", false, 200);
    putPreference(user, "ORDER_PAID", false, 200);
    putPreference(user, "ORDER_PAID", true, 200);

    assertThat(preferences.findByUserId(user.getId())).hasSize(1);
    notificationService.record(user.getId(), NotificationType.ORDER_PAID, 4242L);
    assertThat(notifications.findAll()).hasSize(1);
  }

  @Test
  void securityTypeMuteIs422AndDeliveryIgnoresAHandWrittenMutedRow() throws Exception {
    User user = newUser();

    putPreference(user, "LOGIN_NEW_DEVICE", false, 422);
    // Enabling a security type is a no-op success — it was never off.
    putPreference(user, "LOGIN_NEW_DEVICE", true, 200);

    // Defence in depth: even a muted row written behind the API's back
    // neither silences the alert nor shows as muted in the catalogue.
    preferences.save(new NotificationPreference(
        user.getId(), NotificationType.LOGIN_NEW_DEVICE, false, Instant.now()));
    assertThat(catalogueRow(user, "LOGIN_NEW_DEVICE").get("enabled")).isEqualTo(true);
    notificationService.record(user.getId(), NotificationType.LOGIN_NEW_DEVICE, null);
    assertThat(notifications.findAll())
        .extracting(Notification::getType)
        .containsExactly(NotificationType.LOGIN_NEW_DEVICE);
  }

  @Test
  void unknownTypeAndMissingFlagAre400() throws Exception {
    User user = newUser();

    putPreference(user, "NOT_A_TYPE", false, 400);
    mockMvc.perform(put("/api/notifications/preferences/ORDER_PAID")
            .header("Authorization", "Bearer " + token(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
    assertThat(preferences.findByUserId(user.getId())).isEmpty();
  }

  @Test
  void preferencesAreScopedToTheirOwner() throws Exception {
    User muted = newUser();
    User other = newUser();
    putPreference(muted, "ORDER_PAID", false, 200);

    assertThat(catalogueRow(other, "ORDER_PAID").get("enabled")).isEqualTo(true);
    notificationService.record(other.getId(), NotificationType.ORDER_PAID, 4242L);
    assertThat(notifications.findAll()).hasSize(1);
  }
}
