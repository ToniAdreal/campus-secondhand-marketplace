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
 *   <li>One live offer per (buyer, item): a second POST while one is
 *       live (PENDING, or COUNTERED since #193) is a documented 409 —
 *       the buyer withdraws it or waits for the seller's answer / the
 *       expiry, then submits a fresh offer. It never silently
 *       supersedes and never stores a second live row; the Flyway
 *       V34/V52 {@code live_offer_key} guard is the database-level
 *       arbiter under races.</li>
 *   <li>Counter (seller side, backlog #193): the seller may answer a
 *       PENDING offer once with a counter price strictly above the
 *       buyer's offer and strictly below the listing's asking price
 *       (outside either bound → 422). The offer flips PENDING →
 *       COUNTERED carrying the counter price; a second counter is 422
 *       (the state guard — the offer is no longer PENDING). The buyer
 *       then accepts the counter (an order is created at the COUNTER
 *       price through the same {@link OrderCreationService} seam and
 *       race discipline as the seller's accept) or declines it
 *       (terminal DECLINED, listing untouched). The seller's own
 *       accept/decline and the buyer's withdraw stay PENDING-only —
 *       on a COUNTERED offer they answer 422, because the decision has
 *       moved to the buyer.</li>
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
 *   <li>Expiry: a live offer carries {@code expiresAt} (creation +
 *       {@code app.offers.pending-ttl}, default 48 h). {@link
 *       OfferExpiryService} flips stale PENDING and COUNTERED rows to
 *       EXPIRED — countering does not extend the deadline; the decision
 *       endpoints also check the deadline lazily, so a stale offer can
 *       never be decided in the gap between deadline and job run.</li>
 *   <li>Notifications (#157 pattern): accept notifies the buyer
 *       (OFFER_ACCEPTED, carrying the created order id), decline
 *       notifies the buyer (OFFER_DECLINED, no order id — none
 *       exists). Counter notifies the buyer (OFFER_COUNTERED, no order
 *       id); counter-accept notifies the seller
 *       (OFFER_COUNTER_ACCEPTED, carrying the created order id) and
 *       counter-decline notifies the seller (OFFER_COUNTER_DECLINED,
 *       no order id). Every row is written inside its transition's own
 *       transaction, so a rolled-back transition notifies nobody.
 *       Withdraw notifies nobody — it is visible in the acting
 *       party's own offer list. Expiry (backlog #226) notifies the
 *       WAITING party only, through the same record seam: a PENDING
 *       expiry notifies the buyer (the seller never answered), a
 *       COUNTERED expiry notifies the seller (the buyer never
 *       answered the counter) — the party who let it lapse already
 *       knows, so they get nothing. The row carries the offer id
 *       and no order id, and the #216 preference check applies, so
 *       a muted OFFER_EXPIRED writes no row and fires no SSE hint.
 *       The sweep re-run finds the row EXPIRED and returns false,
 *       so the row is written exactly once.</li>
 * </ul>
 *
 * <p>Honest scope: at most one counter per offer (backlog #193) — there
 * is no back-and-forth chain; a declined offer (original or countered)
 * is final and the buyer may submit a fresh offer afterwards.
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
    if (offers.existsByItem_IdAndBuyerIdAndStatus(itemId, buyerId, OfferStatus.PENDING)
        || offers.existsByItem_IdAndBuyerIdAndStatus(itemId, buyerId, OfferStatus.COUNTERED)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "you already have a live offer on this item — withdraw it or wait for the "
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
          "you already have a live offer on this item — withdraw it or wait for the "
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
   * Counters a PENDING offer (seller side, backlog #193): the offer
   * flips to COUNTERED carrying the counter price, and the decision
   * moves to the buyer. The counter must sit strictly between the
   * buyer's offer and the listing's asking price — at or outside either
   * bound the negotiation is meaningless (the seller could simply
   * accept, or the buyer could simply buy now), so it answers 422.
   * One counter per offer: the state guard rejects a second counter
   * with 422, because a COUNTERED offer is no longer PENDING. The
   * deadline is unchanged — countering buys no extra time. The buyer
   * is notified in this transaction.
   */
  @Transactional
  public OfferDto counter(Long sellerId, Long offerId, Long counterPriceCents) {
    PriceOffer offer = offers.findByIdAndSellerId(offerId, sellerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found"));
    expireIfStale(offer);
    if (offer.getStatus() != OfferStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "offer is " + offer.getStatus() + ", it cannot be countered");
    }
    if (counterPriceCents <= offer.getPriceCents()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "counter price must be above the buyer's offered price");
    }
    if (counterPriceCents >= offer.getItem().getPriceCents()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "counter price must be below the listing's asking price");
    }
    try {
      offer.setStatus(OfferStatus.COUNTERED);
      offer.setCounterPriceCents(counterPriceCents);
      OfferDto dto = OfferDto.from(offers.save(offer));
      offers.flush();
      notifications().recordOffer(offer.getBuyerId(), NotificationType.OFFER_COUNTERED,
          offer.getId(), null);
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "offer was updated concurrently, please retry");
    }
  }

  /**
   * Accepts the seller's counter (buyer side, backlog #193): creates
   * the order at the COUNTER price through {@link OrderCreationService}
   * inside this transaction — the same atomicity and race discipline as
   * the seller's accept (see {@link #accept}). The seller is notified
   * with the created order id.
   */
  @Transactional
  public OfferDto counterAccept(Long buyerId, Long offerId) {
    PriceOffer offer = offers.findByIdAndBuyerId(offerId, buyerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found"));
    expireIfStale(offer);
    if (offer.getStatus() != OfferStatus.COUNTERED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "offer is " + offer.getStatus() + ", it has no counter to accept");
    }
    OrderDto order = creation.create(offer.getBuyerId(), offer.getItem().getId(),
        offer.getCounterPriceCents());
    try {
      offer.setStatus(OfferStatus.ACCEPTED);
      offer.setOrderId(order.id());
      OfferDto dto = OfferDto.from(offers.save(offer));
      offers.flush();
      notifications().recordOffer(offer.getSellerId(), NotificationType.OFFER_COUNTER_ACCEPTED,
          offer.getId(), order.id());
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "offer was updated concurrently, please retry");
    }
  }

  /**
   * Declines the seller's counter (buyer side, backlog #193). Terminal
   * DECLINED — the same end state as a seller decline, with the counter
   * price kept on the row as negotiation history; the listing is never
   * touched and no order is created. The seller is notified in this
   * transaction. Declining frees the live pair, so the buyer may submit
   * a fresh offer immediately afterwards.
   */
  @Transactional
  public OfferDto counterDecline(Long buyerId, Long offerId) {
    PriceOffer offer = offers.findByIdAndBuyerId(offerId, buyerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "offer not found"));
    expireIfStale(offer);
    if (offer.getStatus() != OfferStatus.COUNTERED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "offer is " + offer.getStatus() + ", it has no counter to decline");
    }
    try {
      offer.setStatus(OfferStatus.DECLINED);
      OfferDto dto = OfferDto.from(offers.save(offer));
      offers.flush();
      notifications().recordOffer(offer.getSellerId(), NotificationType.OFFER_COUNTER_DECLINED,
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
   * Expires one stale live offer (PENDING or COUNTERED, backlog #193 —
   * a countered offer lapses on the same deadline; countering buys no
   * extra time) — the per-row step of {@link OfferExpiryService}.
   * Status and deadline are re-checked inside this transaction: an
   * offer answered between the job's worklist scan and this call simply
   * returns {@code false}, never wrongly expired. A concurrent
   * transition surfaces as
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
    if ((offer.getStatus() != OfferStatus.PENDING && offer.getStatus() != OfferStatus.COUNTERED)
        || offer.getExpiresAt().isAfter(now)) {
      return false;
    }
    // Capture the pre-flip state first: it decides who was waiting.
    // A PENDING offer's buyer waited on the seller's answer; a
    // COUNTERED offer's seller waited on the buyer's answer (backlog
    // #226). The party who let it lapse already knows, so they get
    // nothing. Recorded after the flush, so a lost @Version race
    // notifies nobody, and inside this same transaction, so a
    // rolled-back flip leaves no row; the #216 preference check in
    // the record seam applies, and the after-commit SSE hint (#203)
    // fires from that same seam. A sweep re-run finds the row
    // EXPIRED and returns false above, so the row is written once.
    OfferStatus was = offer.getStatus();
    offer.setStatus(OfferStatus.EXPIRED);
    offers.save(offer);
    offers.flush();
    Long waitingParty = was == OfferStatus.PENDING ? offer.getBuyerId() : offer.getSellerId();
    notifications().recordOffer(waitingParty, NotificationType.OFFER_EXPIRED,
        offer.getId(), null);
    return true;
  }

  /**
   * Lazy deadline check for the decision endpoints: a live offer
   * (PENDING or COUNTERED) past its deadline is treated as EXPIRED
   * before the state guard runs, so a stale offer answers 422 instead
   * of being decided in the gap before the expiry job's next run. The
   * in-transaction flip rolls back with the rejected decision — the
   * persisted EXPIRED write is the expiry job's, at most one cadence
   * later; the lazy check's guarantee is the decision, never the row:
   * a stale offer can never be accepted, declined or countered.
   */
  private void expireIfStale(PriceOffer offer) {
    if ((offer.getStatus() == OfferStatus.PENDING || offer.getStatus() == OfferStatus.COUNTERED)
        && !offer.getExpiresAt().isAfter(clock().instant())) {
      offer.setStatus(OfferStatus.EXPIRED);
      offers.save(offer);
    }
  }
}
