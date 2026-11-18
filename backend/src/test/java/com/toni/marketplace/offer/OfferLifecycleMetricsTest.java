package com.toni.marketplace.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.order.OrderCreationService;
import com.toni.marketplace.order.OrderDto;
import com.toni.marketplace.order.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backlog #393 — the six offer-lifecycle counters {@link OfferMetrics}
 * gains: {@code offers.created}, {@code offers.accepted},
 * {@code offers.declined}, {@code offers.countered},
 * {@code offers.counter_accepted} and {@code offers.expired}. Pure unit
 * tests, mirroring {@code OrderLifecycleMetricsTest} (#348): the service
 * is constructed by hand with mocked repositories, the counters live on
 * a throwaway {@link SimpleMeterRegistry}, and every assertion reads
 * the counter itself — so a counter that forgets to move, or moves on
 * the wrong transition, fails here. No Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
class OfferLifecycleMetricsTest {

  private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
  private static final long SELLER_ID = 11L;
  private static final long BUYER_ID = 22L;
  private static final long OFFER_ID = 33L;
  private static final long ITEM_ID = 5L;
  private static final long ORDER_ID = 44L;
  private static final long ASKING_CENTS = 10_000L;
  private static final long OFFERED_CENTS = 7_500L;
  private static final long COUNTER_CENTS = 8_500L;

  @Mock
  private PriceOfferRepository offers;

  @Mock
  private ItemRepository items;

  @Mock
  private OrderCreationService creation;

  private MeterRegistry registry;
  private OfferService offerService;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    OfferMetrics metrics = new OfferMetrics(registry);
    offerService = new OfferService(offers, items, creation, new OfferProperties(),
        Clock.fixed(NOW, ZoneOffset.UTC), metrics);
  }

  private double count(String name) {
    return registry.get(name).counter().count();
  }

  private Item listing() {
    Item item = new Item("Film camera", "Canon AE-1", ASKING_CENTS, SELLER_ID);
    item.setStatus(ItemStatus.AVAILABLE);
    ReflectionTestUtils.setField(item, "id", ITEM_ID);
    return item;
  }

  private PriceOffer liveOffer() {
    return liveOffer(OfferStatus.PENDING, NOW.plusSeconds(48 * 3600));
  }

  private PriceOffer liveOffer(OfferStatus status, Instant expiresAt) {
    PriceOffer offer = new PriceOffer(listing(), BUYER_ID, SELLER_ID, OFFERED_CENTS, expiresAt);
    offer.setStatus(status);
    ReflectionTestUtils.setField(offer, "id", OFFER_ID);
    return offer;
  }

  private void stubOfferSave() {
    when(offers.save(any(PriceOffer.class))).thenAnswer(i -> i.getArgument(0));
  }

  private OrderDto createdOrder(long amountCents) {
    return new OrderDto(ORDER_ID, ITEM_ID, BUYER_ID, OrderStatus.PENDING, amountCents,
        NOW, null, null, 0L, null);
  }

  @Test
  void createdCounter_createMovesOnlyCreated_secondLiveOffer409MovesNothing() {
    Item item = listing();
    when(items.findById(ITEM_ID)).thenReturn(Optional.of(item));
    when(offers.existsByItem_IdAndBuyerIdAndStatus(anyLong(), anyLong(), any()))
        .thenReturn(false);
    stubOfferSave();

    OfferDto dto = offerService.createOffer(BUYER_ID, ITEM_ID, OFFERED_CENTS);

    assertThat(dto.status()).isEqualTo(OfferStatus.PENDING);
    assertThat(count("offers.created")).isEqualTo(1.0);
    assertThat(count("offers.accepted")).isZero();
    assertThat(count("offers.declined")).isZero();
    assertThat(count("offers.countered")).isZero();
    assertThat(count("offers.counter_accepted")).isZero();
    assertThat(count("offers.expired")).isZero();

    // A second live offer is a 409 — no second count.
    when(offers.existsByItem_IdAndBuyerIdAndStatus(anyLong(), anyLong(), any()))
        .thenReturn(true);
    assertThatThrownBy(() -> offerService.createOffer(BUYER_ID, ITEM_ID, OFFERED_CENTS))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    assertThat(count("offers.created")).isEqualTo(1.0);
  }

  @Test
  void acceptedCounter_acceptMovesOnlyAccepted_reacceptMovesNothing() {
    PriceOffer offer = liveOffer();
    when(offers.findByIdAndSellerId(OFFER_ID, SELLER_ID)).thenReturn(Optional.of(offer));
    when(creation.create(BUYER_ID, ITEM_ID, OFFERED_CENTS))
        .thenReturn(createdOrder(OFFERED_CENTS));
    stubOfferSave();

    OfferDto dto = offerService.accept(SELLER_ID, OFFER_ID);

    assertThat(dto.status()).isEqualTo(OfferStatus.ACCEPTED);
    assertThat(count("offers.accepted")).isEqualTo(1.0);
    assertThat(count("offers.created")).isZero();
    assertThat(count("offers.declined")).isZero();
    assertThat(count("offers.countered")).isZero();
    assertThat(count("offers.counter_accepted")).isZero();
    assertThat(count("offers.expired")).isZero();

    // Sequential re-accept is a 422 — no second count.
    assertThatThrownBy(() -> offerService.accept(SELLER_ID, OFFER_ID))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThat(count("offers.accepted")).isEqualTo(1.0);
  }

  @Test
  void declinedCounter_declineMovesOnlyDeclined_redeclineMovesNothing() {
    PriceOffer offer = liveOffer();
    when(offers.findByIdAndSellerId(OFFER_ID, SELLER_ID)).thenReturn(Optional.of(offer));
    stubOfferSave();

    OfferDto dto = offerService.decline(SELLER_ID, OFFER_ID);

    assertThat(dto.status()).isEqualTo(OfferStatus.DECLINED);
    assertThat(count("offers.declined")).isEqualTo(1.0);
    assertThat(count("offers.accepted")).isZero();
    assertThat(count("offers.countered")).isZero();

    // A second decline is a 422 — no second count.
    assertThatThrownBy(() -> offerService.decline(SELLER_ID, OFFER_ID))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThat(count("offers.declined")).isEqualTo(1.0);
  }

  @Test
  void counteredCounter_counterMovesOnlyCountered_secondCounterMovesNothing() {
    PriceOffer offer = liveOffer();
    when(offers.findByIdAndSellerId(OFFER_ID, SELLER_ID)).thenReturn(Optional.of(offer));
    stubOfferSave();

    OfferDto dto = offerService.counter(SELLER_ID, OFFER_ID, COUNTER_CENTS);

    assertThat(dto.status()).isEqualTo(OfferStatus.COUNTERED);
    assertThat(dto.counterPriceCents()).isEqualTo(COUNTER_CENTS);
    assertThat(count("offers.countered")).isEqualTo(1.0);
    assertThat(count("offers.accepted")).isZero();
    assertThat(count("offers.declined")).isZero();

    // A second counter is a 422 — no second count.
    assertThatThrownBy(() -> offerService.counter(SELLER_ID, OFFER_ID, COUNTER_CENTS))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThat(count("offers.countered")).isEqualTo(1.0);
  }

  @Test
  void counterAcceptedCounter_counterAcceptMovesOnlyCounterAccepted_reacceptMovesNothing() {
    PriceOffer offer = liveOffer(OfferStatus.COUNTERED, NOW.plusSeconds(48 * 3600));
    offer.setCounterPriceCents(COUNTER_CENTS);
    when(offers.findByIdAndBuyerId(OFFER_ID, BUYER_ID)).thenReturn(Optional.of(offer));
    when(creation.create(BUYER_ID, ITEM_ID, COUNTER_CENTS))
        .thenReturn(createdOrder(COUNTER_CENTS));
    stubOfferSave();

    OfferDto dto = offerService.counterAccept(BUYER_ID, OFFER_ID);

    assertThat(dto.status()).isEqualTo(OfferStatus.ACCEPTED);
    assertThat(count("offers.counter_accepted")).isEqualTo(1.0);
    assertThat(count("offers.accepted")).isZero();
    assertThat(count("offers.countered")).isZero();
    assertThat(count("offers.declined")).isZero();

    // Sequential re-accept is a 422 — no second count.
    assertThatThrownBy(() -> offerService.counterAccept(BUYER_ID, OFFER_ID))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThat(count("offers.counter_accepted")).isEqualTo(1.0);
  }

  @Test
  void declinedCounter_counterDeclineMovesDeclinedNotCountered() {
    PriceOffer offer = liveOffer(OfferStatus.COUNTERED, NOW.plusSeconds(48 * 3600));
    offer.setCounterPriceCents(COUNTER_CENTS);
    when(offers.findByIdAndBuyerId(OFFER_ID, BUYER_ID)).thenReturn(Optional.of(offer));
    stubOfferSave();

    OfferDto dto = offerService.counterDecline(BUYER_ID, OFFER_ID);

    assertThat(dto.status()).isEqualTo(OfferStatus.DECLINED);
    assertThat(count("offers.declined")).isEqualTo(1.0);
    assertThat(count("offers.countered")).isZero();
    assertThat(count("offers.counter_accepted")).isZero();
  }

  @Test
  void expiredCounter_expireStaleOfferMovesOnlyExpired_rerunMovesNothing() {
    PriceOffer offer = liveOffer(OfferStatus.PENDING, NOW.minusSeconds(3600));
    when(offers.findById(OFFER_ID)).thenReturn(Optional.of(offer));
    stubOfferSave();

    assertThat(offerService.expireStaleOffer(OFFER_ID, NOW)).isTrue();
    assertThat(count("offers.expired")).isEqualTo(1.0);
    assertThat(count("offers.declined")).isZero();
    assertThat(count("offers.accepted")).isZero();

    // A sweep re-run finds the row EXPIRED — no second count.
    assertThat(offerService.expireStaleOffer(OFFER_ID, NOW)).isFalse();
    assertThat(count("offers.expired")).isEqualTo(1.0);
  }

  @Test
  void expiredCounter_lazyStaleDeclineMovesNothing() {
    PriceOffer offer = liveOffer(OfferStatus.PENDING, NOW.minusSeconds(3600));
    when(offers.findByIdAndSellerId(OFFER_ID, SELLER_ID)).thenReturn(Optional.of(offer));
    stubOfferSave();

    // The lazy deadline flip answers 422 — the job's flip is the only
    // counted expiry, and it has not run, so nothing moves.
    assertThatThrownBy(() -> offerService.decline(SELLER_ID, OFFER_ID))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThat(count("offers.expired")).isZero();
    assertThat(count("offers.declined")).isZero();
  }

  @Test
  void noCounter_withdrawMovesNothing() {
    PriceOffer offer = liveOffer();
    when(offers.findByIdAndBuyerId(OFFER_ID, BUYER_ID)).thenReturn(Optional.of(offer));
    stubOfferSave();

    OfferDto dto = offerService.withdraw(BUYER_ID, OFFER_ID);

    assertThat(dto.status()).isEqualTo(OfferStatus.WITHDRAWN);
    assertThat(count("offers.created")).isZero();
    assertThat(count("offers.accepted")).isZero();
    assertThat(count("offers.declined")).isZero();
    assertThat(count("offers.countered")).isZero();
    assertThat(count("offers.counter_accepted")).isZero();
    assertThat(count("offers.expired")).isZero();
  }
}
