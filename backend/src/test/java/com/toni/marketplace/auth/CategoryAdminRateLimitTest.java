package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.item.Category;
import com.toni.marketplace.item.CategoryRepository;
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
 * Rate-limit behavior of the ADMIN category writes through
 * {@code AuthRateLimitFilter} (backlog #312): {@code POST
 * /api/categories}, {@code PATCH /api/categories/{id}} and
 * {@code DELETE /api/categories/{id}} share one per-<em>user</em>
 * bucket (production default 20/minute — pinned to 3/minute here so
 * the burst arithmetic stays small), keyed on the JWT principal id
 * like the message/report buckets, with the per-IP fallback for a
 * missing/invalid token. The bucket never resets on success — a
 * completed create, rename or delete must not refill the allowance
 * — and it is deliberately separate from the #303 ADMIN user-action
 * bucket (a different ADMIN queue).
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses a fresh ADMIN (per-user keys), mirroring
 * {@code AdminReportActionRateLimitTest}. Rows are seeded
 * repository-direct, mirroring {@code CategoryAdminManagementTest}:
 * a rename needs only the category row, and a delete additionally
 * asserts the row is gone; bogus shapes answer 404 (unknown id) or
 * 400 (blank name), which still consume the bucket because the
 * filter runs before the controller.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the ADMIN category surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.admin-category-attempts-per-minute=3",
    // Pin the neighbouring user-action bucket to the same small size so
    // the independence tests can exhaust it without many bogus calls.
    "app.auth.rate-limit.admin-user-action-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions on the bogus actions below.
    "app.auth.login-lockout.enabled=false"})
class CategoryAdminRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private CategoryRepository categories;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User freshUser() {
    String tag = "catrate-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User freshAdmin() {
    String tag = "catrate-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Category freshCategory() {
    String tag = "catrate-" + SEQ.incrementAndGet();
    return categories.save(new Category("Rate Category " + tag, "rate-category-" + tag));
  }

  private void create(String adminToken, String name, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void rename(String adminToken, long categoryId, String name, int expectedStatus)
      throws Exception {
    mockMvc.perform(patch("/api/categories/{id}", categoryId)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void remove(String adminToken, long categoryId, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/categories/{id}", categoryId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  private void disable(String adminToken, long targetId, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/admin/users/{id}/disable", targetId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateCreatePasses() throws Exception {
    String adminToken = token(freshAdmin());
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Rate Limit Category\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.name").value("Rate Limit Category"))
        .andExpect(jsonPath("$.data.slug").value("rate-limit-category"));
    assertThat(categories.findBySlug("rate-limit-category")).isPresent();
  }

  @Test
  void singleLegitimateRenamePassesAndKeepsSlug() throws Exception {
    Category category = freshCategory();
    String adminToken = token(freshAdmin());
    mockMvc.perform(patch("/api/categories/{id}", category.getId())
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Rate Renamed Category\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("Rate Renamed Category"))
        .andExpect(jsonPath("$.data.slug").value(category.getSlug()));
    assertThat(categories.findById(category.getId()).orElseThrow().getName())
        .isEqualTo("Rate Renamed Category");
  }

  @Test
  void singleLegitimateDeletePasses() throws Exception {
    Category category = freshCategory();
    String adminToken = token(freshAdmin());
    mockMvc.perform(delete("/api/categories/{id}", category.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());
    assertThat(categories.findById(category.getId())).isEmpty();
  }

  @Test
  void deleteBurstIs429WithEnvelope() throws Exception {
    String adminToken = token(freshAdmin());
    // Three bogus deletes consume the bucket (404: no category deleted).
    for (int i = 0; i < 3; i++) {
      remove(adminToken, 999_999L, 404);
    }
    // The bucket is empty: the fourth delete is throttled before the
    // controller ever sees it.
    mockMvc.perform(delete("/api/categories/{id}", 999_999L)
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
  }

  @Test
  void categoryBucketIsSharedAcrossCreateRenameAndDelete() throws Exception {
    // One bogus call per shape exhausts the shared bucket — one bucket
    // for the whole category-write surface, not one per endpoint.
    String adminToken = token(freshAdmin());
    create(adminToken, "   ", 400);
    rename(adminToken, 999_999L, "Bogus Rename", 404);
    remove(adminToken, 999_999L, 404);
    // The fourth write, on any shape, is throttled.
    remove(adminToken, 999_999L, 429);
  }

  @Test
  void categoryBucketIsPerAdmin() throws Exception {
    String firstAdminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      remove(firstAdminToken, 999_999L, 404);
    }
    remove(firstAdminToken, 999_999L, 429);

    remove(token(freshAdmin()), 999_999L, 404);
  }

  @Test
  void nonAdminSingleCreateIs403AndDoesNotStarveAdminBucket() throws Exception {
    // A non-ADMIN caller's single create attempt still reaches the
    // security chain and answers 403 — the filter consumes one token
    // from that caller's own principal bucket on the way, but the 403
    // verdict is what the caller sees, not a 429.
    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + token(freshUser()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Gadgets\"}"))
        .andExpect(status().isForbidden());
    // The ADMIN's bucket is untouched by that attempt: a bogus delete
    // by a fresh ADMIN still reaches the controller and answers 404.
    remove(token(freshAdmin()), 999_999L, 404);
  }

  @Test
  void categoryBucketExhaustionLeavesAdminUserActionBucketIntact() throws Exception {
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      remove(adminToken, 999_999L, 404);
    }
    remove(adminToken, 999_999L, 429);
    // The #303 bucket is untouched: a bogus disable reaches the
    // controller and answers 404, not 429.
    disable(adminToken, 999_999L, 404);
  }

  @Test
  void adminUserActionBucketExhaustionLeavesCategoryBucketIntact() throws Exception {
    String adminToken = token(freshAdmin());
    for (int i = 0; i < 3; i++) {
      disable(adminToken, 999_999L, 404);
    }
    disable(adminToken, 999_999L, 429);
    // The #312 bucket is untouched: a bogus delete reaches the
    // controller and answers 404, not 429.
    remove(adminToken, 999_999L, 404);
  }
}
