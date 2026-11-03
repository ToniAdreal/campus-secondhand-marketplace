package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of the ADMIN listing DELETE through
 * {@code AuthRateLimitFilter} (backlog #314): {@code DELETE
 * /api/items/{id}} has its own per-<em>ADMIN</em> bucket
 * (production default 20/minute, like the #312 ADMIN category
 * bucket — pinned to 3/minute here so the burst arithmetic stays
 * small), keyed on the JWT principal id like the message/report
 * buckets, with the per-IP fallback for a missing/invalid token.
 * The bucket never resets on success — a completed delete must
 * not refill the allowance — and it is deliberately separate from
 * the #307 listing-edit bucket (the PATCH edit on the same
 * {@code /api/items/{id}} path shape stays there), even though
 * the two shapes share a path.
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh ADMIN (per-user keys), mirroring
 * {@code CategoryAdminRateLimitTest}. Listings are seeded
 * repository-direct, mirroring {@code ItemDeleteAuthorizationTest}:
 * a bogus delete answers 404 (unknown id) and still consumes the
 * bucket because the filter runs before the controller, while a
 * throttled delete never reaches the controller and the listing
 * survives.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the ADMIN listing-DELETE surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.admin-listing-delete-attempts-per-minute=3",
    // Pin the neighbouring edit bucket to the same small size so the
    // independence tests can exhaust it without many bogus calls.
    "app.auth.rate-limit.item-edit-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions on the bogus actions below.
    "app.auth.login-lockout.enabled=false"})
class AdminListingDeleteRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository items;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshUser() {
    String tag = "listdel-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User freshAdmin() {
    String tag = "listdel-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item seedItem(User seller) {
    return items.save(new Item("Used film camera", "works, light seals new", 45000L,
        seller.getId()));
  }

  private void remove(String adminToken, long itemId, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/items/{id}", itemId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  private void edit(String adminToken, long itemId, String title, int expectedStatus)
      throws Exception {
    mockMvc.perform(patch("/api/items/{id}", itemId)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateDeletePasses() throws Exception {
    Item item = seedItem(freshUser());
    String adminToken = token(freshAdmin());
    mockMvc.perform(delete("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("ok"));
    assertThat(items.findById(item.getId())).isEmpty();
  }

  @Test
  void deleteBurstIs429WithEnvelopeAndListingsIntact() throws Exception {
    Item item = seedItem(freshUser());
    String adminToken = token(freshAdmin());
    // Three bogus deletes consume the bucket (404: no listing deleted).
    for (int i = 0; i < 3; i++) {
      remove(adminToken, 999_999L, 404);
    }
    // The bucket is empty: the fourth delete is throttled before the
    // controller ever sees it, so the real listing survives.
    mockMvc.perform(delete("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isTooManyRequests())
        .andExpect(header -> {
          String retryAfter = header.getResponse().getHeader("Retry-After");
          if (retryAfter == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        })
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist());
    assertThat(items.findById(item.getId())).isPresent();
  }

  @Test
  void listingDeleteBucketIsPerAdmin() throws Exception {
    String firstAdminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      remove(firstAdminToken, 999_999L, 404);
    }
    remove(firstAdminToken, 999_999L, 429);

    remove(token(freshAdmin()), 999_999L, 404);
  }

  @Test
  void nonAdminSingleDeleteIs403AndDoesNotStarveAdminBucket() throws Exception {
    // A non-ADMIN caller's single delete attempt still reaches the
    // security chain and answers 403 — the filter consumes one token
    // from that caller's own principal bucket on the way, but the 403
    // verdict is what the caller sees, not a 429, and the listing
    // survives.
    Item item = seedItem(freshUser());
    mockMvc.perform(delete("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + token(freshUser())))
        .andExpect(status().isForbidden());
    assertThat(items.findById(item.getId())).isPresent();
    // The ADMIN's bucket is untouched by that attempt: a bogus delete
    // by a fresh ADMIN still reaches the controller and answers 404.
    remove(token(freshAdmin()), 999_999L, 404);
  }

  @Test
  void listingDeleteBucketExhaustionLeavesItemEditBucketIntact() throws Exception {
    Item item = seedItem(freshUser());
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      remove(adminToken, 999_999L, 404);
    }
    remove(adminToken, 999_999L, 429);
    // The #307 bucket is untouched: an edit by the same ADMIN reaches
    // the controller and answers 200, not 429.
    edit(adminToken, item.getId(), "Retitled camera", 200);
    assertThat(items.findById(item.getId()).orElseThrow().getTitle())
        .isEqualTo("Retitled camera");
  }

  @Test
  void itemEditBucketExhaustionLeavesListingDeleteBucketIntact() throws Exception {
    Item item = seedItem(freshUser());
    String adminToken = token(freshAdmin());
    edit(adminToken, item.getId(), "Title one", 200);
    edit(adminToken, item.getId(), "Title two", 200);
    edit(adminToken, item.getId(), "Title three", 200);
    // The #307 bucket is now empty, but the #314 bucket is untouched:
    // the delete reaches the controller and answers 200, not 429.
    remove(adminToken, item.getId(), 200);
    assertThat(items.findById(item.getId())).isEmpty();
  }
}
