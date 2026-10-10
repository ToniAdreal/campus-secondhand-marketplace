package com.toni.marketplace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

/**
 * Slice tests for the retention query behind the nightly audit-log purge
 * (backlog #118): selection is by {@code created_at} only — rows of every
 * action and actor age out together once they predate the cut-off, and a
 * row inside the window is never selected, so the purge cannot erase one
 * specific event while keeping its neighbours.
 */
@DataJpaTest
class AuditLogRetentionRepositoryTest {

  private static final Instant CUTOFF = Instant.parse("2026-10-01T00:00:00Z");

  @Autowired
  private AuditLogRepository auditLog;

  @Test
  void selectsOnlyRowsCreatedBeforeTheCutoff() {
    AuditLog old = auditLog.saveAndFlush(new AuditLog(1L, AuditAction.PASSWORD_CHANGED,
        AuditTargetType.USER, 1L, CUTOFF.minusSeconds(1)));
    auditLog.saveAndFlush(new AuditLog(1L, AuditAction.TOTP_ENABLED,
        AuditTargetType.USER, 1L, CUTOFF));
    auditLog.saveAndFlush(new AuditLog(1L, AuditAction.ORDER_REFUNDED,
        AuditTargetType.ORDER, 9L, CUTOFF.plusSeconds(3600)));

    List<Long> ids = auditLog.findPurgeableIds(CUTOFF, PageRequest.of(0, 500));

    // Strictly before: the row exactly at the cut-off survives this run
    // and ages out on a later one; only the older row is selected.
    assertThat(ids).containsExactly(old.getId());
  }

  @Test
  void selectionIgnoresActionAndActor() {
    // Different actors, different actions, both old: both are selected —
    // there is no predicate by which one event could be spared (or
    // targeted) over another.
    AuditLog a = auditLog.saveAndFlush(new AuditLog(1L, AuditAction.USER_DISABLED,
        AuditTargetType.USER, 2L, CUTOFF.minusSeconds(60)));
    AuditLog b = auditLog.saveAndFlush(new AuditLog(2L, AuditAction.ORDER_COMPLETED,
        AuditTargetType.ORDER, 3L, CUTOFF.minusSeconds(30)));

    List<Long> ids = auditLog.findPurgeableIds(CUTOFF, PageRequest.of(0, 500));

    assertThat(ids).containsExactly(a.getId(), b.getId());
  }

  @Test
  void emptyTrailSelectsNothing() {
    assertThat(auditLog.findPurgeableIds(CUTOFF, PageRequest.of(0, 500))).isEmpty();
  }
}
