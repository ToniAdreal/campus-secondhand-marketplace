package com.toni.marketplace.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Moderation-resolution audit rows (backlog #397): dismiss and action
 * each write exactly one row to the append-only trail from inside the
 * transition's own transaction, with the resolving ADMIN as actor —
 * the report row as target on dismiss, the listing as target on
 * action. The idempotent replays return before the record call and
 * write none, and the 422 opposite-transition writes none. The ADMIN
 * audit-log read picks the rows up through its existing filters.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring {@link
 * com.toni.marketplace.review.ReviewAuditTest}: the rows must be
 * committed by the requests themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportResolutionAuditTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private ListingReportRepository reports;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  @AfterEach
  void cleanUp() {
    auditLog.deleteAll();
    reports.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = "report-audit-" + role + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    String tag = "report-audit-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item seedItem(User seller) {
    return items.save(new Item("Reported thing " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 4200L, seller.getId()));
  }

  private long fileReport(User reporter, long itemId) throws Exception {
    String body = mockMvc.perform(post("/api/items/{id}/report", itemId)
            .header("Authorization", "Bearer " + token(reporter))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\",\"note\":\"looks suspicious\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).path("data").path("id").asLong();
  }

  private void dismiss(User admin, long reportId) throws Exception {
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", reportId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk());
  }

  private void action(User admin, long reportId) throws Exception {
    mockMvc.perform(post("/api/admin/reports/{id}/action", reportId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk());
  }

  private List<AuditLog> rowsFor(AuditTargetType targetType, long targetId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(targetType, targetId);
  }

  @Test
  void dismiss_writesOneReportDismissedRow_actorIsAdmin() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User reporter = newUser("reporter");
    Item item = seedItem(seller);
    long reportId = fileReport(reporter, item.getId());

    dismiss(admin, reportId);

    assertThat(rowsFor(AuditTargetType.REPORT, reportId)).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.REPORT_DISMISSED);
      assertThat(row.getActorUserId()).isEqualTo(admin.getId());
      assertThat(row.getTargetType()).isEqualTo(AuditTargetType.REPORT);
      assertThat(row.getTargetId()).isEqualTo(reportId);
    });
  }

  @Test
  void action_writesOneListingHiddenRow_actorIsAdmin_andWithdrawsListing() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User reporter = newUser("reporter");
    Item item = seedItem(seller);
    long reportId = fileReport(reporter, item.getId());

    action(admin, reportId);

    assertThat(rowsFor(AuditTargetType.ITEM, item.getId())).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.LISTING_HIDDEN);
      assertThat(row.getActorUserId()).isEqualTo(admin.getId());
      assertThat(row.getTargetType()).isEqualTo(AuditTargetType.ITEM);
      assertThat(row.getTargetId()).isEqualTo(item.getId());
    });
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.WITHDRAWN);
  }

  @Test
  void reDismiss_writesNoSecondRow() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User reporter = newUser("reporter");
    Item item = seedItem(seller);
    long reportId = fileReport(reporter, item.getId());

    dismiss(admin, reportId);
    dismiss(admin, reportId);

    assertThat(rowsFor(AuditTargetType.REPORT, reportId)).hasSize(1);
    assertThat(rowsFor(AuditTargetType.REPORT, reportId).get(0).getAction())
        .isEqualTo(AuditAction.REPORT_DISMISSED);
  }

  @Test
  void reAction_writesNoSecondRow() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User reporter = newUser("reporter");
    Item item = seedItem(seller);
    long reportId = fileReport(reporter, item.getId());

    action(admin, reportId);
    action(admin, reportId);

    assertThat(rowsFor(AuditTargetType.ITEM, item.getId())).hasSize(1);
    assertThat(rowsFor(AuditTargetType.ITEM, item.getId()).get(0).getAction())
        .isEqualTo(AuditAction.LISTING_HIDDEN);
  }

  @Test
  void oppositeTransition422_writesNoRow() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User reporter = newUser("reporter");
    Item item = seedItem(seller);
    long reportId = fileReport(reporter, item.getId());

    dismiss(admin, reportId);
    mockMvc.perform(post("/api/admin/reports/{id}/action", reportId)
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isUnprocessableEntity());

    assertThat(rowsFor(AuditTargetType.REPORT, reportId)).hasSize(1);
    assertThat(rowsFor(AuditTargetType.ITEM, item.getId())).isEmpty();
  }

  @Test
  void adminAuditLogRead_returnsNewActionsViaExistingFilters() throws Exception {
    User admin = newAdmin();
    User seller = newUser("seller");
    User reporter = newUser("reporter");
    Item dismissedItem = seedItem(seller);
    long dismissedId = fileReport(reporter, dismissedItem.getId());
    Item hiddenItem = seedItem(seller);
    long hiddenId = fileReport(reporter, hiddenItem.getId());

    dismiss(admin, dismissedId);
    action(admin, hiddenId);

    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + token(admin))
            .param("action", "REPORT_DISMISSED")
            .param("targetType", "REPORT")
            .param("targetId", String.valueOf(dismissedId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].action").value("REPORT_DISMISSED"))
        .andExpect(jsonPath("$.data.content[0].actorUserId").value(admin.getId()))
        .andExpect(jsonPath("$.data.content[0].targetType").value("REPORT"))
        .andExpect(jsonPath("$.data.content[0].targetId").value(dismissedId));

    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + token(admin))
            .param("action", "LISTING_HIDDEN")
            .param("targetType", "ITEM")
            .param("targetId", String.valueOf(hiddenItem.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].action").value("LISTING_HIDDEN"))
        .andExpect(jsonPath("$.data.content[0].actorUserId").value(admin.getId()))
        .andExpect(jsonPath("$.data.content[0].targetType").value("ITEM"))
        .andExpect(jsonPath("$.data.content[0].targetId").value(hiddenItem.getId()));
  }
}
