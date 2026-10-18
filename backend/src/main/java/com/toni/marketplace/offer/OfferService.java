package com.toni.marketplace.offer;

import com.toni.marketplace.auth.UserBlockRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationService;
import com.toni.marketplace.notification.NotificationType;
import com.toni.marketplace.order.OrderCreationService;
import com.toni.marketplace.order.OrderDto;
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
 * Price offers (backlog #168): a buyer proposes a price below (or at)
 * the asking price on an AVAILABLE listing instead of only buying at
 * list price.
 *
 * <p>Rules, in the order mould:
 * <ul>
 *   <li>Creation: the listing must exist (404), be AVAILABLE (409
 *       otherwise), and not be the caller's own (422 — the seller
 *       cannot offer on their own listing). The price is validated at
 *       the request boundary (@Positive → 400).</li>
 *   <li>One live PENDING offer per (buyer, item): a second POST while
 *       one is live is a documented 409 — the buyer withdraws it or
 *       waits for the seller's answer / the expiry, then submits a
 *       fresh offer. It never silently supersedes and never stores a
 *       second live row; the Flyway V34 {@code live_offer_key} guard
 *       is the database-level arbiter under races.</li>
 *   <li>Accept (seller side): creates the order at the AGREED price
 *       through {@link OrderCreationService} — the transactional core
 *       of the existing order path — inside this same transaction, so
 *       the offer flip, the listing's RESERVED flip and the order
 *       insert commit or roll back together. The offer row itself is
 *       this transition's idempotency record (it flips PENDING →
 *       ACCEPTED exactly once under {@code @Version}), which is why
 *       accept deliberately does not mint a client-style
 *       Idempotency-Key: a sequential re-accept is 422, a concurrent
 *       one loses the version / active-order guard race to 409, and a
 *       racing buy-now that already reserved the listing makes the
 *       creation guards answer 409 — never a second order.</li>
 *   <li>Decline (seller side) and withdraw (buyer side) are terminal
 *       and never touch the listing or any order. Side-scoped lookups:
 *       acting on someone else's offer id is 404, never 403 — the
 *       row's existence is not leaked across sides.</li>
 *   <li>Expiry: a PENDING offer carries {@code expiresAt} (creation +
 *       {@code app.offers.pending-ttl}, default 48 h). {@link
 *       OfferExpiryService} flips stale rows to EXPIRED; accept /
 *       decline / withdraw also check the deadline lazily, so a stale
 *       offer can never be accepted in the gap between deadline and
 *       job run.</li>
 *   <li>Notifications (#157 pattern): accept notifies the buyer
 *       (OFFER_ACCEPTED, carrying the created order id), decline
 *       notifies the buyer (OFFER_DECLINED, no order id — none
 *       exists). Both rows are written inside the transition's own
 *       transaction, so a rolled-back transition notifies nobody.
 *       Withdraw and expiry notify nobody in v1 — both are visible in
 *       the acting party's own offer list, and expiry's counterpart
 *       never acted at all.</li>
 * </ul>
 *
 * <p>Honest scope: no counter-offer chain in v1 — decline is final and
 * the buyer may submit a fresh offer afterwards.
 */
@Service
public class OfferService {

  private final PriceOfferRepository offers;
  private final ItemRepository items;
  private final OrderCreationService creation;
  private final NotificationService notifications;
  private final OfferProperties props;
  private final Clock clock;
  private final UserBlockRepository blocks;

  @Autowired
  public OfferService(PriceOfferRepository offers, ItemRepository items,
                      OrderCreationService creation, NotificationService notifications,
                      OfferProperties props, Clock clock, UserBlockRepository blocks) {
    this.offers = offers;
    this.items = items;
    this.creation = creation;
    this.notifications = notifications;
    this.props = props;
    this.clock = clock;
    this.blocks = blocks;
  }

  /** Constructor for direct unit tests that do not assert on notifications. */
  public OfferService(PriceOfferRepository offers, ItemRepository items,
                      OrderCreationService creation, OfferProperties props, Clock clock) {
    this(offers, items, creation, NotificationService.noop(), props, clock, null);
  }

  private Clock clock() {
    return clock != null ? clock : Clock.systemUTC();
  }

  private NotificationService notifications() {
    return notifications != null ? notifications : NotificationService.noop();
  }

  /**
   * Proposes a price on a listing. Guards in javadoc order; the
   * application-level live-offer check gives the friendly 409 and the
   * {@code uq_offer_live_pair} constraint stays the final arbiter — a
   * lost insert race surfaces at the flush and is mapped to the same
   * 409.
   */
  @Transactional
  public OfferDto createOffer(Long buyerId, Long itemId, Long priceCents) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    if (item.getSellerId().equals(buyerId)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "cannot make an offer on your own listing");
    }
    if (item.getStatus() != ItemStatus.AVAILABLE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "item is not available");
    }
    // Block check (backlog #187): a buyer the seller has blocked cannot
    // open a negotiation with that seller. The 403 copy is generic —
    // never "you are blocked" — so the blocked buyer learns nothing a
    // stranger would not. Offers already in flight are untouched.
    if (blocks != null && blocks.existsByBlockerIdAndBlockedId(item.getSellerId(), buyerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "you cannot make an offer on this listing");
    }
    if (offers.existsByItem_IdAndBuyerIdAndStatus(itemId, buyerId, OfferStatus.PENDING)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "you already have a pending offer on this item — withdraw it or wait for the "
              + "seller's answer before offering again");
    }
    PriceOffer offer = new PriceOffer(item, buyerId, item.getSellerId(), priceCents,
        clock().instant().plus(props.getPendingTtl()));
    try {
      OfferDto dto = OfferDto.from(offers.save(offer));
      // Flush inside the transaction, mirroring OrderCreationService: a
      // live-pair constraint violation from a racing create surfaces
      // here and maps to 409, not at commit as a 500.
      offers.flush();
      return dto;
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "you already have a pending offer on this item — withdraw it or wait for the "
              + "seller's answer before offering again");
    }
  }

  /** The caller's own offers as a buyer, newest first (controller sort). */
  @Transactional(readOnly = true)
  public Page<OfferDto> listBuyerOffers(Long buyerId, Pageable pageable) {
    return offers.findByBuyerId(buyerId, pageable).map(OfferDto::from);
  }

  /** Offers on the caller's own listings, newest first (controller sort). */
  @Transactional(readOnly = true)
  public Page<OfferDto> listSellerOffers(Long sellerId, Pageable pageable) {
    return offers.findBySellerId(sellerId, pageable).map(OfferDto::from);
  }

  /**
   * Accepts a PENDING offer: creates the order at the agreed price and
   * flips the listing to RESERVED, atomically with the offer's own
   * PENDING → ACCEPTED flip (see the class javadoc for why the offer
   * row is the idempotency record). Seller-side only via the scoped
   * lookup — anyone else gets 404.
   */
  @Transactional
  public OfferDto accept(Long sellerId, Long offerId) {
    PriceOffer offer = offers.findByIdAndSellerId(offerId, sellerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found"));
    expireIfStale(offer);
    if (offer.getStatus() != OfferStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "offer is " + offer.getStatus() + ", it cannot be accepted");
    }
    // Joins this transaction (REQUIRED): its guards answer 404/409/422
    // in the envelope and any failure rolls the whole accept back, so
    // the offer stays PENDING and no half-created order survives.
    OrderDto order = creation.create(offer.getBuyerId(), offer.getItem().getId(),
        offer.getPriceCents());
    try {
      offer.setStatus(OfferStatus.ACCEPTED);
      offer.setOrderId(order.id());
      OfferDto dto = OfferDto.from(offers.save(offer));
      offers.flush();
      notifications().recordOffer(offer.getBuyerId(), NotificationType.OFFER_ACCEPTED,
          offer.getId(), order.id());
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "offer was updated concurrently, please retry");
    }
  }

  /**
   * Declines a PENDING offer. Terminal; the listing is never touched
   * and no order is created. The buyer is notified in this transaction.
   */
  @Transactional
  public OfferDto decline(Long sellerId, Long offerId) {
    PriceOffer offer = offers.findByIdAndSellerId(offerId, sellerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found"));
    expireIfStale(offer);
    if (offer.getStatus() != OfferStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "offer is " + offer.getStatus() + ", it cannot be declined");
    }
    try {
      offer.setStatus(OfferStatus.DECLINED);
      OfferDto dto = OfferDto.from(offers.save(offer));
      offers.flush();
      notifications().recordOffer(offer.getBuyerId(), NotificationType.OFFER_DECLINED,
          offer.getId(), null);
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "offer was updated concurrently, please retry");
    }
  }

  /**
   * Withdraws a PENDING offer (buyer side). Terminal; the listing is
   * never touched. Withdrawing frees the (buyer, item) live pair, so
   * the buyer may submit a fresh offer immediately afterwards.
   */
  @Transactional
  public OfferDto withdraw(Long buyerId, Long offerId) {
    PriceOffer offer = offers.findByIdAndBuyerId(offerId, buyerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found"));
    expireIfStale(offer);
    if (offer.getStatus() != OfferStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "offer is " + offer.getStatus() + ", it cannot be withdrawn");
    }
    try {
      offer.setStatus(OfferStatus.WITHDRAWN);
      OfferDto dto = OfferDto.from(offers.save(offer));
      offers.flush();
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "offer was updated concurrently, please retry");
    }
  }

  /**
   * Expires one stale PENDING offer — the per-row step of {@link
   * OfferExpiryService}. Status and deadline are re-checked inside this
   * transaction: an offer answered between the job's worklist scan and
   * this call simply returns {@code false}, never wrongly expired. A
   * concurrent transition surfaces as
   * {@code ObjectOptimisticLockingFailureException} at the flush, which
   * the job catches per row and treats as "the answer won".
   *
   * @return {@code true} when this call expired the offer.
   */
  @Transactional
  public boolean expireStaleOffer(Long offerId, Instant now) {
    PriceOffer offer = offers.findById(offerId)
        .orElseThrow(() -> new IllegalStateException(
            "offer " + offerId + " vanished from the expiry worklist"));
    if (offer.getStatus() != OfferStatus.PENDING || offer.getExpiresAt().isAfter(now)) {
      return false;
    }
    offer.setStatus(OfferStatus.EXPIRED);
    offers.save(offer);
    offers.flush();
    return true;
  }

  /**
   * Lazy deadline check for the decision endpoints: a PENDING offer
   * past its deadline is flipped to EXPIRED in the caller's transaction
   * before the state guard runs, so a stale offer answers 422 as
   * EXPIRED instead of being decided in the gap before the expiry job's
   * next run. The flip persists with the caller's transaction even
   * though the decision itself is then rejected — that is the point:
   * the row's state now matches what the job would have written.
   */
  private void expireIfStale(PriceOffer offer) {
    if (offer.getStatus() == OfferStatus.PENDING
        && !offer.getExpiresAt().isAfter(clock().instant())) {
      offer.setStatus(OfferStatus.EXPIRED);
      offers.save(offer);
    }
  }
}
