package com.toni.marketplace.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN audit-log read endpoint (backlog #109): GET /api/admin/audit-log —
 * paginated, newest first, optional exact-match filters actorUserId /
 * action / targetType+targetId (allowlisted enum values only). Rows are
 * the AuditLogDto projection — never the entity. ADMIN 200, normal user
 * 403, anonymous 401 envelope; an out-of-allowlist filter value is a 400
 * envelope, not a 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditLogReadTest {

  private static final Instant T1 = Instant.parse("2026-10-09T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-09T11:00:00Z");
  private static final Instant T3 = Instant.parse("2026-10-09T12:00:00Z");
  private static final Instant T4 = Instant.parse("2026-10-09T13:00:00Z");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private User admin;
  private User alice;
  private User carol;
  private String adminToken;
  private String userToken;
  private AuditLog newest;

  @BeforeEach
  void createUsersTokensAndRows() {
    admin = new User("aud-admin", "aud-admin@example.com", passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    alice = users.save(new User("aud-alice", "aud-alice@example.com",
        passwords.encode("alice-password")));
    carol = users.save(new User("aud-carol", "aud-carol@example.com",
        passwords.encode("carol-password")));

    adminToken = jwt.createTokenPair(admin).accessToken();
    userToken = jwt.createTokenPair(alice).accessToken();

    auditLog.save(new AuditLog(alice.getId(), AuditAction.PASSWORD_CHANGED,
        AuditTargetType.USER, alice.getId(), T1));
    auditLog.save(new AuditLog(alice.getId(), AuditAction.ORDER_COMPLETED,
        AuditTargetType.ORDER, 55L, T2));
    auditLog.save(new AuditLog(carol.getId(), AuditAction.ORDER_REFUNDED,
        AuditTargetType.ORDER, 55L, T3));
    newest = auditLog.save(new AuditLog(carol.getId(), AuditAction.USER_DISABLED,
        AuditTargetType.USER, 303L, T4));
  }

  @Test
  void adminListReturnsAllRowsNewestFirstAsDto() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalElements").value(4))
        .andExpect(jsonPath("$.data.content[0].id").value(newest.getId()))
        .andExpect(jsonPath("$.data.content[0].actorUserId").value(carol.getId()))
        .andExpect(jsonPath("$.data.content[0].action").value("USER_DISABLED"))
        .andExpect(jsonPath("$.data.content[0].targetType").value("USER"))
        .andExpect(jsonPath("$.data.content[0].targetId").value(303))
        .andExpect(jsonPath("$.data.content[0].createdAt").exists())
        .andExpect(jsonPath("$.data.content[3].action").value("PASSWORD_CHANGED"));
  }

  @Test
  void filterByActorReturnsOnlyThatActorsRows() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log").param("actorUserId", String.valueOf(alice.getId()))
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].action").value("ORDER_COMPLETED"))
        .andExpect(jsonPath("$.data.content[1].action").value("PASSWORD_CHANGED"));
  }

  @Test
  void filterByActionReturnsOnlyMatchingRows() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log").param("action", "ORDER_REFUNDED")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].actorUserId").value(carol.getId()));
  }

  @Test
  void filterByTargetTypeAndIdReturnsThatTargetsTrailNewestFirst() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log")
            .param("targetType", "ORDER").param("targetId", "55")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[0].action").value("ORDER_REFUNDED"))
        .andExpect(jsonPath("$.data.content[1].action").value("ORDER_COMPLETED"));
  }

  @Test
  void combinedFiltersIntersect() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log")
            .param("actorUserId", String.valueOf(carol.getId()))
            .param("action", "ORDER_COMPLETED")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0))
        .andExpect(jsonPath("$.data.content").isEmpty());

    mockMvc.perform(get("/api/admin/audit-log")
            .param("actorUserId", String.valueOf(carol.getId()))
            .param("action", "ORDER_REFUNDED")
            .param("targetType", "ORDER").param("targetId", "55")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].action").value("ORDER_REFUNDED"));
  }

  @Test
  void filterWithNoMatchReturnsEmptyPage() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log").param("actorUserId", "999999")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0))
        .andExpect(jsonPath("$.data.content").isEmpty());
  }

  @Test
  void paginationEnvelopeMirrorsAdminUserList() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log").param("page", "1").param("size", "3")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(4))
        .andExpect(jsonPath("$.data.totalPages").value(2))
        .andExpect(jsonPath("$.data.number").value(1))
        .andExpect(jsonPath("$.data.size").value(3))
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].action").value("PASSWORD_CHANGED"));
  }

  @Test
  void invalidActionValueIsBadRequestEnvelope() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log").param("action", "NOT_AN_ACTION")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void nonAdminListIsForbidden() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void anonymousListIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
