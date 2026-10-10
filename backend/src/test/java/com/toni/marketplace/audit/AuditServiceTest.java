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
  void listAuditLogDelegatesFiltersAndMapsRowsToDto() {
    AuditLog row = new AuditLog(7L, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER, 42L, NOW);
    org.springframework.test.util.ReflectionTestUtils.setField(row, "id", 99L);
    org.springframework.data.domain.Pageable pageable =
        org.springframework.data.domain.PageRequest.of(0, 20);
    org.mockito.Mockito.when(auditLog.searchAuditLog(7L, AuditAction.ORDER_REFUNDED,
            AuditTargetType.ORDER, 42L, pageable))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(row)));
    AuditService service = new AuditService(auditLog, Clock.fixed(NOW, ZoneOffset.UTC));

    org.springframework.data.domain.Page<AuditLogDto> page = service.listAuditLog(
        7L, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER, 42L, pageable);

    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).id()).isEqualTo(99L);
    assertThat(page.getContent().get(0).actorUserId()).isEqualTo(7L);
    assertThat(page.getContent().get(0).action()).isEqualTo(AuditAction.ORDER_REFUNDED);
    assertThat(page.getContent().get(0).targetType()).isEqualTo(AuditTargetType.ORDER);
    assertThat(page.getContent().get(0).targetId()).isEqualTo(42L);
    assertThat(page.getContent().get(0).createdAt()).isEqualTo(NOW);
  }

  @Test
  void noopListAuditLogReturnsEmptyPage() {
    AuditService noop = AuditService.noop();

    org.springframework.data.domain.Page<AuditLogDto> page = noop.listAuditLog(
        null, null, null, null, org.springframework.data.domain.PageRequest.of(0, 20));

    assertThat(page.getTotalElements()).isZero();
    assertThat(page.getContent()).isEmpty();
  }

  @Test
  void noopRecordsNothingAndNeverThrows() {
    AuditService noop = AuditService.noop();

    assertThatCode(() -> noop.record(1L, AuditAction.PASSWORD_CHANGED,
        AuditTargetType.USER, 1L)).doesNotThrowAnyException();
    verifyNoInteractions(auditLog);
  }
}
