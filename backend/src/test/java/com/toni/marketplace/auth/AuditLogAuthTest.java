package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLog;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backlog #98 — audit rows for the auth-side transitions, end-to-end via
 * MockMvc: password change, TOTP enable, and ADMIN disable/enable each
 * append exactly one row with the right actor/action/target; failed
 * attempts and idempotent no-op repeats append none.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and audit rows it creates
class AuditLogAuthTest {

  private static final AtomicInteger IP_SEQ = new AtomicInteger();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private TotpService totp;

  @Autowired
  private JwtTokenService jwt;

  /** Distinct client IPs per call keep the per-IP rate-limit buckets out of the way. */
  private static RequestPostProcessor uniqueIp() {
    return request -> {
      request.setRemoteAddr("10.98.0." + IP_SEQ.incrementAndGet());
      return request;
    };
  }

  private MvcResult register(String username) throws Exception {
    return mockMvc.perform(post("/api/auth/register")
            .with(uniqueIp())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"s3cret-pass\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private static String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
  }

  private static long userId(MvcResult result) throws Exception {
    return ((Number) JsonPath.read(result.getResponse().getContentAsString(),
        "$.data.user.id")).longValue();
  }

  private List<AuditLog> rowsFor(AuditTargetType type, long targetId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(type, targetId);
  }

  @Test
  void passwordChangeWritesExactlyOneRow() throws Exception {
    MvcResult registered = register("audit-alice");
    long aliceId = userId(registered);
    String access = accessToken(registered);

    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"s3cret-pass\",\"newPassword\":\"n3w-s3cret\"}"))
        .andExpect(status().isOk());

    List<AuditLog> rows = rowsFor(AuditTargetType.USER, aliceId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.PASSWORD_CHANGED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(aliceId);
    assertThat(rows.get(0).getCreatedAt()).isNotNull();
  }

  @Test
  void failedPasswordChangeWritesNoRow() throws Exception {
    MvcResult registered = register("audit-bob");
    long bobId = userId(registered);
    String access = accessToken(registered);

    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"wrong-pass\",\"newPassword\":\"n3w-s3cret\"}"))
        .andExpect(status().isUnauthorized());

    assertThat(rowsFor(AuditTargetType.USER, bobId)).isEmpty();
  }

  @Test
  void totpEnableWritesExactlyOneRow() throws Exception {
    MvcResult registered = register("audit-carol");
    long carolId = userId(registered);
    String access = accessToken(registered);

    MvcResult setup = mockMvc.perform(post("/api/auth/2fa/setup")
            .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andReturn();
    String secret = JsonPath.read(setup.getResponse().getContentAsString(), "$.data.secret");

    mockMvc.perform(post("/api/auth/2fa/enable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totp.codeAt(secret, Instant.now()) + "\"}"))
        .andExpect(status().isOk());

    List<AuditLog> rows = rowsFor(AuditTargetType.USER, carolId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.TOTP_ENABLED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(carolId);
  }

  @Test
  void adminDisableAndEnableWriteRowsWithAdminActor_andRepeatsWriteNone() throws Exception {
    User admin = new User("audit-admin", "audit-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    User victim = users.save(new User("audit-victim", "audit-victim@example.com",
        passwords.encode("victim-password")));

    String adminToken = jwt.createTokenPair(admin).accessToken();
    long victimId = victim.getId();

    // Disable: one USER_DISABLED row, actor = the admin.
    mockMvc.perform(post("/api/admin/users/{id}/disable", victimId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
    // Re-disable is an idempotent no-op: still exactly one row.
    mockMvc.perform(post("/api/admin/users/{id}/disable", victimId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    List<AuditLog> rows = rowsFor(AuditTargetType.USER, victimId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAction()).isEqualTo(AuditAction.USER_DISABLED);
    assertThat(rows.get(0).getActorUserId()).isEqualTo(admin.getId());

    // Enable: one USER_ENABLED row with the same admin actor.
    mockMvc.perform(post("/api/admin/users/{id}/enable", victimId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
    // Re-enable is a no-op too.
    mockMvc.perform(post("/api/admin/users/{id}/enable", victimId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    rows = rowsFor(AuditTargetType.USER, victimId);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).getAction()).isEqualTo(AuditAction.USER_ENABLED);
    assertThat(rows.get(1).getActorUserId()).isEqualTo(admin.getId());
  }
}
