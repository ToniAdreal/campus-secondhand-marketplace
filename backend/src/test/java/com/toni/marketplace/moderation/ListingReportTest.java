package com.toni.marketplace.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listing reports + ADMIN moderation queue (backlog #160):
 * POST /api/items/{id}/report files a Flyway-backed report (own listing
 * → 422, one OPEN report per reporter+item — a repeat returns the
 * existing row), and the ADMIN-only queue reads it newest-first with a
 * status filter and resolves it via dismiss / action (action withdraws
 * the listing in the same transaction; both terminal transitions are
 * idempotent, the opposite transition on a resolved report is 422).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingReportTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private ListingReportRepository reports;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User seller;
  private String sellerToken;
  private String reporterToken;
  private String otherToken;
  private String adminToken;
  private Long reporterId;
  private Item item;

  @BeforeEach
  void createUsersItemAndTokens() {
    String tag = "rep-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    User reporter = users.save(new User(tag + "-reporter", tag + "-reporter@example.com",
        passwords.encode("reporter-password")));
    User other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("other-password")));
    User admin = new User(tag + "-admin", tag + "-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    reporterId = reporter.getId();
    sellerToken = jwt.createTokenPair(seller).accessToken();
    reporterToken = jwt.createTokenPair(reporter).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
    item = itemRepo.save(new Item("Report seed " + tag, "seeded", 4200L, seller.getId()));
  }

  private String reportBody(String reason, String note) {
    return note == null
        ? "{\"reason\":\"" + reason + "\"}"
        : "{\"reason\":\"" + reason + "\",\"note\":\"" + note + "\"}";
  }

  @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  private long fileReport(String token, String reason) throws Exception {
    String body = mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reportBody(reason, "looks suspicious")))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).path("data").path("id").asLong();
  }

  // ---- report creation -------------------------------------------------

  @Test
  void reportIsFiledOpenWithReporterReasonAndNote() throws Exception {
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + reporterToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reportBody("SCAM", "asks for payment off-platform")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.reporterId").value(reporterId))
        .andExpect(jsonPath("$.data.itemId").value(item.getId()))
        .andExpect(jsonPath("$.data.reason").value("SCAM"))
        .andExpect(jsonPath("$.data.note").value("asks for payment off-platform"))
        .andExpect(jsonPath("$.data.status").value("OPEN"))
        .andExpect(jsonPath("$.data.createdAt").exists());
    assertThat(reports.count()).isEqualTo(1);
  }

  @Test
  void noteIsOptional() throws Exception {
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + reporterToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reportBody("SPAM", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.note").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void anonymousReportIs401() throws Exception {
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(reportBody("SPAM", null)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    assertThat(reports.count()).isZero();
  }

  @Test
  void reportingYourOwnListingIs422() throws Exception {
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reportBody("OTHER", null)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
    assertThat(reports.count()).isZero();
  }

  @Test
  void unknownListingIs404() throws Exception {
    mockMvc.perform(post("/api/items/{id}/report", 999_999L)
            .header("Authorization", "Bearer " + reporterToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reportBody("SPAM", null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void missingOrInvalidReasonIs400() throws Exception {
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + reporterToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + reporterToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reportBody("RUDE", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    assertThat(reports.count()).isZero();
  }

  @Test
  void repeatReportReturnsTheExistingOpenRowNoDuplicate() throws Exception {
    long first = fileReport(reporterToken, "SPAM");

    // A repeat — even with a different reason/note — returns the existing
    // OPEN row untouched instead of inserting a second one.
    mockMvc.perform(post("/api/items/{id}/report", item.getId())
            .header("Authorization", "Bearer " + reporterToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(reportBody("SCAM", "changed my mind")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(first))
        .andExpect(jsonPath("$.data.reason").value("SPAM"));

    // A different reporter is a different (reporter, item) pair — a real
    // second row.
    fileReport(otherToken, "SCAM");
    assertThat(reports.count()).isEqualTo(2);
  }

  @Test
  void reReportAfterDismissalFilesAFreshRow() throws Exception {
    long first = fileReport(reporterToken, "SPAM");
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", first)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    long second = fileReport(reporterToken, "SPAM");
    assertThat(second).isNotEqualTo(first);
    assertThat(reports.count()).isEqualTo(2);
  }

  // ---- admin queue ------------------------------------------------------

  @Test
  void adminQueueIsNewestFirstAndFiltersByStatus() throws Exception {
    long first = fileReport(reporterToken, "SPAM");
    long second = fileReport(otherToken, "SCAM");
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", first)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/admin/reports")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].id").value(second))
        .andExpect(jsonPath("$.data.content[1].id").value(first));

    mockMvc.perform(get("/api/admin/reports").param("status", "OPEN")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(second));

    mockMvc.perform(get("/api/admin/reports").param("status", "DISMISSED")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].id").value(first));

    mockMvc.perform(get("/api/admin/reports").param("status", "BOGUS")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void adminQueueRejectsNonAdminAndAnonymous() throws Exception {
    fileReport(reporterToken, "SPAM");
    mockMvc.perform(get("/api/admin/reports")
            .header("Authorization", "Bearer " + reporterToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    mockMvc.perform(get("/api/admin/reports"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  // ---- dismiss / action --------------------------------------------------

  @Test
  void dismissIsTerminalIdempotentAndLeavesTheListingAlone() throws Exception {
    long id = fileReport(reporterToken, "OTHER");

    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", id)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DISMISSED"));
    // Idempotent repeat.
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", id)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DISMISSED"));
    // The opposite transition on a resolved report is 422, not a rewrite.
    mockMvc.perform(post("/api/admin/reports/{id}/action", id)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void actionWithdrawsTheListingInTheSameTransaction() throws Exception {
    long id = fileReport(reporterToken, "PROHIBITED");

    mockMvc.perform(post("/api/admin/reports/{id}/action", id)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIONED"));

    // The listing is withdrawn — visible both in the row and the detail view.
    assertThat(itemRepo.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.WITHDRAWN);
    mockMvc.perform(get("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + reporterToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));

    // Idempotent repeat; the opposite transition is 422.
    mockMvc.perform(post("/api/admin/reports/{id}/action", id)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIONED"));
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", id)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
  }

  @Test
  void moderationTransitionsRejectNonAdminAndUnknownIds() throws Exception {
    long id = fileReport(reporterToken, "SPAM");
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", id)
            .header("Authorization", "Bearer " + reporterToken))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/admin/reports/{id}/action", id)
            .header("Authorization", "Bearer " + reporterToken))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/admin/reports/{id}/dismiss", 999_999L)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(post("/api/admin/reports/{id}/action", 999_999L)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    // Nothing moved.
    assertThat(reports.findById(id).orElseThrow().getStatus()).isEqualTo(ReportStatus.OPEN);
  }
}
