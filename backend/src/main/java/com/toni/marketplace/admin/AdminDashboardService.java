package com.toni.marketplace.admin;

import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.dispute.DisputeStatus;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.moderation.ListingReportRepository;
import com.toni.marketplace.moderation.ReportStatus;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.WebhookEvent;
import com.toni.marketplace.order.WebhookEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the ADMIN operations dashboard (backlog #196) from aggregate
 * repository queries only. This is a read-only cross-domain
 * aggregation — the same structural role {@code job/} plays for
 * scheduled sweeps — and it is the reason the {@code admin/} package
 * exists: no single domain service can see users, listings, orders,
 * moderation and the webhook ledger at once, and none of them should
 * grow a dependency on the others just for this read.
 *
 * <p>Every figure is a {@code count} over an existing store (see
 * {@link DashboardDto} for the window/snapshot split and for the
 * declines cut). Nothing is estimated, sampled or read from JVM
 * in-memory counters, so the dashboard survives restarts exactly like
 * the settlement summary (#134).
 */
@Service
public class AdminDashboardService {

  private final UserRepository users;
  private final ItemRepository items;
  private final OrderRepository orders;
  private final ListingReportRepository reports;
  private final OrderDisputeRepository disputes;
  private final WebhookEventRepository webhooks;
  private final Clock clock;

  public AdminDashboardService(
      UserRepository users,
      ItemRepository items,
      OrderRepository orders,
      ListingReportRepository reports,
      OrderDisputeRepository disputes,
      WebhookEventRepository webhooks,
      Clock clock) {
    this.users = users;
    this.items = items;
    this.orders = orders;
    this.reports = reports;
    this.disputes = disputes;
    this.webhooks = webhooks;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public DashboardDto dashboard(DashboardWindow window) {
    Instant now = clock.instant();
    Instant since = now.minus(window.duration());
    return DashboardDto.from(
        window,
        since,
        users.countByCreatedAtGreaterThanEqual(since),
        items.countByCreatedAtGreaterThanEqual(since),
        items.countByStatus(ItemStatus.AVAILABLE),
        items.countExpiredInWindow(since, now,
            List.of(ItemStatus.EXPIRED, ItemStatus.AVAILABLE)),
        orders.summarizeAllBetween(since, now),
        orders.countCapturesBetween(since, now),
        orders.countRefundsBetween(since, now),
        reports.countByStatus(ReportStatus.OPEN),
        disputes.countByStatus(DisputeStatus.OPEN),
        webhooks.countByOutcomeBetween(WebhookEvent.Outcome.MATCHED, since, now),
        webhooks.countByOutcomeBetween(WebhookEvent.Outcome.MISMATCH, since, now));
  }
}
