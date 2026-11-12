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

  @Test
  void exportNoopInstanceReturnsEmptyUntruncatedExport() {
    AuditService noop = AuditService.noop();

    AuditService.ExportResult result = noop.exportAuditLog(null, null, null, null);

    assertThat(result.rows()).isEmpty();
    assertThat(result.truncated()).isFalse();
    verifyNoInteractions(auditLog);
  }

  @Test
  void exportReturnsRowsAndReportsNotTruncatedWhenTrailFitsCap() {
    AuditLog row = new AuditLog(7L, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER, 42L, NOW);
    org.springframework.test.util.ReflectionTestUtils.setField(row, "id", 99L);
    org.springframework.data.domain.PageRequest expectedPage =
        org.springframework.data.domain.PageRequest.of(0, AuditService.EXPORT_ROW_CAP,
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));
    org.mockito.Mockito.when(auditLog.searchAuditLog(7L, AuditAction.ORDER_REFUNDED,
            AuditTargetType.ORDER, 42L, expectedPage))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(
            java.util.List.of(row, row), expectedPage, 2));
    AuditService service = new AuditService(auditLog, Clock.fixed(NOW, ZoneOffset.UTC));

    AuditService.ExportResult result = service.exportAuditLog(7L, AuditAction.ORDER_REFUNDED,
        AuditTargetType.ORDER, 42L);

    assertThat(result.rows()).hasSize(2);
    assertThat(result.rows().get(0).action()).isEqualTo(AuditAction.ORDER_REFUNDED);
    assertThat(result.rows().get(0).targetId()).isEqualTo(42L);
    assertThat(result.truncated()).isFalse();
    assertThat(result.exportedAt()).isEqualTo(NOW);
    org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
        ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    org.mockito.Mockito.verify(auditLog).searchAuditLog(
        org.mockito.ArgumentMatchers.eq(7L),
        org.mockito.ArgumentMatchers.eq(AuditAction.ORDER_REFUNDED),
        org.mockito.ArgumentMatchers.eq(AuditTargetType.ORDER),
        org.mockito.ArgumentMatchers.eq(42L),
        pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(AuditService.EXPORT_ROW_CAP);
    assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
        .isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
    assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").getDirection())
        .isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
  }

  @Test
  void exportReportsTruncatedWhenTrailExceedsCap() {
    java.util.List<AuditLog> rows = new java.util.ArrayList<>();
    for (int i = 0; i < AuditService.EXPORT_ROW_CAP; i++) {
      AuditLog row = new AuditLog(7L, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER,
          (long) i, NOW);
      org.springframework.test.util.ReflectionTestUtils.setField(row, "id", (long) i + 1);
      rows.add(row);
    }
    org.springframework.data.domain.PageRequest expectedPage =
        org.springframework.data.domain.PageRequest.of(0, AuditService.EXPORT_ROW_CAP,
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));
    org.mockito.Mockito.when(auditLog.searchAuditLog(7L, AuditAction.ORDER_REFUNDED,
            AuditTargetType.ORDER, 42L, expectedPage))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(
            rows, expectedPage, AuditService.EXPORT_ROW_CAP + 1));
    AuditService service = new AuditService(auditLog, Clock.fixed(NOW, ZoneOffset.UTC));

    AuditService.ExportResult result = service.exportAuditLog(7L, AuditAction.ORDER_REFUNDED,
        AuditTargetType.ORDER, 42L);

    assertThat(result.rows()).hasSize(AuditService.EXPORT_ROW_CAP);
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void exportAtExactlyTheCapIsNotTruncated() {
    java.util.List<AuditLog> rows = new java.util.ArrayList<>();
    for (int i = 0; i < AuditService.EXPORT_ROW_CAP; i++) {
      AuditLog row = new AuditLog(7L, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER,
          (long) i, NOW);
      org.springframework.test.util.ReflectionTestUtils.setField(row, "id", (long) i + 1);
      rows.add(row);
    }
    org.springframework.data.domain.PageRequest expectedPage =
        org.springframework.data.domain.PageRequest.of(0, AuditService.EXPORT_ROW_CAP,
            org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));
    org.mockito.Mockito.when(auditLog.searchAuditLog(7L, AuditAction.ORDER_REFUNDED,
            AuditTargetType.ORDER, 42L, expectedPage))
        .thenReturn(new org.springframework.data.domain.PageImpl<>(
            rows, expectedPage, AuditService.EXPORT_ROW_CAP));
    AuditService service = new AuditService(auditLog, Clock.fixed(NOW, ZoneOffset.UTC));

    AuditService.ExportResult result = service.exportAuditLog(7L, AuditAction.ORDER_REFUNDED,
        AuditTargetType.ORDER, 42L);

    assertThat(result.rows()).hasSize(AuditService.EXPORT_ROW_CAP);
    assertThat(result.truncated()).isFalse();
  }
}
