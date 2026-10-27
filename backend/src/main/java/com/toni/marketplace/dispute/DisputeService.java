package com.toni.marketplace.dispute;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationService;
import com.toni.marketplace.notification.NotificationType;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderMetrics;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import com.toni.marketplace.order.PaymentService;
import com.toni.marketplace.order.SellerPayoutService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Order disputes (backlog #169): a buyer whose PAID or SHIPPED order never arrives
 * — or whose COMPLETED order turns out wrong — opens a dispute instead
 * of depending on the seller's voluntary refund (#50, seller/ADMIN
 * only; a COMPLETED order had no recourse at all).
 *
 * <p>Open ({@link #openDispute}):
 * <ul>
 *   <li>Buyer only — anyone else gets 403, checked before any state
 *       guard, mirroring {@code OrderService.pay}, so an order id never
 *       leaks what the caller may do with it. Unknown order → 404;
 *       anonymous → the chain's 401 envelope.</li>
 *   <li>Only PAID, SHIPPED (backlog #222 — a shipped order that never
 *       arrives is exactly the dispute case) or COMPLETED orders, and
 *       only inside {@code app.disputes.window} (default 7 days) of the transition —
 *       measured from the order's {@code updatedAt}, the last status
 *       change (orders carry no paidAt/completedAt column; see
 *       {@link DisputeProperties}). Outside the window → 422; any other
 *       status → 422.</li>
 *   <li>Opening moves the order to DISPUTED and moves NO money. While
 *       DISPUTED, pay / cancel / complete / ship / confirm-receipt /
 *       refund all answer 422 (the
 *       dispute must be resolved first, so no transition races a
 *       resolution): complete/refund already reject every non-PAID
 *       state, cancel rejects every non-PENDING state, and pay's guard
 *       names DISPUTED explicitly.</li>
 *   <li>One dispute per order, ever: opening while one is OPEN is
 *       idempotent and returns the open dispute (no second row, no
 *       second notification, no second audit row); re-disputing an
 *       order whose dispute was already resolved is 422 — the decision
 *       was made. The V36 {@code open_order_id} guard column is the
 *       database-level arbiter for racing opens.</li>
 * </ul>
 *
 * <p>Resolution (ADMIN only, on the controllers):
 * <ul>
 *   <li>Both resolutions require a resolution note (backlog #240,
 *       validated at the request boundary: blank/absent/over-1000
 *       chars → 400), stored on the dispute row and shown to both
 *       parties. The first resolution's note stands — an idempotent
 *       re-resolve never rewrites it, and a 422 opposite resolution
 *       throws before any write.</li>
 *   <li>{@link #resolveRefund}: runs the existing mock refund seam
 *       exactly once — the order's stored refund idempotency key
 *       (generated here if the seller never refunded, the #65 pattern),
 *       so the PSP replay guarantee is the same one
 *       {@code OrderService.refund} relies on — and the order goes to
 *       REFUNDED. The listing flips RESERVED → AVAILABLE only when it
 *       is actually RESERVED (a PAID or SHIPPED dispute); a COMPLETED dispute's
 *       listing is already SOLD and stays SOLD. Which case applied is
 *       recorded on the dispute row ({@code listingRestored}).</li>
 *   <li>{@link #resolveRelease}: the order returns to the exact status
 *       recorded on the dispute row at open time
 *       ({@code previousStatus}) — never a silent rewrite to a guessed
 *       state — and the listing is untouched (opening never moved it).</li>
 *   <li>Repeating the same resolution is idempotent and returns the
 *       dispute unchanged (a re-refund never calls the PSP seam a
 *       second time); attempting the opposite resolution on an
 *       already-resolved dispute is 422.</li>
 * </ul>
 *
 * <p>Audit + notifications join each transition's own transaction,
 * mirroring {@code OrderService}: open writes ORDER_DISPUTED (actor =
 * the buyer) and notifies the seller; each resolution writes its own
 * audit action (actor = the resolving ADMIN) and notifies BOTH sides.
 * A rolled-back transition writes neither.
 *
 * <p>Honest scope: mock PSP only — a "refund" here is the mock seam, no
 * real chargeback exists. The reason is text; photo evidence on an
 * open dispute lives in {@link DisputeEvidenceService} (backlog #200).
 */
@Service
public class DisputeService {

  private final OrderDisputeRepository disputes;
  private final OrderRepository orders;
  private final ItemRepository items;
  private final PaymentService payments;
  private final OrderMetrics metrics;
  private final AuditService audit;
  private final NotificationService notifications;
  private final DisputeProperties props;
  private final Clock clock;
  private final SellerPayoutService payouts;

  @Autowired
  public DisputeService(OrderDisputeRepository disputes, OrderRepository orders,
                        ItemRepository items, PaymentService payments,
                        OrderMetrics metrics, AuditService audit,
                        NotificationService notifications, DisputeProperties props,
                        Clock clock,
                        SellerPayoutService payouts) {
    this.disputes = disputes;
    this.orders = orders;
    this.items = items;
    this.payments = payments;
    this.metrics = metrics;
    this.audit = audit;
    this.notifications = notifications;
    this.props = props;
    this.clock = clock;
    this.payouts = payouts;
  }

  /** Constructor for direct unit tests that do not assert on metrics/audit/notifications. */
  public DisputeService(OrderDisputeRepository disputes, OrderRepository orders,
                        ItemRepository items, PaymentService payments,
                        DisputeProperties props, Clock clock) {
    this(disputes, orders, items, payments, OrderMetrics.noop(), AuditService.noop(),
        NotificationService.noop(), props, clock, null);
  }

  private Clock clock() {
    return clock != null ? clock : Clock.systemUTC();
  }

  private AuditService audit() {
    return audit != null ? audit : AuditService.noop();
  }

  private NotificationService notifications() {
    return notifications != null ? notifications : NotificationService.noop();
  }

  private OrderMetrics metrics() {
    return metrics != null ? metrics : OrderMetrics.noop();
  }

  /**
   * Opens a dispute on a PAID/SHIPPED/COMPLETED order (see the class javadoc
   * for the full rule set), or returns the already-open dispute for
   * the order unchanged.
   */
  @Transactional
  public DisputeDto openDispute(Long buyerId, Long orderId, String reason) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (!order.getBuyerId().equals(buyerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the buyer can dispute this order");
    }
    if (order.getStatus() == OrderStatus.DISPUTED) {
      // Idempotent re-open: the live dispute is the answer. (A DISPUTED
      // order without an OPEN dispute row cannot occur — the two flip
      // in one transaction — so a miss here is a data bug, not a 404.)
      return disputes.findByOrderIdAndStatus(orderId, DisputeStatus.OPEN)
          .map(DisputeDto::from)
          .orElseThrow(() -> new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "disputed order has no open dispute"));
    }
    if (order.getStatus() != OrderStatus.PAID
        && order.getStatus() != OrderStatus.SHIPPED
        && order.getStatus() != OrderStatus.COMPLETED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "order is " + order.getStatus() + ", only PAID, SHIPPED or COMPLETED orders "
              + "can be disputed");
    }
    if (disputes.findByOrderId(orderId).stream()
        .anyMatch(d -> d.getStatus() != DisputeStatus.OPEN)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "this order was already disputed and the dispute was resolved");
    }
    Instant deadline = order.getUpdatedAt().plus(props.getWindow());
    if (clock().instant().isAfter(deadline)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "the dispute window for this order has closed");
    }
    try {
      OrderStatus previous = order.getStatus();
      order.setStatus(OrderStatus.DISPUTED);
      orders.save(order);
      // Flush inside the transaction, mirroring OrderService: a
      // concurrent transition of the order row surfaces here as a
      // version conflict → 409 instead of a 500 at commit.
      orders.flush();
      OrderDispute dispute = new OrderDispute(orderId, buyerId,
          order.getItem().getSellerId(), reason, previous);
      DisputeDto dto = DisputeDto.from(disputes.save(dispute));
      disputes.flush();
      audit().record(buyerId, AuditAction.ORDER_DISPUTED, AuditTargetType.ORDER, orderId);
      notifications().record(order.getItem().getSellerId(),
          NotificationType.ORDER_DISPUTED, orderId);
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    } catch (DataIntegrityViolationException e) {
      // Lost the uq_dispute_open_order race: a concurrent open inserted
      // the live dispute first. Same client outcome as the version
      // race — 409, retry reads the now-DISPUTED order and returns the
      // open dispute idempotently.
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    }
  }

  /**
   * The dispute of one order, for the parties who may see it
   * (backlog #200): the buyer, the listing's seller, or an ADMIN.
   * Anyone else — and any caller asking about an order with no
   * dispute, or an unknown order — gets the identical 404, so the
   * read never confirms that an order or a dispute exists. This is
   * the discovery read the SPA's evidence panel is keyed by.
   */
  @Transactional(readOnly = true)
  public DisputeDto getDisputeForOrder(Long callerId, boolean admin, Long orderId) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "dispute not found"));
    boolean party = admin
        || order.getBuyerId().equals(callerId)
        || order.getItem().getSellerId().equals(callerId);
    if (!party) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dispute not found");
    }
    return disputes.findByOrderId(orderId).stream()
        .findFirst()
        .map(DisputeDto::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "dispute not found"));
  }

  /** The ADMIN queue, paginated (the controller supplies newest-first); status filter optional. */
  @Transactional(readOnly = true)
  public Page<DisputeDto> listDisputes(DisputeStatus status, Pageable pageable) {
    Page<OrderDispute> page = status == null
        ? disputes.findAll(pageable)
        : disputes.findByStatus(status, pageable);
    return page.map(DisputeDto::from);
  }

  /**
   * Resolves a dispute by refunding the order through the mock PSP
   * seam (see the class javadoc). ADMIN authorization lives on the
   * controller; {@code adminId} is the audit actor.
   * {@code resolutionNote} is the ADMIN's reason for the verdict
   * (backlog #240, validated at the request boundary) and is stored
   * on the dispute row alongside the verdict.
   */
  @Transactional
  public DisputeDto resolveRefund(Long adminId, Long disputeId, String resolutionNote) {
    OrderDispute dispute = requireDispute(disputeId);
    if (dispute.getStatus() == DisputeStatus.RESOLVED_REFUND) {
      return DisputeDto.from(dispute);
    }
    if (dispute.getStatus() == DisputeStatus.RESOLVED_RELEASE) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "dispute already resolved by release");
    }
    Order order = orders.findById(dispute.getOrderId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "dispute points at a missing order"));
    try {
      // The mock refund seam, exactly once: the order-scoped refund key
      // is generated once and stored on the order row (#65), so this
      // call replays — never duplicates — if a crash retried it, and
      // the idempotent re-resolve above returns before reaching here.
      String refundKey = refundKeyFor(order);
      // Backlog #210: a dispute refund settles whatever is still
      // refundable — if the seller already made a partial refund while
      // the order was PAID, the dispute refunds only the remainder, and
      // the cumulative total lands exactly on the order amount.
      long remaining = order.getAmountCents() - order.getRefundedAmountCents();
      PaymentService.RefundResult refund = payments.refund(order, refundKey, remaining);
      order.setRefundId(refund.refundId());
      order.setRefundedAmountCents(order.getAmountCents());
      order.setRefundCount(order.getRefundCount() + 1);
      order.setStatus(OrderStatus.REFUNDED);
      Item item = items.findById(order.getItem().getId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
              "order points at a missing item"));
      boolean restored = false;
      if (item.getStatus() == ItemStatus.RESERVED) {
        item.setStatus(ItemStatus.AVAILABLE);
        items.save(item);
        restored = true;
      }
      orders.save(order);
      orders.flush();
      // Mirror OrderService.refund's forget: the refund id is durable
      // on the flushed row, so the PSP replay entry has done its job.
      payments.forgetRefund(refundKey);
      metrics().refunded();
      // Backlog #221: a refund that lands after completion never mutates
      // the settlement row — it appends a compensating negative entry,
      // so the seller's net reconciles (settlement + adjustments). When
      // the dispute froze a PAID order there is no settlement row and
      // this is a no-op; direct unit tests without a payout bean skip
      // it the same way.
      if (payouts != null) {
        payouts.recordRefundAdjustment(order.getId(), remaining, "DISPUTE_REFUND");
      }
      dispute.setStatus(DisputeStatus.RESOLVED_REFUND);
      dispute.setListingRestored(restored);
      dispute.setResolutionNote(resolutionNote);
      dispute.setResolvedAt(clock().instant());
      DisputeDto dto = DisputeDto.from(disputes.save(dispute));
      disputes.flush();
      audit().record(adminId, AuditAction.ORDER_DISPUTE_RESOLVED_REFUND,
          AuditTargetType.ORDER, dispute.getOrderId());
      notifications().record(dispute.getBuyerId(),
          NotificationType.DISPUTE_RESOLVED_REFUND, dispute.getOrderId());
      notifications().record(dispute.getSellerId(),
          NotificationType.DISPUTE_RESOLVED_REFUND, dispute.getOrderId());
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "dispute was updated concurrently, please retry");
    }
  }

  /**
   * Resolves a dispute by releasing the order back to its recorded
   * pre-dispute status (see the class javadoc). ADMIN authorization
   * lives on the controller; {@code adminId} is the audit actor.
   * {@code resolutionNote} is the ADMIN's reason for the verdict
   * (backlog #240, validated at the request boundary) and is stored
   * on the dispute row alongside the verdict.
   */
  @Transactional
  public DisputeDto resolveRelease(Long adminId, Long disputeId, String resolutionNote) {
    OrderDispute dispute = requireDispute(disputeId);
    if (dispute.getStatus() == DisputeStatus.RESOLVED_RELEASE) {
      return DisputeDto.from(dispute);
    }
    if (dispute.getStatus() == DisputeStatus.RESOLVED_REFUND) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "dispute already resolved by refund");
    }
    Order order = orders.findById(dispute.getOrderId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "dispute points at a missing order"));
    try {
      order.setStatus(dispute.getPreviousStatus());
      orders.save(order);
      orders.flush();
      dispute.setStatus(DisputeStatus.RESOLVED_RELEASE);
      dispute.setResolutionNote(resolutionNote);
      dispute.setResolvedAt(clock().instant());
      DisputeDto dto = DisputeDto.from(disputes.save(dispute));
      disputes.flush();
      audit().record(adminId, AuditAction.ORDER_DISPUTE_RESOLVED_RELEASE,
          AuditTargetType.ORDER, dispute.getOrderId());
      notifications().record(dispute.getBuyerId(),
          NotificationType.DISPUTE_RESOLVED_RELEASE, dispute.getOrderId());
      notifications().record(dispute.getSellerId(),
          NotificationType.DISPUTE_RESOLVED_RELEASE, dispute.getOrderId());
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "dispute was updated concurrently, please retry");
    }
  }

  /**
   * Order-scoped PSP refund key (backlog #65), generated once per order
   * and stored on the row — the same discipline as
   * {@code OrderService.refund}, so a dispute refund and a seller refund
   * for the same order can never use different keys.
   */
  private String refundKeyFor(Order order) {
    // Backlog #210: the same sequence-derived key as OrderService — the
    // next refund sequence for this order, so a dispute refund can never
    // replay (or collide with) a seller refund's key, and a crash-retry
    // before the commit re-derives the same key.
    String key = "rfd_" + order.getId() + "_" + (order.getRefundCount() + 1);
    order.setRefundIdempotencyKey(key);
    return key;
  }

  private OrderDispute requireDispute(Long disputeId) {
    return disputes.findById(disputeId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "dispute not found"));
  }
}
