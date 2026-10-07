package com.toni.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.IdempotencyStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * Pure unit tests for the {@link DataRetentionService} purge logic, with a
 * {@link MutableClock} standing in for wall time. The repositories are
 * mocked — these tests pin the retention arithmetic, the terminal-status
 * filter and the batch loop, not the JPQL (slice tests would need a Spring
 * context; the {@code findStaleIds}/{@code findTerminalIdsBefore} queries
 * are exercised wherever the app boots).
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionServiceTest {

  @Mock
  private RefreshTokenRepository refreshTokens;

  @Mock
  private IdempotencyKeyRepository idempotencyKeys;

  private MutableClock clock;
  private CleanupProperties props;
  private DataRetentionService purge;

  @BeforeEach
  void setUp() {
    // Fixed epoch start keeps the retention arithmetic legible; production
    // starts at wall-clock time, which the math treats identically.
    clock = new MutableClock(Instant.EPOCH);
    props = new CleanupProperties();
    purge = new DataRetentionService(refreshTokens, idempotencyKeys, props, clock);
  }

  @Test
  void defaultsAreDocumented() {
    assertThat(props.getRefreshTokenRetention()).isEqualTo(30);
    assertThat(props.getIdempotencyRetention()).isEqualTo(90);
  }

  @Test
  void purgeDeletesStaleRefreshTokensAfterRetentionWindow() {
    // One partial page: the batch loop stops after it, so findStaleIds is
    // called exactly once.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of(1L, 2L));
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());

    clock.advance(Duration.ofDays(31));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(2, 0));
    verify(refreshTokens, times(1)).deleteAllByIdInBatch(List.of(1L, 2L));

    ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(refreshTokens, times(1))
        .findStaleIds(nowCaptor.capture(), cutoffCaptor.capture(), any(Pageable.class));
    assertThat(nowCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(31)));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));
  }

  @Test
  void purgeNeverTargetsInProgressKeys() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    clock.advance(Duration.ofDays(365));

    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts.idempotencyKeys()).isEqualTo(0);
    ArgumentCaptor<Set<IdempotencyStatus>> statusCaptor =
        ArgumentCaptor.forClass(Set.class);
    verify(idempotencyKeys).findTerminalIdsBefore(statusCaptor.capture(), any(), any());
    assertThat(statusCaptor.getValue())
        .containsExactlyInAnyOrder(IdempotencyStatus.COMPLETED, IdempotencyStatus.FAILED);
  }

  @Test
  void purgeDeletesTerminalKeysInBatches() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    List<Long> fullBatch = LongStream.range(0, DataRetentionService.BATCH_SIZE)
        .boxed().toList();
    List<Long> partialBatch = LongStream.range(1000, 1200).boxed().toList();
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any()))
        .thenReturn(fullBatch, partialBatch);

    clock.advance(Duration.ofDays(91));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 700));
    verify(idempotencyKeys, times(2)).deleteAllByIdInBatch(any());
    verify(idempotencyKeys).deleteAllByIdInBatch(fullBatch);
    verify(idempotencyKeys).deleteAllByIdInBatch(partialBatch);

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(idempotencyKeys, times(2))
        .findTerminalIdsBefore(any(), cutoffCaptor.capture(), any(Pageable.class));
    assertThat(cutoffCaptor.getAllValues())
        .allSatisfy(cutoff -> assertThat(cutoff)
            .isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1))));
  }

  @Test
  void emptyReposDeleteNothing() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());

    clock.advance(Duration.ofDays(365));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0));
    verify(refreshTokens, never()).deleteAllByIdInBatch(any());
    verify(idempotencyKeys, never()).deleteAllByIdInBatch(any());
  }

  @Test
  void customRetentionShrinksTheCutoffWindow() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    props.setRefreshTokenRetention(7);
    props.setIdempotencyRetention(14);

    clock.advance(Duration.ofDays(10));
    purge.purgeStaleData();

    ArgumentCaptor<Instant> tokenCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(refreshTokens).findStaleIds(any(), tokenCutoff.capture(), any(Pageable.class));
    assertThat(tokenCutoff.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(3)));

    ArgumentCaptor<Instant> keyCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(idempotencyKeys).findTerminalIdsBefore(any(), keyCutoff.capture(), any(Pageable.class));
    // 10 days elapsed < 14-day key retention: the query still runs (the
    // repository decides staleness), but the cutoff predates creation, so
    // nothing can match yet.
    assertThat(keyCutoff.getValue()).isEqualTo(Instant.EPOCH.minus(Duration.ofDays(4)));
  }
}
