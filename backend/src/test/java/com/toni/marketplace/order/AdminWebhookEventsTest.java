package com.toni.marketplace.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN webhook-event viewer (backlog #156): GET
 * /api/admin/payments/webhooks — ADMIN-only, paginated newest-first
 * over the {@code webhook_event} ledger (#140), with an optional exact
 * outcome filter. Rows are seeded directly on the ledger so the exact
 * projection pins the DTO field set: id, eventId, eventType,
 * pspReference, outcome and createdAt — and nothing else (the ledger
 * stores no raw body or signature, and the test asserts no such field
 * names appear in the payload).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminWebhookEventsTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private WebhookEventRepository events;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private JdbcTemplate jdbc;

  private String adminToken;
  private String userToken;
  private String tag;

  @BeforeEach
  void createUsersAndTokens() {
    tag = "whv-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag + "-admin", tag + "-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    User user = users.save(new User(tag + "-user", tag + "-user@example.com",
        passwords.encode("user-password")));
    adminToken = jwt.createTokenPair(admin).accessToken();
    userToken = jwt.createTokenPair(user).accessToken();
  }

  private WebhookEvent seed(String suffix, String type, String reference,
                            WebhookEvent.Outcome outcome, Instant createdAt) {
    WebhookEvent row = events.saveAndFlush(
        new WebhookEvent(tag + "-" + suffix, type, reference, outcome));
    jdbc.update("update webhook_event set created_at = ? where id = ?",
        Timestamp.from(createdAt), row.getId());
    return row;
  }

  @Test
  void adminListsNewestFirstWithExactFieldSet() throws Exception {
    seed("old", "capture.succeeded", "cap_old", WebhookEvent.Outcome.MATCHED,
        Instant.parse("2026-01-10T00:00:00Z"));
    seed("new", "refund.succeeded", "rfd_new", WebhookEvent.Outcome.MISMATCH,
        Instant.parse("2026-01-12T00:00:00Z"));

    mockMvc.perform(get("/api/admin/payments/webhooks")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andExpect(jsonPath("$.data.content[0].eventId").value(tag + "-new"))
        .andExpect(jsonPath("$.data.content[0].eventType").value("refund.succeeded"))
        .andExpect(jsonPath("$.data.content[0].pspReference").value("rfd_new"))
        .andExpect(jsonPath("$.data.content[0].outcome").value("MISMATCH"))
        .andExpect(jsonPath("$.data.content[0].createdAt").exists())
        .andExpect(jsonPath("$.data.content[1].eventId").value(tag + "-old"))
        // Field-set pin: no body / signature / payload field can leak
        // (the ledger stores none of them — see WebhookEventDto).
        .andExpect(jsonPath("$.data.content[0].body").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].signature").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].payload").doesNotExist())
        .andExpect(jsonPath("$.data.content[0].rawBody").doesNotExist());
  }

  @Test
  void outcomeFilterNarrowsToExactMatches() throws Exception {
    seed("m1", "capture.succeeded", "cap_m1", WebhookEvent.Outcome.MATCHED,
        Instant.parse("2026-01-10T00:00:00Z"));
    seed("x1", "capture.succeeded", "cap_x1", WebhookEvent.Outcome.MISMATCH,
        Instant.parse("2026-01-11T00:00:00Z"));

    mockMvc.perform(get("/api/admin/payments/webhooks")
            .param("outcome", "MISMATCH")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].eventId").value(tag + "-x1"));

    mockMvc.perform(get("/api/admin/payments/webhooks")
            .param("outcome", "MATCHED")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].eventId").value(tag + "-m1"));
  }

  @Test
  void paginationEnvelopeSplitsPagesNewestFirst() throws Exception {
    seed("p1", "capture.succeeded", "cap_p1", WebhookEvent.Outcome.MATCHED,
        Instant.parse("2026-01-10T00:00:00Z"));
    seed("p2", "capture.succeeded", "cap_p2", WebhookEvent.Outcome.MATCHED,
        Instant.parse("2026-01-11T00:00:00Z"));
    seed("p3", "capture.succeeded", "cap_p3", WebhookEvent.Outcome.MATCHED,
        Instant.parse("2026-01-12T00:00:00Z"));

    mockMvc.perform(get("/api/admin/payments/webhooks")
            .param("size", "2").param("page", "0")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(2))
        .andExpect(jsonPath("$.data.content.length()").value(2))
        .andExpect(jsonPath("$.data.content[0].eventId").value(tag + "-p3"));

    mockMvc.perform(get("/api/admin/payments/webhooks")
            .param("size", "2").param("page", "1")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].eventId").value(tag + "-p1"));
  }

  @Test
  void unknownOutcomeFilterIs400Envelope() throws Exception {
    mockMvc.perform(get("/api/admin/payments/webhooks")
            .param("outcome", "APPLIED")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void emptyLedgerYieldsEmptyPageNotAnError() throws Exception {
    mockMvc.perform(get("/api/admin/payments/webhooks")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0))
        .andExpect(jsonPath("$.data.content.length()").value(0));
  }

  @Test
  void nonAdminGets403Envelope() throws Exception {
    mockMvc.perform(get("/api/admin/payments/webhooks")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void anonymousGets401Envelope() throws Exception {
    mockMvc.perform(get("/api/admin/payments/webhooks"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
