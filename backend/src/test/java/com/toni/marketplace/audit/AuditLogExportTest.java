package com.toni.marketplace.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN audit-log CSV export (backlog #232): GET
 * /api/admin/audit-log/export?format=csv — same ADMIN-only guard, same
 * optional exact-match filters and newest-first order as the list
 * endpoint (#109), but as a {@code text/csv} attachment with a dated
 * filename, capped at {@link AuditService#EXPORT_ROW_CAP} rows with a
 * truncation header when the filtered trail is longer. ADMIN 200 with
 * CSV header + filtered rows, non-ADMIN 403, anonymous 401 envelope,
 * cap enforced. The success answer streams
 * ({@code StreamingResponseBody}), so these tests dispatch async — and
 * the stream runs on the calling thread, not the MVC async pool
 * (backlog #362): the pool thread's stream flush commits the response,
 * which fires Spring Security's deferred header write, while the
 * first dispatch unwinds on the test thread and fires the same write
 * again — both into {@code MockHttpServletResponse}'s non-thread-safe
 * header map ({@code ConcurrentModificationException}). Running the
 * callable on the calling thread serialises the two writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditLogExportTest {

  /**
   * Runs MVC async work on the calling thread (backlog #362): Spring
   * Boot wires the MVC async executor from the bean named
   * {@code applicationTaskExecutor} — but only when it is an
   * {@code AsyncTaskExecutor} — so replacing that bean (and only in
   * these export tests' contexts) with a {@code TaskExecutorAdapter}
   * around a {@code SyncTaskExecutor} makes the
   * {@code StreamingResponseBody} callable execute inline during the
   * first dispatch — the stream flush, the response commit and the
   * filter-chain unwind then all happen on one thread, and the
   * deferred security-header write can no longer race itself.
   * ({@code SyncTaskExecutor} alone does not implement
   * {@code AsyncTaskExecutor}, so it would not be picked up at all:
   * Boot would silently fall back to the pool executor.)
   * Production is untouched: a real container owns its response.
   */
  @TestConfiguration
  static class SingleThreadedAsync {
    @Bean("applicationTaskExecutor")
    AsyncTaskExecutor applicationTaskExecutor() {
      return new TaskExecutorAdapter(new SyncTaskExecutor());
    }
  }

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

  @Autowired
  @Qualifier("applicationTaskExecutor")
  private AsyncTaskExecutor taskExecutor;

  private User admin;
  private User alice;
  private User carol;
  private String adminToken;
  private String userToken;
  private AuditLog refundedRow;

  @BeforeEach
  void createUsersTokensAndRows() {
    // The export reports table-wide totals, so committed audit rows
    // from other test classes sharing this context must not leak in (the
    // delete rolls back with this class's transaction, like
    // AuditLogReadTest already does).
    auditLog.deleteAll();
    admin = new User("aud-exp-admin", "aud-exp-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    alice = users.save(new User("aud-exp-alice", "aud-exp-alice@example.com",
        passwords.encode("alice-password")));
    carol = users.save(new User("aud-exp-carol", "aud-exp-carol@example.com",
        passwords.encode("carol-password")));

    adminToken = jwt.createTokenPair(admin).accessToken();
    userToken = jwt.createTokenPair(alice).accessToken();

    auditLog.save(new AuditLog(alice.getId(), AuditAction.PASSWORD_CHANGED,
        AuditTargetType.USER, alice.getId(), T1));
    auditLog.save(new AuditLog(alice.getId(), AuditAction.ORDER_COMPLETED,
        AuditTargetType.ORDER, 55L, T2));
    refundedRow = auditLog.save(new AuditLog(carol.getId(), AuditAction.ORDER_REFUNDED,
        AuditTargetType.ORDER, 55L, T3));
    auditLog.save(new AuditLog(carol.getId(), AuditAction.USER_DISABLED,
        AuditTargetType.USER, 303L, T4));
  }

  /** Runs the export through async dispatch and returns the finished response. */
  private MockHttpServletResponse export(MockHttpServletRequestBuilder builder) throws Exception {
    MvcResult started = mockMvc.perform(builder)
        .andExpect(request().asyncStarted())
        .andReturn();
    // The stream already ran inline on this thread (SingleThreadedAsync),
    // so the async result is set; this wait only guards the dispatch
    // ordering and returns immediately.
    started.getAsyncResult(10_000);
    return mockMvc.perform(asyncDispatch(started)).andReturn().getResponse();
  }

  @Test
  void asyncExecutorIsSingleThreaded() {
    // Guards the #362 fix's precondition: Boot wires the MVC async
    // executor from the applicationTaskExecutor bean only when it is
    // an AsyncTaskExecutor, so the bean must be a TaskExecutorAdapter
    // around a SyncTaskExecutor — and the probe proves it really runs
    // inline on the calling thread. If Boot ever stops wiring it, this
    // fails loudly instead of the export tests going flaky again.
    assertThat(taskExecutor).isInstanceOf(TaskExecutorAdapter.class);
    AtomicReference<Thread> runningThread = new AtomicReference<>();
    taskExecutor.execute(() -> runningThread.set(Thread.currentThread()));
    assertThat(runningThread.get()).isSameAs(Thread.currentThread());
  }

  private static String[] lines(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString().split("\r\n");
  }

  @Test
  void adminExportReturnsCsvHeaderAndFilteredRowsNewestFirst() throws Exception {
    MockHttpServletResponse response = export(get("/api/admin/audit-log/export")
        .param("format", "csv").param("action", "ORDER_REFUNDED")
        .header("Authorization", "Bearer " + adminToken));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith("text/csv");
    assertThat(response.getHeader("Content-Disposition")).isEqualTo(
        "attachment; filename=\"audit-log-" + LocalDate.now(ZoneOffset.UTC) + ".csv\"");
    assertThat(response.getHeader("X-Audit-Export-Truncated")).isNull();

    String[] rows = lines(response);
    assertThat(rows).hasSize(2);
    assertThat(rows[0]).isEqualTo("id,actor_user_id,action,target_type,target_id,created_at");
    assertThat(rows[1]).isEqualTo(refundedRow.getId() + "," + carol.getId()
        + ",ORDER_REFUNDED,ORDER,55,2026-10-09T12:00:00Z");
  }

  @Test
  void adminExportWithoutFormatParamDefaultsToCsvAndReturnsAllRows() throws Exception {
    MockHttpServletResponse response = export(get("/api/admin/audit-log/export")
        .header("Authorization", "Bearer " + adminToken));

    assertThat(response.getStatus()).isEqualTo(200);
    String[] rows = lines(response);
    assertThat(rows).hasSize(5);
    assertThat(rows[0]).isEqualTo("id,actor_user_id,action,target_type,target_id,created_at");
    assertThat(rows[1]).contains("USER_DISABLED");
    assertThat(rows[4]).contains("PASSWORD_CHANGED");
  }

  @Test
  void combinedFiltersIntersectOnExport() throws Exception {
    MockHttpServletResponse response = export(get("/api/admin/audit-log/export")
        .param("format", "csv")
        .param("actorUserId", String.valueOf(carol.getId()))
        .param("targetType", "ORDER").param("targetId", "55")
        .header("Authorization", "Bearer " + adminToken));

    assertThat(response.getStatus()).isEqualTo(200);
    String[] rows = lines(response);
    assertThat(rows).hasSize(2);
    assertThat(rows[1]).contains("ORDER_REFUNDED");
  }

  @Test
  void nonCsvFormatIsBadRequestEnvelope() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log/export").param("format", "json")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void invalidActionValueIsBadRequestEnvelope() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log/export").param("format", "csv")
            .param("action", "NOT_AN_ACTION")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void nonAdminExportIsForbidden() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log/export").param("format", "csv")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void anonymousExportIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/admin/audit-log/export").param("format", "csv"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void exportCapsAt100RowsAndReportsTruncation() throws Exception {
    Instant base = Instant.parse("2026-10-10T00:00:00Z");
    for (int i = 0; i < 105; i++) {
      auditLog.save(new AuditLog(alice.getId(), AuditAction.PASSWORD_CHANGED,
          AuditTargetType.USER, alice.getId(), base.plusSeconds(i)));
    }

    MockHttpServletResponse response = export(get("/api/admin/audit-log/export")
        .param("format", "csv")
        .header("Authorization", "Bearer " + adminToken));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-Audit-Export-Truncated")).isEqualTo("true");
    String[] rows = lines(response);
    assertThat(rows).hasSize(1 + AuditService.EXPORT_ROW_CAP);
    assertThat(rows[0]).isEqualTo("id,actor_user_id,action,target_type,target_id,created_at");
    // Newest first: the last seeded row (latest instant) heads the file.
    assertThat(rows[1]).endsWith(",2026-10-10T00:01:44Z");
  }
}
