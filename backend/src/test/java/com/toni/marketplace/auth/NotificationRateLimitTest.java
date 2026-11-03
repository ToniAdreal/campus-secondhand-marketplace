package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.item.FavoriteRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationPreferenceRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of the notification writes through
 * {@code AuthRateLimitFilter} (backlog #309): {@code POST
 * /api/notifications/stream-ticket} (the one-time SSE ticket),
 * {@code PUT /api/notifications/preferences/{type}} (one settings
 * toggle), {@code POST /api/notifications/{id}/read} (mark one read),
 * {@code POST /api/notifications/read-all} (mark all read) and
 * {@code DELETE /api/notifications/{id}} (delete one) share one
 * per-<em>user</em> bucket (production default 20/minute, like the
 * #282/#308 buckets — pinned to 3/minute here so the burst arithmetic
 * stays small), keyed on the JWT principal id like the message/report
 * buckets, with the per-IP fallback for a missing/invalid token. The
 * bucket never resets on success — a minted ticket, a toggled
 * preference or a cleared notification must not refill the allowance —
 * and it is deliberately separate from the #308 social-graph bucket
 * (saving, following and blocking stay there). The SSE stream itself
 * ({@code GET /api/notifications/stream}) is gated by its one-time
 * ticket, not by this filter, and the list, preferences-catalogue and
 * unread-count GETs are reads and stay unthrottled.
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses fresh users (per-user keys), mirroring
 * {@code SocialGraphRateLimitTest}. Notifications are seeded
 * repository-direct; the read, read-all and delete writes are
 * idempotent 200s on their own rows, and the ticket and preference
 * writes are repeatable, so a burst can repeat one shape without
 * tripping a state guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the notification surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.notification-attempts-per-minute=3",
    // Pin the neighbouring #308 bucket to the same small size so the
    // independence tests can exhaust it without a long burst.
    "app.auth.rate-limit.social-graph-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions.
    "app.auth.login-lockout.enabled=false"})
class NotificationRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private NotificationRepository notifications;
  @Autowired private NotificationPreferenceRepository preferences;
  @Autowired private ItemRepository items;
  @Autowired private FavoriteRepository favorites;

  private User freshUser() {
    String tag = "notif-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String bearer(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private static RequestPostProcessor fromIp(String clientIp) {
    return request -> {
      request.setRemoteAddr(clientIp);
      return request;
    };
  }

  private Notification seedNotification(User recipient) {
    return notifications.save(new Notification(recipient.getId(), NotificationType.ORDER_PAID, null));
  }

  private void ticketOnce(String token, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/notifications/stream-ticket")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void preferenceOnce(String token, int expectedStatus) throws Exception {
    mockMvc.perform(put("/api/notifications/preferences/ORDER_PAID")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().is(expectedStatus));
  }

  private void readOnce(String token, long notificationId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/notifications/{id}/read", notificationId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void readAllOnce(String token, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/notifications/read-all")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void deleteOnce(String token, long notificationId, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/notifications/{id}", notificationId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void favoriteOnce(String token, long itemId, int expectedStatus) throws Exception {
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", itemId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateStreamTicketPasses() throws Exception {
    User user = freshUser();
    mockMvc.perform(post("/api/notifications/stream-ticket")
            .header("Authorization", "Bearer " + bearer(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ticket").isNotEmpty());
  }

  @Test
  void singleLegitimatePreferencePasses() throws Exception {
    User user = freshUser();
    preferenceOnce(bearer(user), 200);
    assertThat(preferences.findByUserIdAndType(user.getId(), NotificationType.ORDER_PAID))
        .isPresent();
  }

  @Test
  void singleLegitimateReadPasses() throws Exception {
    User user = freshUser();
    Notification notification = seedNotification(user);
    readOnce(bearer(user), notification.getId(), 200);
    assertThat(notifications.findById(notification.getId()).orElseThrow().getReadAt())
        .isNotNull();
  }

  @Test
  void singleLegitimateReadAllPasses() throws Exception {
    User user = freshUser();
    seedNotification(user);
    seedNotification(user);
    mockMvc.perform(post("/api/notifications/read-all")
            .header("Authorization", "Bearer " + bearer(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(2));
  }

  @Test
  void singleLegitimateDeletePasses() throws Exception {
    User user = freshUser();
    Notification notification = seedNotification(user);
    deleteOnce(bearer(user), notification.getId(), 200);
    assertThat(notifications.existsById(notification.getId())).isFalse();
  }

  @Test
  void notificationBurstIs429WithEnvelope() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    ticketOnce(token, 200);
    ticketOnce(token, 200);
    ticketOnce(token, 200);
    // The bucket is empty: the fourth ticket is throttled before the
    // controller ever sees it, so no ticket is minted.
    mockMvc.perform(post("/api/notifications/stream-ticket")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        });
  }

  @Test
  void notificationBucketIsSharedAcrossAllFiveShapes() throws Exception {
    // Exhausting the bucket with tickets leaves a preference toggle
    // and a mark-read throttled too — one bucket for the whole
    // notification surface, not one per endpoint. The filter
    // short-circuits before the controllers, so the toggle writes no
    // preference row and the notification stays unread.
    User user = freshUser();
    String token = bearer(user);
    Notification notification = seedNotification(user);
    for (int i = 0; i < 3; i++) {
      ticketOnce(token, 200);
    }
    preferenceOnce(token, 429);
    readOnce(token, notification.getId(), 429);
    assertThat(preferences.findByUserIdAndType(user.getId(), NotificationType.ORDER_PAID))
        .isEmpty();
    assertThat(notifications.findById(notification.getId()).orElseThrow().getReadAt())
        .isNull();
  }

  @Test
  void notificationBucketIsPerUser() throws Exception {
    User first = freshUser();
    String firstToken = bearer(first);
    for (int i = 0; i < 3; i++) {
      ticketOnce(firstToken, 200);
    }
    ticketOnce(firstToken, 429);

    User second = freshUser();
    ticketOnce(bearer(second), 200);
  }

  @Test
  void anonymousTicketFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous tickets are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.75";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/api/notifications/stream-ticket")
              .with(fromIp(ip)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(post("/api/notifications/stream-ticket")
            .with(fromIp(ip)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void notificationBucketExhaustionLeavesSocialGraphBucketIntact() throws Exception {
    User seller = freshUser();
    User actor = freshUser();
    String token = bearer(actor);
    Item item = items.save(new Item("Used film camera", "works, light seals new", 45000L,
        seller.getId()));
    for (int i = 0; i < 3; i++) {
      ticketOnce(token, 200);
    }
    ticketOnce(token, 429);
    // The #308 graph bucket is untouched: a save passes.
    favoriteOnce(token, item.getId(), 200);
    assertThat(favorites.existsByUserIdAndItemId(actor.getId(), item.getId())).isTrue();
  }

  @Test
  void socialGraphBucketExhaustionLeavesNotificationBucketIntact() throws Exception {
    User seller = freshUser();
    User actor = freshUser();
    String token = bearer(actor);
    Item item = items.save(new Item("Used film camera", "works, light seals new", 45000L,
        seller.getId()));
    for (int i = 0; i < 3; i++) {
      favoriteOnce(token, item.getId(), 200);
    }
    favoriteOnce(token, item.getId(), 429);
    // The notification bucket is untouched: a ticket passes.
    ticketOnce(token, 200);
  }

  @Test
  void notificationReadsStayUnthrottledAfterExhaustion() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    for (int i = 0; i < 3; i++) {
      ticketOnce(token, 200);
    }
    ticketOnce(token, 429);
    // The list, preferences-catalogue and unread-count GETs never match
    // the notification matchers and stay unthrottled reads even with an
    // empty bucket.
    mockMvc.perform(get("/api/notifications")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/notifications/preferences")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/notifications/unread-count")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }
}
