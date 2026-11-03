package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.dispute.DisputeEvidenceRepository;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.PaymentAttemptRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rate-limit behavior of the dispute-evidence DELETE through
 * {@code AuthRateLimitFilter} (backlog #315): {@code DELETE
 * /api/disputes/{disputeId}/evidence/{evidenceId}} has its own
 * per-<em>user</em> bucket (production default 20/minute, like the
 * #282 bucket — pinned to 3/minute here so the burst arithmetic
 * stays small), keyed on the JWT principal id like the message /
 * report buckets, with the per-IP fallback for a missing/invalid
 * token. The bucket never resets on success — a completed removal
 * must not refill the allowance — and it is deliberately separate
 * from the #282 social-write bucket (filing evidence with the POST
 * sibling stays there), even though the DELETE shares the
 * dispute-evidence path prefix with that POST.
 *
 * <p>Buckets are in-memory and survive across tests within this
 * class, so each test uses fresh users (per-user keys), mirroring
 * {@code AdminListingDeleteRateLimitTest}. The #282 bucket is
 * pinned to 4/minute here, not 3: opening a dispute costs one #282
 * token (the dispute POST itself), so 4 leaves exactly three
 * uploads before exhaustion — the arithmetic the independence
 * test needs. Evidence rows are uploaded through the API, not
 * seeded repository-direct, so the burst test can assert the
 * stored file itself survives a throttled delete.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code DisputeEvidenceTest}: rows must be committed by the
 * requests themselves to be asserted here, and uploads land in
 * {@code target/test-uploads/dispute-evidence-delete} and are
 * wiped after every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the dispute-evidence DELETE surface, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.dispute-evidence-delete-attempts-per-minute=3",
    // Pin the neighbouring #282 bucket so the independence tests can
    // exhaust it without a long burst; 4 covers the dispute POST plus
    // three uploads (see the class javadoc).
    "app.auth.rate-limit.social-write-attempts-per-minute=4",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions on the bogus actions below.
    "app.auth.login-lockout.enabled=false",
    "app.uploads.dir=target/test-uploads/dispute-evidence-delete"})
class DisputeEvidenceDeleteRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  private static final Path UPLOAD_DIR =
      Paths.get("target/test-uploads/dispute-evidence-delete");

  /** Minimal valid PNG (1x1 pixel), the DisputeEvidenceTest fixture. */
  private static final byte[] PNG_BYTES = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
      0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
      (byte) 0x89, 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41,
      0x54, 0x78, (byte) 0x9C, 0x63, 0x60, 0x00, 0x02, 0x00,
      0x00, 0x05, 0x00, 0x01, 0x7A, 0x5E, (byte) 0xAB, 0x3F,
      0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
      (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ItemRepository items;
  @Autowired private OrderRepository orders;
  @Autowired private IdempotencyKeyRepository keys;
  @Autowired private PaymentAttemptRepository paymentAttempts;
  @Autowired private NotificationRepository notifications;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private OrderDisputeRepository disputes;
  @Autowired private DisputeEvidenceRepository evidence;
  @Autowired private RefreshTokenRepository refreshTokens;

  @AfterEach
  void cleanUp() throws IOException {
    evidence.deleteAll();
    disputes.deleteAll();
    notifications.deleteAll();
    auditLog.deleteAll();
    keys.deleteAll();
    paymentAttempts.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
    if (Files.exists(UPLOAD_DIR)) {
      try (Stream<Path> walk = Files.walk(UPLOAD_DIR)) {
        walk.sorted(Comparator.reverseOrder()).forEach(path -> {
          try {
            Files.deleteIfExists(path);
          } catch (IOException ignored) {
            // Best effort: a leftover test upload never fails the suite.
          }
        });
      }
    }
  }

  private User freshUser() {
    String tag = "eviddel-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String bearer(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  /** A PAID order on a RESERVED listing, opened into a dispute; returns the dispute id. */
  private long openDispute(User buyer, User seller) throws Exception {
    Item item = new Item("Film camera", "Canon AE-1", 10_000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    items.save(item);
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + bearer(buyer))
            .header("Idempotency-Key", "eviddel-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + bearer(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());
    String disputeBody = mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + bearer(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"the lens arrived cracked\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(disputeBody, "$.data.id")).longValue();
  }

  private MvcResult uploadOk(String token, long disputeId) throws Exception {
    return mockMvc.perform(multipart("/api/disputes/{id}/evidence", disputeId)
            .file(new MockMultipartFile("file", "damage.png", "image/png", PNG_BYTES))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
  }

  private long evidenceIdOf(MvcResult upload) throws Exception {
    return ((Number) JsonPath.read(
        upload.getResponse().getContentAsString(), "$.data.id")).longValue();
  }

  private Path fileOf(MvcResult upload) throws Exception {
    String url = JsonPath.read(upload.getResponse().getContentAsString(), "$.data.url");
    return UPLOAD_DIR.resolve(url.substring("/uploads/".length()));
  }

  private void deleteOnce(String token, long disputeId, long evidenceId, int expectedStatus)
      throws Exception {
    mockMvc.perform(delete("/api/disputes/{d}/evidence/{e}", disputeId, evidenceId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateDeletePasses() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long disputeId = openDispute(buyer, seller);
    String token = bearer(buyer);
    MvcResult upload = uploadOk(token, disputeId);
    long evidenceId = evidenceIdOf(upload);
    Path file = fileOf(upload);
    assertThat(file).exists();

    deleteOnce(token, disputeId, evidenceId, 200);
    assertThat(evidence.findById(evidenceId)).isEmpty();
    assertThat(file).doesNotExist();
  }

  @Test
  void deleteBurstIs429WithRowAndFileIntact() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long disputeId = openDispute(buyer, seller);
    String token = bearer(buyer);
    MvcResult upload = uploadOk(token, disputeId);
    long evidenceId = evidenceIdOf(upload);
    Path file = fileOf(upload);
    assertThat(file).exists();

    // Three bogus deletes consume the bucket (404: no evidence removed).
    for (int i = 0; i < 3; i++) {
      deleteOnce(token, disputeId, 999_999L, 404);
    }
    // The bucket is empty: the fourth delete is throttled before the
    // controller ever sees it, so the row and its file both survive —
    // a throttled delete deletes nothing, and the #40 orphan
    // discipline has nothing to clean up.
    mockMvc.perform(delete("/api/disputes/{d}/evidence/{e}", disputeId, evidenceId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(header -> {
          if (header.getResponse().getHeader("Retry-After") == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
        });
    assertThat(evidence.findById(evidenceId)).isPresent();
    assertThat(file).exists();
  }

  @Test
  void deleteBucketIsPerUser() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long disputeId = openDispute(buyer, seller);
    String firstToken = bearer(buyer);
    for (int i = 0; i < 3; i++) {
      deleteOnce(firstToken, disputeId, 999_999L, 404);
    }
    deleteOnce(firstToken, disputeId, 999_999L, 429);

    // A second user lands on their own principal key: their first
    // delete attempt reaches the controller (404 for the unknown
    // evidence id), not a 429 from the first user's bucket.
    deleteOnce(bearer(freshUser()), disputeId, 999_999L, 404);
  }

  @Test
  void deleteBucketExhaustionLeavesSocialWriteBucketIntact() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long disputeId = openDispute(buyer, seller);
    String token = bearer(buyer);
    uploadOk(token, disputeId);
    for (int i = 0; i < 3; i++) {
      deleteOnce(token, disputeId, 999_999L, 404);
    }
    deleteOnce(token, disputeId, 999_999L, 429);
    // The #282 bucket is untouched: filing another evidence photo
    // reaches the controller and answers 200, not 429.
    MvcResult second = uploadOk(token, disputeId);
    assertThat(evidence.findById(evidenceIdOf(second))).isPresent();
  }

  @Test
  void socialWriteBucketExhaustionLeavesDeleteBucketIntact() throws Exception {
    User seller = freshUser();
    User buyer = freshUser();
    long disputeId = openDispute(buyer, seller);
    String token = bearer(buyer);
    // The dispute POST cost one #282 token; three uploads cost the
    // other three, so the #282 bucket is now empty.
    MvcResult first = uploadOk(token, disputeId);
    uploadOk(token, disputeId);
    uploadOk(token, disputeId);
    mockMvc.perform(multipart("/api/disputes/{id}/evidence", disputeId)
            .file(new MockMultipartFile("file", "damage.png", "image/png", PNG_BYTES))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
    // The #315 bucket is untouched: the delete reaches the
    // controller and answers 200, not 429.
    long evidenceId = evidenceIdOf(first);
    Path file = fileOf(first);
    deleteOnce(token, disputeId, evidenceId, 200);
    assertThat(evidence.findById(evidenceId)).isEmpty();
    assertThat(file).doesNotExist();
  }
}
