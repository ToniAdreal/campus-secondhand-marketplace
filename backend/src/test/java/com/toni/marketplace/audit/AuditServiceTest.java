package com.toni.marketplace.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests for {@link AuditService} (backlog #98): the recorded row
 * carries exactly the actor/action/target the caller passed, stamped from
 * the injected clock; the {@code noop()} instance records nothing.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

  @Mock
  private AuditLogRepository auditLog;

  private static final Instant NOW = Instant.parse("2026-10-09T11:00:00Z");

  @Test
  void recordSavesOneRowWithCallerFieldsAndClockInstant() {
    AuditService service = new AuditService(auditLog, Clock.fixed(NOW, ZoneOffset.UTC));

    service.record(7L, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER, 42L);

    ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLog).save(captor.capture());
    AuditLog row = captor.getValue();
    assertThat(row.getActorUserId()).isEqualTo(7L);
    assertThat(row.getAction()).isEqualTo(AuditAction.ORDER_REFUNDED);
    assertThat(row.getTargetType()).isEqualTo(AuditTargetType.ORDER);
    assertThat(row.getTargetId()).isEqualTo(42L);
    assertThat(row.getCreatedAt()).isEqualTo(NOW);
  }

  @Test
  void noopRecordsNothingAndNeverThrows() {
    AuditService noop = AuditService.noop();

    assertThatCode(() -> noop.record(1L, AuditAction.PASSWORD_CHANGED,
        AuditTargetType.USER, 1L)).doesNotThrowAnyException();
    verifyNoInteractions(auditLog);
  }
}
