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
 * Rate-limit behavior of the social-graph writes through
 * {@code AuthRateLimitFilter} (backlog #308): {@code PUT/DELETE
 * /api/users/me/favorites/{itemId}} (save / unsave a listing),
 * {@code POST/DELETE /api/users/me/follows/{sellerId}} (follow /
 * unfollow a seller) and {@code POST/DELETE /api/users/{id}/block}
 * (block / unblock a user) share one per-<em>user</em> bucket
 * (production default 20/minute, like the #282 bucket — pinned to
 * 3/minute here so the burst arithmetic stays small), keyed on the
 * JWT principal id like the message/report buckets, with the per-IP
 * fallback for a missing/invalid token. The bucket never resets on
 * success — a completed save, follow or block must not refill the
 * allowance — and it is deliberately separate from the #282
 * social-write bucket (filing an offer, a review, a dispute, evidence
 * or an appeal stays there: same "social" word, different surface).
 * The favorites, follows and blocks list GETs are reads and stay
 * unthrottled.
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses fresh users (per-user keys), mirroring
 * {@code ItemEditRateLimitTest}. Listings are seeded repository-direct,
 * mirroring {@code ItemEditRateLimitTest}; the favorite, follow and
 * block writes are all idempotent 200s, so a burst can repeat one
 * shape without tripping a 409 or a second-row guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the social-graph surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.social-graph-attempts-per-minute=3",
    // Pin the neighbouring #282 bucket to the same small size so the
    // independence tests can exhaust it without a long burst.
    "app.auth.rate-limit.social-write-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions.
    "app.auth.login-lockout.enabled=false"})
class SocialGraphRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ItemRepository items;
  @Autowired private FavoriteRepository favorites;
  @Autowired private SellerFollowRepository follows;
  @Autowired private UserBlockRepository blocks;

  private User freshUser() {
    String tag = "socgraph-" + SEQ.incrementAndGet() + "-"
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

  private Item seedItem(User seller) {
    return items.save(new Item("Used film camera", "works, light seals new", 45000L,
        seller.getId()));
  }

  private void favoriteOnce(String token, long itemId, int expectedStatus) throws Exception {
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", itemId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void unfavoriteOnce(String token, long itemId, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/users/me/favorites/{itemId}", itemId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void followOnce(String token, long sellerId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/users/me/follows/{sellerId}", sellerId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void unfollowOnce(String token, long sellerId, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/users/me/follows/{sellerId}", sellerId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void blockOnce(String token, long blockedId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/users/{id}/block", blockedId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void unblockOnce(String token, long blockedId, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/users/{id}/block", blockedId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void offerOnce(String token, long itemId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/items/{id}/offers", itemId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":10000}"))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateFavoritePasses() throws Exception {
    User seller = freshUser();
    User saver = freshUser();
    Item item = seedItem(seller);
    favoriteOnce(bearer(saver), item.getId(), 200);
    assertThat(favorites.existsByUserIdAndItemId(saver.getId(), item.getId())).isTrue();
  }

  @Test
  void singleLegitimateFollowPasses() throws Exception {
    User follower = freshUser();
    User seller = freshUser();
    followOnce(bearer(follower), seller.getId(), 200);
    assertThat(follows.existsByFollowerIdAndSellerId(follower.getId(), seller.getId())).isTrue();
  }

  @Test
  void singleLegitimateBlockPasses() throws Exception {
    User blocker = freshUser();
    User blocked = freshUser();
    blockOnce(bearer(blocker), blocked.getId(), 200);
    assertThat(blocks.existsByBlockerIdAndBlockedId(blocker.getId(), blocked.getId())).isTrue();
  }

  @Test
  void singleLegitimateUnfavoritePasses() throws Exception {
    User seller = freshUser();
    User saver = freshUser();
    Item item = seedItem(seller);
    String token = bearer(saver);
    favoriteOnce(token, item.getId(), 200);
    unfavoriteOnce(token, item.getId(), 200);
    assertThat(favorites.existsByUserIdAndItemId(saver.getId(), item.getId())).isFalse();
  }

  @Test
  void singleLegitimateUnfollowPasses() throws Exception {
    User follower = freshUser();
    User seller = freshUser();
    String token = bearer(follower);
    followOnce(token, seller.getId(), 200);
    unfollowOnce(token, seller.getId(), 200);
    assertThat(follows.existsByFollowerIdAndSellerId(follower.getId(), seller.getId()))
        .isFalse();
  }

  @Test
  void singleLegitimateUnblockPasses() throws Exception {
    User blocker = freshUser();
    User blocked = freshUser();
    String token = bearer(blocker);
    blockOnce(token, blocked.getId(), 200);
    unblockOnce(token, blocked.getId(), 200);
    assertThat(blocks.existsByBlockerIdAndBlockedId(blocker.getId(), blocked.getId()))
        .isFalse();
  }

  @Test
  void favoriteBurstIs429WithEnvelope() throws Exception {
    User seller = freshUser();
    User saver = freshUser();
    Item item = seedItem(seller);
    String token = bearer(saver);
    favoriteOnce(token, item.getId(), 200);
    favoriteOnce(token, item.getId(), 200);
    favoriteOnce(token, item.getId(), 200);
    // The bucket is empty: the fourth save is throttled before the
    // controller ever sees it, so the save stays exactly one row.
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", item.getId())
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
    assertThat(favorites.findFavoriteListView(saver.getId())).hasSize(1);
  }

  @Test
  void graphBucketIsSharedAcrossFavoriteFollowAndBlock() throws Exception {
    // Exhausting the bucket with saves leaves a follow and a block
    // throttled too — one bucket for the whole graph surface, not one
    // per endpoint. The filter short-circuits before the controllers,
    // so the follow and the block write no rows.
    User seller = freshUser();
    User blocked = freshUser();
    User actor = freshUser();
    String token = bearer(actor);
    Item item = seedItem(seller);
    for (int i = 0; i < 3; i++) {
      favoriteOnce(token, item.getId(), 200);
    }
    followOnce(token, seller.getId(), 429);
    blockOnce(token, blocked.getId(), 429);
    assertThat(follows.existsByFollowerIdAndSellerId(actor.getId(), seller.getId()))
        .isFalse();
    assertThat(blocks.existsByBlockerIdAndBlockedId(actor.getId(), blocked.getId()))
        .isFalse();
  }

  @Test
  void graphBucketIsPerUser() throws Exception {
    User seller = freshUser();
    User first = freshUser();
    Item item = seedItem(seller);
    String firstToken = bearer(first);
    for (int i = 0; i < 3; i++) {
      favoriteOnce(firstToken, item.getId(), 200);
    }
    favoriteOnce(firstToken, item.getId(), 429);

    User second = freshUser();
    favoriteOnce(bearer(second), item.getId(), 200);
    assertThat(favorites.existsByUserIdAndItemId(second.getId(), item.getId())).isTrue();
  }

  @Test
  void anonymousFavoriteFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous saves are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.74";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(put("/api/users/me/favorites/{itemId}", 987654321L)
              .with(fromIp(ip)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", 987654321L)
            .with(fromIp(ip)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void graphBucketExhaustionLeavesSocialWriteBucketIntact() throws Exception {
    User seller = freshUser();
    User actor = freshUser();
    String token = bearer(actor);
    Item saved = seedItem(seller);
    Item offered = seedItem(seller);
    for (int i = 0; i < 3; i++) {
      favoriteOnce(token, saved.getId(), 200);
    }
    favoriteOnce(token, saved.getId(), 429);
    // The #282 creation bucket is untouched: filing an offer passes.
    offerOnce(token, offered.getId(), 200);
  }

  @Test
  void socialWriteBucketExhaustionLeavesGraphBucketIntact() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    String token = bearer(buyer);
    for (int i = 0; i < 3; i++) {
      offerOnce(token, seedItem(seller).getId(), 200);
    }
    offerOnce(token, seedItem(seller).getId(), 429);
    // The graph bucket is untouched: a save passes.
    favoriteOnce(token, seedItem(seller).getId(), 200);
  }

  @Test
  void listReadsStayUnthrottledAfterExhaustion() throws Exception {
    User seller = freshUser();
    User actor = freshUser();
    String token = bearer(actor);
    Item item = seedItem(seller);
    for (int i = 0; i < 3; i++) {
      favoriteOnce(token, item.getId(), 200);
    }
    favoriteOnce(token, item.getId(), 429);
    // The list GETs carry no id segment, never match the graph
    // matchers, and stay unthrottled reads even with an empty bucket.
    mockMvc.perform(get("/api/users/me/favorites")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/users/me/follows")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/users/me/blocks")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }
}
