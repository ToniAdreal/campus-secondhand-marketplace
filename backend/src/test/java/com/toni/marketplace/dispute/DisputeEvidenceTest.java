package com.toni.marketplace.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLog;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Dispute evidence photos (backlog #200): the upload/list/remove
 * matrix over the real dispute flow — both parties and an ADMIN may
 * add and read, a stranger gets the no-existence-leak 404, a resolved
 * dispute refuses new evidence with 422, the storage magic-byte gate
 * answers 400 leaving neither file nor row, and removing evidence
 * deletes its file from disk (the #40 orphan discipline).
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderDisputeTest}: rows must be committed by the requests
 * themselves to be asserted here. Uploads land in
 * {@code target/test-uploads/dispute-evidence} and are wiped after
 * every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.uploads.dir=target/test-uploads/dispute-evidence"})
class DisputeEvidenceTest {

  private static final AtomicLong SEQ = new AtomicLong();

  private static final Path UPLOAD_DIR =
      Paths.get("target/test-uploads/dispute-evidence");

  /** Minimal valid PNG (1x1 pixel), the ItemPhotoGalleryTest fixture. */
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

  /** Structurally complete JPEG (SOI + APP0 JFIF + EOI), the ImageStorageServiceTest fixture. */
  private static final byte[] JPEG_BYTES = {
      (byte) 0xFF, (byte) 0xD8,
      (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
      'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
      (byte) 0xFF, (byte) 0xD9};

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OrderDisputeRepository disputes;

  @Autowired
  private DisputeEvidenceRepository evidence;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @AfterEach
  void cleanUp() throws IOException {
    evidence.deleteAll();
    disputes.deleteAll();
    notifications.deleteAll();
    auditLog.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
    if (Files.exists(UPLOAD_DIR)) {
      try (var stream = Files.walk(UPLOAD_DIR)) {
        stream.sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
      }
    }
  }

  private User newUser() {
    String tag = "evidence-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    String tag = "evidence-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  /** A PAID order on a RESERVED listing, opened into a dispute; returns the dispute id. */
  private long openDispute(User buyer, User seller) throws Exception {
    Item item = new Item("Film camera", "Canon AE-1", 10_000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    items.save(item);
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "evidence-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());
    String disputeBody = mockMvc.perform(post("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"the lens arrived cracked\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(disputeBody, "$.data.id")).longValue();
  }

  private MockMultipartFile png() {
    return new MockMultipartFile("file", "damage.png", "image/png", PNG_BYTES);
  }

  private MockMultipartFile jpeg() {
    return new MockMultipartFile("file", "damage.jpg", "image/jpeg", JPEG_BYTES);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      upload(long disputeId, User caller) {
    return uploadFile(disputeId, caller, png());
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      uploadFile(long disputeId, User caller, MockMultipartFile file) {
    return multipart("/api/disputes/{id}/evidence", disputeId)
        .file(file)
        .header("Authorization", "Bearer " + token(caller));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      download(long disputeId, long evidenceId, User caller) {
    return get("/api/disputes/{d}/evidence/{e}/download", disputeId, evidenceId)
        .header("Authorization", "Bearer " + token(caller));
  }

  private static String fileNameOf(String url) {
    return url.substring("/uploads/".length());
  }

  @Test
  void buyerUploads_sellerAndAdminReadAndAdd() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    long disputeId = openDispute(buyer, seller);

    String body = mockMvc.perform(upload(disputeId, buyer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.disputeId").value(disputeId))
        .andExpect(jsonPath("$.data.uploaderId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.url").value(
            org.hamcrest.Matchers.matchesPattern("/uploads/[0-9a-f-]+\\.png")))
        .andReturn().getResponse().getContentAsString();
    String url = JsonPath.read(body, "$.data.url");
    assertThat(UPLOAD_DIR.resolve(fileNameOf(url))).exists();

    // The seller and an ADMIN see the buyer's photo and can add their own.
    mockMvc.perform(get("/api/disputes/{id}/evidence", disputeId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].url").value(url));
    mockMvc.perform(upload(disputeId, seller)).andExpect(status().isOk());
    mockMvc.perform(upload(disputeId, admin)).andExpect(status().isOk());
    mockMvc.perform(get("/api/disputes/{id}/evidence", disputeId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3));
  }

  @Test
  void strangerGets404ForReadUploadAndByOrderRead() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    long disputeId = openDispute(buyer, seller);
    long orderId = disputes.findById(disputeId).orElseThrow().getOrderId();

    mockMvc.perform(get("/api/disputes/{id}/evidence", disputeId)
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(upload(disputeId, stranger))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/orders/{id}/dispute", orderId)
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isNotFound());
    assertThat(evidence.findAll()).isEmpty();
  }

  @Test
  void anonymousGets401_unknownDisputeGets404() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    long disputeId = openDispute(buyer, seller);

    mockMvc.perform(get("/api/disputes/{id}/evidence", disputeId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(get("/api/disputes/{id}/evidence", 999_999L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());
  }

  @Test
  void badMagicBytesGets400_leavesNoFileAndNoRow() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    long disputeId = openDispute(buyer, seller);
    MockMultipartFile spoofed = new MockMultipartFile(
        "file", "fake.png", "image/png", "not a png at all".getBytes());

    mockMvc.perform(multipart("/api/disputes/{id}/evidence", disputeId)
            .file(spoofed)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(evidence.findAll()).isEmpty();
    if (Files.exists(UPLOAD_DIR)) {
      try (var stream = Files.list(UPLOAD_DIR)) {
        assertThat(stream.toList()).isEmpty();
      }
    }
  }

  @Test
  void resolvedDisputeRefusesNewEvidence422() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    long disputeId = openDispute(buyer, seller);
    mockMvc.perform(post("/api/admin/disputes/{id}/resolve-release", disputeId)
            .header("Authorization", "Bearer " + token(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"resolutionNote\":\"Evidence test release.\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(upload(disputeId, buyer))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
    assertThat(evidence.findAll()).isEmpty();
  }

  @Test
  void removeByUploaderDeletesRowAndFile_otherPartyGets403_crossDisputeGets404()
      throws Exception {
    User seller = newUser();
    User buyer = newUser();
    long disputeId = openDispute(buyer, seller);
    long otherDisputeId = openDispute(buyer, seller);

    String body = mockMvc.perform(upload(disputeId, buyer))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long evidenceId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    String url = JsonPath.read(body, "$.data.url");
    Path file = UPLOAD_DIR.resolve(fileNameOf(url));
    assertThat(file).exists();

    // The evidence id under another dispute's URL is a 404, never a
    // cross-dispute delete.
    mockMvc.perform(delete("/api/disputes/{d}/evidence/{e}", otherDisputeId, evidenceId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());
    // The seller may view the buyer's evidence but not remove it.
    mockMvc.perform(delete("/api/disputes/{d}/evidence/{e}", disputeId, evidenceId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    assertThat(evidence.findById(evidenceId)).isPresent();
    assertThat(file).exists();

    // The uploader removes it: row gone, file gone (orphan cleanup).
    mockMvc.perform(delete("/api/disputes/{d}/evidence/{e}", disputeId, evidenceId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
    assertThat(evidence.findById(evidenceId)).isEmpty();
    assertThat(file).doesNotExist();
  }

  private java.util.List<AuditLog> auditRowsForEvidence(long evidenceId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
        AuditTargetType.DISPUTE_EVIDENCE, evidenceId);
  }

  @Test
  void uploadAndDeleteWriteAuditRowsWithEvidenceIdOnly() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    long disputeId = openDispute(buyer, seller);
    long orderId = disputes.findById(disputeId).orElseThrow().getOrderId();

    String body = mockMvc.perform(upload(disputeId, buyer))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long evidenceId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

    // The upload writes exactly one row: actor is the uploader, target is
    // the evidence row id — the only identifier the trail stores (no file
    // bytes, no /uploads/ URL: the audit table has no payload column).
    var uploaded = auditRowsForEvidence(evidenceId);
    assertThat(uploaded).hasSize(1);
    assertThat(uploaded.get(0).getAction()).isEqualTo(AuditAction.DISPUTE_EVIDENCE_UPLOADED);
    assertThat(uploaded.get(0).getActorUserId()).isEqualTo(buyer.getId());
    assertThat(uploaded.get(0).getTargetType()).isEqualTo(AuditTargetType.DISPUTE_EVIDENCE);
    // The dispute id and order id are one lookup away through the evidence
    // row and the dispute row (one dispute per order, #169).
    assertThat(evidence.findById(evidenceId).orElseThrow().getDisputeId()).isEqualTo(disputeId);
    assertThat(disputes.findById(disputeId).orElseThrow().getOrderId()).isEqualTo(orderId);

    // The uploader removes it: exactly one delete row, same target id —
    // and the row survives its evidence row, so a deleted photo still
    // leaves a trail where before #413 it left none.
    mockMvc.perform(delete("/api/disputes/{d}/evidence/{e}", disputeId, evidenceId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
    assertThat(evidence.findById(evidenceId)).isEmpty();
    var rows = auditRowsForEvidence(evidenceId);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).getAction()).isEqualTo(AuditAction.DISPUTE_EVIDENCE_DELETED);
    assertThat(rows.get(1).getActorUserId()).isEqualTo(buyer.getId());
    assertThat(rows.get(1).getTargetType()).isEqualTo(AuditTargetType.DISPUTE_EVIDENCE);
  }

  @Test
  void failedUploadAndRefusedDeleteWriteNoAuditRow() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    long disputeId = openDispute(buyer, seller);

    // A rejected upload (bad magic bytes) writes no row.
    MockMultipartFile spoofed = new MockMultipartFile(
        "file", "fake.png", "image/png", "not a png at all".getBytes());
    mockMvc.perform(multipart("/api/disputes/{id}/evidence", disputeId)
            .file(spoofed)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isBadRequest());
    assertThat(auditLog.findAll().stream()
        .filter(a -> a.getAction() == AuditAction.DISPUTE_EVIDENCE_UPLOADED)
        .toList()).isEmpty();

    // A refused delete (the other party, 403) writes no row either, and a
    // stranger's 404 upload attempt writes nothing.
    String body = mockMvc.perform(upload(disputeId, buyer))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long evidenceId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    mockMvc.perform(delete("/api/disputes/{d}/evidence/{e}", disputeId, evidenceId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden());
    assertThat(auditRowsForEvidence(evidenceId)).hasSize(1); // only the upload row
    User stranger = newUser();
    mockMvc.perform(upload(disputeId, stranger)).andExpect(status().isNotFound());
    assertThat(auditRowsForEvidence(evidenceId)).hasSize(1);
  }

  @Test
  void byOrderReadReturnsDisputeToBuyerSellerAndAdmin() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    long disputeId = openDispute(buyer, seller);
    long orderId = disputes.findById(disputeId).orElseThrow().getOrderId();

    for (User caller : java.util.List.of(buyer, seller, admin)) {
      mockMvc.perform(get("/api/orders/{id}/dispute", orderId)
              .header("Authorization", "Bearer " + token(caller)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.id").value(disputeId))
          .andExpect(jsonPath("$.data.status").value("OPEN"));
    }
    // An order with no dispute is the same 404 for its own buyer.
    User lonely = newUser();
    Item item = new Item("Desk lamp", "Brass", 5_000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    items.save(item);
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(lonely))
            .header("Idempotency-Key", "evidence-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long undisputedOrderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    mockMvc.perform(get("/api/orders/{id}/dispute", undisputedOrderId)
            .header("Authorization", "Bearer " + token(lonely)))
        .andExpect(status().isNotFound());
  }

  @Test
  void downloadAuthzMatrix_partiesGetBytes_stranger404_anonymous401() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User admin = newAdmin();
    User stranger = newUser();
    long disputeId = openDispute(buyer, seller);
    long otherDisputeId = openDispute(buyer, seller);

    String body = mockMvc.perform(upload(disputeId, buyer))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long evidenceId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

    // Every party downloads the stored bytes with the stored type —
    // the success is the image itself, not the envelope.
    for (User caller : java.util.List.of(buyer, seller, admin)) {
      mockMvc.perform(download(disputeId, evidenceId, caller))
          .andExpect(status().isOk())
          .andExpect(content().contentType("image/png"))
          .andExpect(content().bytes(PNG_BYTES));
    }

    // A stranger gets the same 404 as an unknown id (no-existence-leak),
    // and an anonymous caller gets the chain's 401 envelope.
    mockMvc.perform(download(disputeId, evidenceId, stranger))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(get("/api/disputes/{d}/evidence/{e}/download", disputeId, evidenceId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // Unknown dispute, unknown evidence, and an evidence id from
    // another dispute are all the same 404.
    mockMvc.perform(download(999_999L, evidenceId, buyer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(download(disputeId, 999_999L, buyer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(download(otherDisputeId, evidenceId, buyer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void downloadPreservesJpegContentType() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    long disputeId = openDispute(buyer, seller);

    String body = mockMvc.perform(uploadFile(disputeId, buyer, jpeg()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.url").value(
            org.hamcrest.Matchers.matchesPattern("/uploads/[0-9a-f-]+\\.jpg")))
        .andReturn().getResponse().getContentAsString();
    long evidenceId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

    // The type comes from the stored extension, which the upload
    // verified by magic bytes — a JPEG downloads as image/jpeg.
    mockMvc.perform(download(disputeId, evidenceId, buyer))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/jpeg"))
        .andExpect(content().bytes(JPEG_BYTES));
  }

  @Test
  void downloadOfRowWithMissingFileGets404() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    long disputeId = openDispute(buyer, seller);

    String body = mockMvc.perform(upload(disputeId, buyer))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long evidenceId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    String url = JsonPath.read(body, "$.data.url");
    Path file = UPLOAD_DIR.resolve(fileNameOf(url));
    assertThat(file).exists();

    // The row survives but its file is gone (operator wipe, sweeper
    // race): the download is a 404, never a 500 — the row alone does
    // not prove a servable file.
    Files.deleteIfExists(file);
    mockMvc.perform(download(disputeId, evidenceId, buyer))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
