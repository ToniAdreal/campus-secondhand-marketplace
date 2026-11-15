package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Following a seller (backlog #217): the CRUD + idempotency matrix,
 * the block interplay (409 on a blocked seller, block-removes-follow
 * in the same transaction), and the publish fan-out (one
 * SELLER_NEW_LISTING row per follower on direct create and on draft
 * publish, preference-mute suppresses the row, drafts never notify on
 * create).
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code UserBlockTest}: each test owns its rows via unique usernames
 * and wipes the touched tables afterwards.
 *
 * <p>Notification assertions are scoped per recipient (backlog #381):
 * the notification table is shared across every test class in one
 * Surefire fork (in-memory H2, {@code DB_CLOSE_DELAY=-1}) and carries
 * no foreign keys, so rows written by order-transition tests in other
 * classes survive their cleanups and a global {@code findAll()} would
 * fail this class for another class's rows. {@link #rowsFor} reads only
 * the user under test, which is exactly what the fan-out promises.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerFollowTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private SellerFollowRepository follows;

  @Autowired
  private UserBlockRepository blocks;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private com.toni.marketplace.notification.NotificationPreferenceRepository preferences;

  @Autowired
  private ItemRepository items;

  @Autowired
  private RefreshTokenRepository refreshTokens;

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
    follows.deleteAll();
    blocks.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "follow-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  /**
   * The recipient-scoped notification read (backlog #381): the fan-out
   * promises rows for one follower, never for the table as a whole —
   * see the class javadoc. The page cap (50) is far above anything one
   * test publishes.
   */
  private List<Notification> rowsFor(User user) {
    return notifications
        .findByRecipientId(user.getId(), PageRequest.of(0, 50)).getContent();
  }

  private void follow(User follower, long sellerId) throws Exception {
    mockMvc.perform(post("/api/users/me/follows/{sellerId}", sellerId)
            .header("Authorization", "Bearer " + token(follower)))
        .andExpect(status().isOk());
  }

  private void publishListing(User seller, String title) throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"description\":\"a used thing\","
                + "\"priceCents\":12000}"))
        .andExpect(status().isOk());
  }

  // --- CRUD + idempotency ---

  @Test
  void followThenListShowsTheSeller() throws Exception {
    User follower = newUser();
    User seller = newUser();

    mockMvc.perform(post("/api/users/me/follows/{sellerId}", seller.getId())
            .header("Authorization", "Bearer " + token(follower)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/me/follows")
            .header("Authorization", "Bearer " + token(follower)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].sellerId").value(seller.getId()))
        .andExpect(jsonPath("$.data[0].sellerUsername").value(seller.getUsername()))
        // No email or other account field leaks through the projection.
        .andExpect(jsonPath("$.data[0].sellerEmail").doesNotExist());

    // Isolation: another user follows nobody.
    mockMvc.perform(get("/api/users/me/follows")
            .header("Authorization", "Bearer " + token(newUser())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void followIsIdempotent() throws Exception {
    User follower = newUser();
    User seller = newUser();

    follow(follower, seller.getId());
    follow(follower, seller.getId());

    assertThat(follows.findAll()).hasSize(1);
  }

  @Test
  void unfollowIsIdempotent() throws Exception {
    User follower = newUser();
    User seller = newUser();

    follow(follower, seller.getId());
    mockMvc.perform(delete("/api/users/me/follows/{sellerId}", seller.getId())
            .header("Authorization", "Bearer " + token(follower)))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/users/me/follows/{sellerId}", seller.getId())
            .header("Authorization", "Bearer " + token(follower)))
        .andExpect(status().isOk());

    assertThat(follows.findAll()).isEmpty();
  }

  @Test
  void selfFollowIs422() throws Exception {
    User user = newUser();

    mockMvc.perform(post("/api/users/me/follows/{sellerId}", user.getId())
            .header("Authorization", "Bearer " + token(user)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(follows.findAll()).isEmpty();
  }

  @Test
  void unknownSellerIs404() throws Exception {
    User follower = newUser();

    mockMvc.perform(post("/api/users/me/follows/{sellerId}", 999_999L)
            .header("Authorization", "Bearer " + token(follower)))
        .andExpect(status().isNotFound());

    mockMvc.perform(delete("/api/users/me/follows/{sellerId}", 999_999L)
            .header("Authorization", "Bearer " + token(follower)))
        .andExpect(status().isNotFound());
  }

  @Test
  void anonymousCallersGet401Envelope() throws Exception {
    mockMvc.perform(post("/api/users/me/follows/1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(delete("/api/users/me/follows/1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(get("/api/users/me/follows"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  // --- Block interplay (#187) ---

  @Test
  void followingABlockedSellerIs409() throws Exception {
    User caller = newUser();
    User seller = newUser();

    mockMvc.perform(post("/api/users/{id}/block", seller.getId())
            .header("Authorization", "Bearer " + token(caller)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/users/me/follows/{sellerId}", seller.getId())
            .header("Authorization", "Bearer " + token(caller)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    assertThat(follows.findAll()).isEmpty();
  }

  @Test
  void blockingAFollowedSellerRemovesTheFollow() throws Exception {
    User caller = newUser();
    User seller = newUser();

    follow(caller, seller.getId());
    assertThat(follows.findAll()).hasSize(1);

    mockMvc.perform(post("/api/users/{id}/block", seller.getId())
            .header("Authorization", "Bearer " + token(caller)))
        .andExpect(status().isOk());

    // The follow is gone in the same transaction as the block —
    // and publishing after the block notifies nobody.
    assertThat(follows.findAll()).isEmpty();
    publishListing(seller, "after the block");
    assertThat(rowsFor(caller)).isEmpty();
  }

  // --- Publish fan-out ---

  @Test
  void publishingNotifiesEachFollowerOnce() throws Exception {
    User seller = newUser();
    User followerA = newUser();
    User followerB = newUser();
    User stranger = newUser();

    follow(followerA, seller.getId());
    follow(followerB, seller.getId());

    publishListing(seller, "campus bike");

    // Each follower gets exactly one row naming the new listing.
    for (User follower : new User[]{followerA, followerB}) {
      mockMvc.perform(get("/api/notifications")
              .header("Authorization", "Bearer " + token(follower)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content.length()").value(1))
          .andExpect(jsonPath("$.data.content[0].type").value("SELLER_NEW_LISTING"))
          .andExpect(jsonPath("$.data.content[0].itemId").isNotEmpty());
    }
    // The seller and a non-follower get nothing; the rows carry the
    // listing id and no order id.
    assertThat(rowsFor(followerA)).hasSize(1);
    assertThat(rowsFor(followerB)).hasSize(1);
    assertThat(rowsFor(seller)).isEmpty();
    assertThat(rowsFor(stranger)).isEmpty();

    // A second publish notifies again — no per-listing dedupe across
    // publishes.
    publishListing(seller, "campus bike 2");
    assertThat(rowsFor(followerA)).hasSize(2);
    assertThat(rowsFor(followerB)).hasSize(2);
    assertThat(rowsFor(seller)).isEmpty();
    assertThat(rowsFor(stranger)).isEmpty();
    assertThat(rowsFor(followerA)).allMatch(
        n -> n.getType() == NotificationType.SELLER_NEW_LISTING
            && n.getOrderId() == null && n.getItemId() != null);
    assertThat(rowsFor(followerB)).allMatch(
        n -> n.getType() == NotificationType.SELLER_NEW_LISTING
            && n.getOrderId() == null && n.getItemId() != null);
  }

  @Test
  void draftCreateDoesNotNotifyButPublishDoes() throws Exception {
    User seller = newUser();
    User follower = newUser();
    follow(follower, seller.getId());

    String draftId = mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"draft lamp\",\"description\":\"draft\","
                + "\"priceCents\":5000,\"status\":\"DRAFT\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DRAFT"))
        .andReturn().getResponse().getContentAsString();
    assertThat(rowsFor(follower)).isEmpty();
    assertThat(rowsFor(seller)).isEmpty();

    long itemId = ((Number) com.jayway.jsonpath.JsonPath.read(draftId, "$.data.id")).longValue();
    mockMvc.perform(post("/api/items/{id}/publish", itemId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("AVAILABLE"));

    assertThat(rowsFor(follower)).hasSize(1);
    assertThat(rowsFor(follower).get(0).getType())
        .isEqualTo(NotificationType.SELLER_NEW_LISTING);
    assertThat(rowsFor(follower).get(0).getItemId()).isEqualTo(itemId);
    assertThat(rowsFor(seller)).isEmpty();
  }

  @Test
  void mutedTypeSuppressesTheFanOut() throws Exception {
    User seller = newUser();
    User follower = newUser();
    follow(follower, seller.getId());

    // The type is mutable (#216) — the recipient opted out.
    mockMvc.perform(put("/api/notifications/preferences/SELLER_NEW_LISTING")
            .header("Authorization", "Bearer " + token(follower))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false}"))
        .andExpect(status().isOk());

    publishListing(seller, "muted bike");
    assertThat(rowsFor(follower)).isEmpty();
  }

  @Test
  void newTypeIsInThePreferenceCatalogue() throws Exception {
    User user = newUser();

    mockMvc.perform(get("/api/notifications/preferences")
            .header("Authorization", "Bearer " + token(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.type == 'SELLER_NEW_LISTING')].label")
            .value("New listing from a followed seller"))
        .andExpect(jsonPath("$.data[?(@.type == 'SELLER_NEW_LISTING')].security")
            .value(false))
        .andExpect(jsonPath("$.data[?(@.type == 'SELLER_NEW_LISTING')].enabled")
            .value(true));
  }
}
