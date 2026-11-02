package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemPhoto;
import com.toni.marketplace.item.ItemPhotoRepository;
import com.toni.marketplace.item.ItemRepository;
import java.util.List;
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
 * Rate-limit behavior of the listing-edit writes through
 * {@code AuthRateLimitFilter} (backlog #307): {@code PATCH
 * /api/items/{id}} (field edits), {@code PUT
 * /api/items/{id}/photos/order} (gallery reorder) and {@code DELETE
 * /api/items/{id}/photos/{photoId}} (gallery photo removal) share one
 * per-<em>user</em> bucket (production default 30/minute, like the #228
 * mutation bucket — pinned to 3/minute here so the burst arithmetic
 * stays small), keyed on the JWT principal id like the message/report
 * buckets, with the per-IP fallback for a missing/invalid token. The
 * bucket never resets on success — a completed edit, reorder or photo
 * removal must not refill the allowance — and it is deliberately
 * separate from the #228 mutation bucket (photo uploads, renew,
 * publish and the status flip stay there, as the #228 docs already
 * promised for the edit PATCH and the reorder PUT) and from the #219
 * listing-create bucket (creating a listing stays there).
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses fresh users (per-user keys), mirroring
 * {@code PasskeyManagementRateLimitTest}. Listings and galleries are
 * seeded repository-direct (photo URLs are inert strings here — the
 * reorder and delete paths never touch the files on disk beyond a
 * best-effort unlink), mirroring {@code ItemPhotoReorderTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the listing-edit surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.item-edit-attempts-per-minute=3",
    // Pin the neighbouring buckets to the same small size so the
    // independence tests can exhaust them without a long burst.
    "app.auth.rate-limit.item-mutation-attempts-per-minute=3",
    "app.auth.rate-limit.listing-create-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions.
    "app.auth.login-lockout.enabled=false"})
class ItemEditRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ItemRepository items;
  @Autowired private ItemPhotoRepository photos;

  private User freshUser() {
    String tag = "itemedit-" + SEQ.incrementAndGet() + "-"
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

  /** Seeds a 3-photo gallery at positions 0,1,2 and returns the photo ids in order. */
  private List<Long> seedGallery(Item item) {
    return List.of(
        photos.save(new ItemPhoto(item, "/uploads/a.png", 0)).getId(),
        photos.save(new ItemPhoto(item, "/uploads/b.png", 1)).getId(),
        photos.save(new ItemPhoto(item, "/uploads/c.png", 2)).getId());
  }

  private void editOnce(String token, long itemId, String title, int expectedStatus)
      throws Exception {
    mockMvc.perform(patch("/api/items/{id}", itemId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private List<Long> storedIdsInOrder(Long itemId) {
    return photos.findByItemIdOrderByPositionAsc(itemId).stream()
        .map(ItemPhoto::getId)
        .toList();
  }

  private String orderBody(List<Long> photoIds) {
    StringBuilder sb = new StringBuilder("{\"photoIds\":[");
    for (int i = 0; i < photoIds.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(photoIds.get(i));
    }
    return sb.append("]}").toString();
  }

  private void reorderOnce(String token, long itemId, List<Long> photoIds, int expectedStatus)
      throws Exception {
    mockMvc.perform(put("/api/items/{id}/photos/order", itemId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(photoIds)))
        .andExpect(status().is(expectedStatus));
  }

  private void deletePhotoOnce(String token, long itemId, long photoId, int expectedStatus)
      throws Exception {
    mockMvc.perform(delete("/api/items/{id}/photos/{photoId}", itemId, photoId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void markSoldOnce(String token, long itemId, int expectedStatus) throws Exception {
    mockMvc.perform(patch("/api/items/{id}/status", itemId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void createListingOnce(String token) throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"ThinkPad T480s\",\"description\":\"good battery\","
                + "\"priceCents\":25000}"))
        .andExpect(status().isOk());
  }

  @Test
  void singleLegitimateEditPasses() throws Exception {
    User seller = freshUser();
    Item item = seedItem(seller);
    editOnce(bearer(seller), item.getId(), "Retitled camera", 200);
    assertThat(items.findById(item.getId()).orElseThrow().getTitle())
        .isEqualTo("Retitled camera");
  }

  @Test
  void singleLegitimateReorderPasses() throws Exception {
    User seller = freshUser();
    Item item = seedItem(seller);
    List<Long> ids = seedGallery(item);
    List<Long> newOrder = List.of(ids.get(2), ids.get(0), ids.get(1));
    reorderOnce(bearer(seller), item.getId(), newOrder, 200);
    assertThat(storedIdsInOrder(item.getId())).containsExactlyElementsOf(newOrder);
  }

  @Test
  void singleLegitimatePhotoDeletePasses() throws Exception {
    User seller = freshUser();
    Item item = seedItem(seller);
    List<Long> ids = seedGallery(item);
    deletePhotoOnce(bearer(seller), item.getId(), ids.get(1), 200);
    assertThat(photos.findById(ids.get(1))).isEmpty();
    assertThat(photos.findByItemIdOrderByPositionAsc(item.getId())).hasSize(2);
  }

  @Test
  void editBurstIs429WithEnvelope() throws Exception {
    User seller = freshUser();
    Item item = seedItem(seller);
    String token = bearer(seller);
    editOnce(token, item.getId(), "Title one", 200);
    editOnce(token, item.getId(), "Title two", 200);
    editOnce(token, item.getId(), "Title three", 200);
    // The bucket is empty: the fourth edit is throttled before the
    // controller ever sees it, so the title stays at the third edit.
    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Title four\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        });
    assertThat(items.findById(item.getId()).orElseThrow().getTitle())
        .isEqualTo("Title three");
  }

  @Test
  void editBucketIsSharedAcrossEditReorderAndDelete() throws Exception {
    // Exhausting the bucket with edits leaves a reorder and a photo
    // delete throttled too — one bucket for the whole edit surface,
    // not one per endpoint. The reorder body is an exact permutation
    // and the photo id is real, so without the throttle both would
    // have succeeded; the filter short-circuits before the controller
    // ever sees them.
    User seller = freshUser();
    String token = bearer(seller);
    Item edited = seedItem(seller);
    Item gallery = seedItem(seller);
    List<Long> ids = seedGallery(gallery);
    for (int i = 0; i < 3; i++) {
      editOnce(token, edited.getId(), "Title " + i, 200);
    }
    reorderOnce(token, gallery.getId(), List.of(ids.get(2), ids.get(0), ids.get(1)), 429);
    deletePhotoOnce(token, gallery.getId(), ids.get(0), 429);
    assertThat(storedIdsInOrder(gallery.getId())).containsExactlyElementsOf(ids);
    assertThat(photos.findById(ids.get(0))).isPresent();
  }

  @Test
  void editBucketIsPerUser() throws Exception {
    User first = freshUser();
    String firstToken = bearer(first);
    Item firstItem = seedItem(first);
    for (int i = 0; i < 3; i++) {
      editOnce(firstToken, firstItem.getId(), "Title " + i, 200);
    }
    editOnce(firstToken, firstItem.getId(), "Title four", 429);

    User second = freshUser();
    editOnce(bearer(second), seedItem(second).getId(), "Second seller title", 200);
  }

  @Test
  void anonymousEditFallbackStillThrottles() throws Exception {
    // No token: the filter falls back to a per-IP bucket. The first three
    // anonymous edits are answered 401 by the security chain (the
    // bucket still consumed each attempt); the fourth is short-circuited
    // 429 by the filter itself. A dedicated remote address keeps this
    // test's IP bucket away from every other test's.
    String ip = "203.0.113.73";
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(patch("/api/items/{id}", 987654321L)
              .with(fromIp(ip))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"title\":\"Anonymous\"}"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(patch("/api/items/{id}", 987654321L)
            .with(fromIp(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Anonymous\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
  }

  @Test
  void editBucketExhaustionLeavesMutationAndCreateBucketsIntact() throws Exception {
    User seller = freshUser();
    String token = bearer(seller);
    Item edited = seedItem(seller);
    Item statusItem = seedItem(seller);
    for (int i = 0; i < 3; i++) {
      editOnce(token, edited.getId(), "Title " + i, 200);
    }
    editOnce(token, edited.getId(), "Title four", 429);
    // The #228 mutation bucket is untouched: the status flip on another
    // listing passes, and the #219 listing-create bucket is untouched:
    // creating a listing passes.
    markSoldOnce(token, statusItem.getId(), 200);
    createListingOnce(token);
  }

  @Test
  void mutationBucketExhaustionLeavesEditBucketIntact() throws Exception {
    User seller = freshUser();
    String token = bearer(seller);
    Item statusItem = seedItem(seller);
    Item edited = seedItem(seller);
    // The first status flip succeeds; the next two are 422 (already
    // SOLD) but still consume the mutation bucket; the fourth is 429.
    markSoldOnce(token, statusItem.getId(), 200);
    markSoldOnce(token, statusItem.getId(), 422);
    markSoldOnce(token, statusItem.getId(), 422);
    markSoldOnce(token, statusItem.getId(), 429);
    // The edit bucket is untouched: an edit on another listing passes.
    editOnce(token, edited.getId(), "Still editable", 200);
    assertThat(items.findById(edited.getId()).orElseThrow().getTitle())
        .isEqualTo("Still editable");
  }
}
