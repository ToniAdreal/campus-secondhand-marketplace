package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.item.ItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests for the seller sales summary's month window and
 * aggregate mapping (backlog #108), driven by a fixed {@link Clock} —
 * the controllable-clock pattern the other time-dependent services use.
 * The MockMvc contract lives in {@link SellerSalesSummaryTest}.
 */
@ExtendWith(MockitoExtension.class)
class SellerSalesSummaryWindowTest {

  @Mock
  private OrderRepository orders;

  @Mock
  private ItemRepository items;

  @Mock
  private IdempotencyKeyService idempotency;

  @Mock
  private PaymentService payments;

  @Mock
  private OrderCreationService creation;

  private record Aggregate(OrderStatus status, long orderCount, long amountCents)
      implements OrderRepository.StatusAggregate {
    @Override
    public OrderStatus getStatus() {
      return status;
    }

    @Override
    public long getOrderCount() {
      return orderCount;
    }

    @Override
    public long getAmountCents() {
      return amountCents;
    }
  }

  @Test
  void monthWindow_lastDayOfMonth_spansExactlyThatMonth() {
    ZoneId shanghai = ZoneId.of("Asia/Shanghai");
    // 2026-03-31 23:30 in Shanghai — still March there.
    Instant now = Instant.parse("2026-03-31T15:30:00Z");

    OrderService.MonthWindow window = OrderService.monthWindow(now, shanghai);

    assertThat(window.month()).isEqualTo(YearMonth.of(2026, 3));
    assertThat(window.from()).isEqualTo(Instant.parse("2026-02-28T16:00:00Z"));
    assertThat(window.to()).isEqualTo(Instant.parse("2026-03-31T16:00:00Z"));
  }

  @Test
  void monthWindow_firstInstantBelongsToNewMonth_oneSecondBeforeToPrevious() {
    ZoneId utc = ZoneId.of("UTC");
    Instant aprilStart = Instant.parse("2026-04-01T00:00:00Z");

    assertThat(OrderService.monthWindow(aprilStart, utc).month())
        .isEqualTo(YearMonth.of(2026, 4));
    assertThat(OrderService.monthWindow(aprilStart.minusSeconds(1), utc).month())
        .isEqualTo(YearMonth.of(2026, 3));
  }

  @Test
  void summary_queriesRepositoryWithCurrentMonthWindow_andMapsAggregates() {
    // Fixed clock on the last day of a month, server-local zone — the
    // window the service derives must contain the fixed instant and
    // start at that month's first instant in the same zone.
    ZoneId zone = ZoneId.systemDefault();
    Instant now = YearMonth.now(zone).atEndOfMonth()
        .atTime(12, 0).atZone(zone).toInstant();
    OrderService service = new OrderService(orders, items, idempotency, payments,
        creation, OrderMetrics.noop(), AuditService.noop(),
        Clock.fixed(now, zone));

    when(orders.summarizeBySellerId(7L)).thenReturn(List.of(
        new Aggregate(OrderStatus.PAID, 2, 30_000),
        new Aggregate(OrderStatus.COMPLETED, 1, 10_000),
        new Aggregate(OrderStatus.REFUNDED, 3, 90_000),
        new Aggregate(OrderStatus.PENDING, 4, 40_000)));
    when(orders.summarizeBySellerIdBetween(eq(7L), any(), any())).thenReturn(List.of(
        new Aggregate(OrderStatus.COMPLETED, 1, 10_000)));

    SellerSalesSummaryDto dto = service.sellerSalesSummary(7L);

    assertThat(dto.totalOrders()).isEqualTo(10);
    assertThat(dto.paidCount()).isEqualTo(2);
    assertThat(dto.completedCount()).isEqualTo(1);
    assertThat(dto.refundedCount()).isEqualTo(3);
    assertThat(dto.pendingCount()).isEqualTo(4);
    assertThat(dto.cancelledCount()).isZero();
    // Gross = PAID + COMPLETED snapshots only; REFUNDED never counts.
    assertThat(dto.grossCents()).isEqualTo(40_000);
    assertThat(dto.currentMonth().totalOrders()).isEqualTo(1);
    assertThat(dto.currentMonth().grossCents()).isEqualTo(10_000);

    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
    org.mockito.Mockito.verify(orders)
        .summarizeBySellerIdBetween(eq(7L), from.capture(), to.capture());
    YearMonth month = YearMonth.from(now.atZone(zone));
    assertThat(from.getValue())
        .isEqualTo(month.atDay(1).atStartOfDay(zone).toInstant());
    assertThat(to.getValue())
        .isEqualTo(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    assertThat(dto.currentMonth().year()).isEqualTo(month.getYear());
    assertThat(dto.currentMonth().month()).isEqualTo(month.getMonthValue());
  }
}
