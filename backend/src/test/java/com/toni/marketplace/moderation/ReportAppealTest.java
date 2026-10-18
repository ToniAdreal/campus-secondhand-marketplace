package com.toni.marketplace.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
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
import com.toni.marketplace.notification.NotificationType;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Report appeals (backlog #190): the seller files an appeal on an
 * ACTIONED report (seller-only, ACTIONED-only, one live appeal per
 * report, one appeal per report ever), reads it back through the
 * seller surfaces, and an ADMIN resolves it — uphold restores a still-
 * WITHDRAWN listing and records {@code listingRestored}, leaves a
 * moved-on listing (and its in-flight order) untouched, reject leaves
 * the withdrawal standing — with audit + notification side effects in
 * the same transaction and the dispute-style idempotency rules on
 * repeat resolutions.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code OrderDisputeTest}: rows must be committed by the requests
 * themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportAppealTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private ListingReportRepository reports;
  @Autowired private ReportAppealRepository appeals;
  @Autowired private OrderRepository orders;
  @Autowired private IdempotencyKeyRepository keys;
  @Autowired private NotificationRepository notifications;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User seller;
  private User reporter;
  private User other;
  private User admin;
  private Item item;

  @BeforeEach
  void createUsersAndItem() {
    String tag = "appeal-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    reporter = users.save(new User(tag + "-reporter", tag + "-reporter@example.com",
        passwords.encode("reporter-password")));
    other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("other-password")));
    User adminUser = new User(tag + "-admin", tag + "-admin@example.com",
        passwords.encode("admin-password"));
    adminUser.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(adminUser);
    item = itemRepo.save(new Item("Appeal seed " + tag, "seeded", 4200L, seller.getId()));
  }

  @AfterEach
  void cleanUp() {
    appeals.deleteAll();
    reports.deleteAll();
    notifications.deleteAll();
    auditLog.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    itemRepo.deleteAll();
    users.deleteAll();
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private long fileReport(User by, Item on) throws Exception {
    String body = mockMvc.perform(post("/api/items/{id}/report", on.getId())
            .header("Authorization", "Bearer " + token(by))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SCAM\",\"note\":\"looks suspicious\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).path("data").path("id").asLong();
  }

  /** Files a report and has an ADMIN action it: an ACTIONED report on a WITHDRAWN listing. */
  private long actionedReport(Item on) throws Exception {
    long reportId = fileReport(reporter, on);
    mockMvc.perform(post("/api/admin/reports/{id}/action", reportId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIONED"));
    return reportId;
  }

  private long fileAppeal(User by, long reportId, String reason) throws Exception {
    String body = mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(by))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + reason + "\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).path("data").path("id").asLong();
  }

  private java.util.List<NotificationType> notificationTypesFor(Long recipientId) {
    return notifications.findByRecipientId(recipientId, PageRequest.of(0, 50))
        .map(n -> n.getType()).getContent();
  }

  private java.util.List<AuditAction> auditActionsFor(long appealId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(
            AuditTargetType.REPORT_APPEAL, appealId)
        .stream().map(a -> a.getAction()).toList();
  }

  // ---- filing -----------------------------------------------------------

  @Test
  void sellerFilesAppealOnActionedReport() throws Exception {
    long reportId = actionedReport(item);
    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.WITHDRAWN);

    mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"this is my own textbook, sold once\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reportId").value(reportId))
        .andExpect(jsonPath("$.data.itemId").value(item.getId()))
        .andExpect(jsonPath("$.data.sellerId").value(seller.getId()))
        .andExpect(jsonPath("$.data.status").value("OPEN"))
        .andExpect(jsonPath("$.data.listingRestored").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.resolvedAt").value(org.hamcrest.Matchers.nullValue()));

    assertThat(appeals.count()).isEqualTo(1);
    long appealId = appeals.findAll().get(0).getId();
    assertThat(auditActionsFor(appealId)).containsExactly(AuditAction.REPORT_APPEAL_OPENED);
    // Filing notifies nobody — only resolutions notify the seller.
    assertThat(notificationTypesFor(seller.getId())).isEmpty();
  }

  @Test
  void appealByNonSellerIs403Anonymous401UnknownReport404() throws Exception {
    long reportId = actionedReport(item);

    mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(reporter))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"not my listing\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(other))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"not my listing either\"}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"anonymous\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    mockMvc.perform(post("/api/seller/reports/{id}/appeal", 999_999L)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"no such report\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    assertThat(appeals.count()).isZero();
  }

  @Test
  void appealOnOpenOrDismissedReportIs422() throws Exception {
    long openReport = fileReport(reporter, item);
    mockMvc.perform(post("/api/seller/reports/{id}/appeal", openReport)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"not decided yet\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", openReport)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/seller/reports/{id}/appeal", openReport)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"nothing was taken\"}"))
        .andExpect(status().isUnprocessableEntity());
    assertThat(appeals.count()).isZero();
  }

  @Test
  void secondPostWhileOpenReturnsSameAppealNoDuplicateSideEffects() throws Exception {
    long reportId = actionedReport(item);
    long first = fileAppeal(seller, reportId, "first attempt");
    long second = fileAppeal(seller, reportId, "retry with different words");

    assertThat(second).isEqualTo(first);
    assertThat(appeals.count()).isEqualTo(1);
    assertThat(auditActionsFor(first)).containsExactly(AuditAction.REPORT_APPEAL_OPENED);
  }

  @Test
  void reAppealAfterResolutionIs422() throws Exception {
    long reportId = actionedReport(item);
    long appealId = fileAppeal(seller, reportId, "please reconsider");
    mockMvc.perform(post("/api/admin/appeals/{id}/reject", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"second bite\"}"))
        .andExpect(status().isUnprocessableEntity());
    assertThat(appeals.count()).isEqualTo(1);
  }

  @Test
  void blankOrOverlongReasonIs400() throws Exception {
    long reportId = actionedReport(item);
    mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"  \"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
        .andExpect(status().isBadRequest());
    assertThat(appeals.count()).isZero();
  }

  // ---- seller reads -------------------------------------------------------

  @Test
  void sellerReadsAppealStateAndLists() throws Exception {
    long reportId = actionedReport(item);
    long appealId = fileAppeal(seller, reportId, "please reconsider");

    mockMvc.perform(get("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(appealId))
        .andExpect(jsonPath("$.data.status").value("OPEN"));
    // A non-seller read is 403 before any state leaks.
    mockMvc.perform(get("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(other)))
        .andExpect(status().isForbidden());

    mockMvc.perform(get("/api/seller/reports")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(reportId))
        .andExpect(jsonPath("$.data.content[0].status").value("ACTIONED"))
        .andExpect(jsonPath("$.data.content[0].appealId").value(appealId))
        .andExpect(jsonPath("$.data.content[0].appealStatus").value("OPEN"));
    // The reporter sees none of the seller's report surface.
    mockMvc.perform(get("/api/seller/reports")
            .header("Authorization", "Bearer " + token(reporter)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));

    mockMvc.perform(get("/api/seller/appeals")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(appealId));
  }

  @Test
  void getAppealForUnappealedReportIs404() throws Exception {
    long reportId = actionedReport(item);
    mockMvc.perform(get("/api/seller/reports/{id}/appeal", reportId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/seller/reports/{id}/appeal", 999_999L)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound());
  }

  // ---- ADMIN resolution ---------------------------------------------------

  @Test
  void upholdRestoresWithdrawnListingNotifiesSellerAuditsIdempotent() throws Exception {
    long reportId = actionedReport(item);
    long appealId = fileAppeal(seller, reportId, "please reconsider");

    mockMvc.perform(post("/api/admin/appeals/{id}/uphold", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UPHELD"))
        .andExpect(jsonPath("$.data.listingRestored").value(true))
        .andExpect(jsonPath("$.data.resolvedAt").exists());

    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
    assertThat(notificationTypesFor(seller.getId()))
        .containsExactly(NotificationType.REPORT_APPEAL_UPHELD);
    assertThat(auditActionsFor(appealId))
        .containsExactly(AuditAction.REPORT_APPEAL_OPENED, AuditAction.REPORT_APPEAL_UPHELD);

    // Idempotent re-uphold: no second notification or audit row.
    mockMvc.perform(post("/api/admin/appeals/{id}/uphold", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UPHELD"));
    assertThat(notificationTypesFor(seller.getId()))
        .containsExactly(NotificationType.REPORT_APPEAL_UPHELD);
    assertThat(auditActionsFor(appealId))
        .filteredOn(a -> a == AuditAction.REPORT_APPEAL_UPHELD).hasSize(1);

    // The opposite resolution on a resolved appeal is 422.
    mockMvc.perform(post("/api/admin/appeals/{id}/reject", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void upholdWhenListingMovedOnLeavesItAndItsOrderUntouched() throws Exception {
    // A RESERVED listing (live order): actioning its report leaves it
    // RESERVED, so the ACTIONED report's listing is not WITHDRAWN.
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(other))
            .header("Idempotency-Key", "appeal-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) JsonPath.read(body, "$.data.id")).longValue();
    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);

    long reportId = actionedReport(item);
    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
    long appealId = fileAppeal(seller, reportId, "the order is live, listing is fine");

    mockMvc.perform(post("/api/admin/appeals/{id}/uphold", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UPHELD"))
        .andExpect(jsonPath("$.data.listingRestored").value(false));

    // The listing stays RESERVED and the in-flight order is untouched.
    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void rejectLeavesListingWithdrawnNotifiesSellerIdempotent() throws Exception {
    long reportId = actionedReport(item);
    long appealId = fileAppeal(seller, reportId, "please reconsider");

    mockMvc.perform(post("/api/admin/appeals/{id}/reject", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REJECTED"))
        .andExpect(jsonPath("$.data.listingRestored").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.resolvedAt").exists());

    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.WITHDRAWN);
    assertThat(notificationTypesFor(seller.getId()))
        .containsExactly(NotificationType.REPORT_APPEAL_REJECTED);
    assertThat(auditActionsFor(appealId))
        .containsExactly(AuditAction.REPORT_APPEAL_OPENED, AuditAction.REPORT_APPEAL_REJECTED);

    // Idempotent re-reject; the opposite resolution is 422.
    mockMvc.perform(post("/api/admin/appeals/{id}/reject", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk());
    assertThat(notificationTypesFor(seller.getId()))
        .containsExactly(NotificationType.REPORT_APPEAL_REJECTED);
    mockMvc.perform(post("/api/admin/appeals/{id}/uphold", appealId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void adminQueueRequiresAdminFiltersByStatusUnknownAppeal404() throws Exception {
    long reportId = actionedReport(item);
    long appealId = fileAppeal(seller, reportId, "please reconsider");

    mockMvc.perform(get("/api/admin/appeals")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(appealId))
        .andExpect(jsonPath("$.data.content[0].itemId").value(item.getId()));
    mockMvc.perform(get("/api/admin/appeals").param("status", "UPHELD")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    mockMvc.perform(get("/api/admin/appeals").param("status", "BOGUS")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/admin/appeals")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/appeals"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/admin/appeals/{id}/uphold", appealId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/admin/appeals/{id}/uphold", 999_999L)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/admin/appeals/{id}/reject", 999_999L)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isNotFound());
    // Nothing moved.
    assertThat(appeals.findById(appealId).orElseThrow().getStatus())
        .isEqualTo(ReportAppealStatus.OPEN);
  }
}
