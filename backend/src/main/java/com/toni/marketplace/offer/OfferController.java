package com.toni.marketplace.offer;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Price offers (backlog #168). Every route is authenticated (the
 * security chain's anyRequest rule; anonymous callers get the 401
 * envelope) and every id is resolved against the JWT principal — the
 * buyer/seller scoping lives in {@link OfferService}, never in a
 * request parameter.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "offers", description = "Price offers on listings")
public class OfferController {

  private final OfferService offerService;

  public OfferController(OfferService offerService) {
    this.offerService = offerService;
  }

  /**
   * Proposes a price on a listing. The caller is the buyer; offering on
   * your own listing is 422, on a non-AVAILABLE listing 409, and a
   * second live offer on the same listing is 409 (withdraw the first
   * or wait for its answer/expiry — see OfferService).
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "validation failure — priceCents missing or not positive (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown listing id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "listing not AVAILABLE, or the caller already has a pending offer on it (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "the listing is the caller's own (envelope)")})
  @PostMapping("/api/items/{itemId}/offers")
  public ApiResponse<OfferDto> create(@PathVariable Long itemId,
                                      @Valid @RequestBody OfferCreateRequest request) {
    return ApiResponse.ok(offerService.createOffer(currentUserId(), itemId, request.priceCents()));
  }

  /** The caller's own offers as a buyer, newest first. */
  @GetMapping("/api/offers/made")
  public ApiResponse<Page<OfferDto>> made(
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(offerService.listBuyerOffers(currentUserId(), pageable));
  }

  /** Offers on the caller's own listings, newest first. */
  @GetMapping("/api/offers/received")
  public ApiResponse<Page<OfferDto>> received(
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(offerService.listSellerOffers(currentUserId(), pageable));
  }

  /**
   * Accepts an offer on the caller's listing: creates the order at the
   * agreed price and reserves the listing, atomically. Someone else's
   * offer id is 404; a non-PENDING offer is 422; losing the race to a
   * buy-now (or a concurrent accept) is 409.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown offer id, or an offer on someone else's listing (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "the listing already has an active order, or a version conflict (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "the offer is not PENDING (envelope)")})
  @PostMapping("/api/offers/{id}/accept")
  public ApiResponse<OfferDto> accept(@PathVariable Long id) {
    return ApiResponse.ok(offerService.accept(currentUserId(), id));
  }

  /** Declines an offer on the caller's listing. Terminal; no order is created. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown offer id, or an offer on someone else's listing (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent transition (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "the offer is not PENDING (envelope)")})
  @PostMapping("/api/offers/{id}/decline")
  public ApiResponse<OfferDto> decline(@PathVariable Long id) {
    return ApiResponse.ok(offerService.decline(currentUserId(), id));
  }

  /**
   * Counters an offer on the caller's listing (backlog #193): the one
   * counter moves the offer to COUNTERED at the counter price and the
   * decision to the buyer. The counter must sit strictly between the
   * buyer's offer and the asking price (outside either bound is 422);
   * someone else's offer id is 404; a non-PENDING offer is 422.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "validation failure — priceCents missing or not positive (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown offer id, or an offer on someone else's listing (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent transition (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "the offer is not PENDING, or the counter price is outside "
              + "(buyer's offer, asking price) (envelope)")})
  @PostMapping("/api/offers/{id}/counter")
  public ApiResponse<OfferDto> counter(@PathVariable Long id,
                                       @Valid @RequestBody OfferCounterRequest request) {
    return ApiResponse.ok(offerService.counter(currentUserId(), id, request.priceCents()));
  }

  /**
   * Accepts the seller's counter on the caller's own offer (backlog
   * #193): creates the order at the COUNTER price and reserves the
   * listing, atomically. Someone else's offer id is 404; an offer with
   * no live counter is 422; losing the race to a buy-now is 409.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown offer id, or someone else's offer (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "the listing already has an active order, or a version conflict (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "the offer is not COUNTERED (envelope)")})
  @PostMapping("/api/offers/{id}/counter-accept")
  public ApiResponse<OfferDto> counterAccept(@PathVariable Long id) {
    return ApiResponse.ok(offerService.counterAccept(currentUserId(), id));
  }

  /**
   * Declines the seller's counter on the caller's own offer (backlog
   * #193). Terminal; no order is created and the listing is untouched.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown offer id, or someone else's offer (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent transition (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "the offer is not COUNTERED (envelope)")})
  @PostMapping("/api/offers/{id}/counter-decline")
  public ApiResponse<OfferDto> counterDecline(@PathVariable Long id) {
    return ApiResponse.ok(offerService.counterDecline(currentUserId(), id));
  }

  /** Withdraws the caller's own offer. Terminal; frees the live pair for a fresh offer. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown offer id, or someone else's offer (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent transition (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "the offer is not PENDING (envelope)")})
  @PostMapping("/api/offers/{id}/withdraw")
  public ApiResponse<OfferDto> withdraw(@PathVariable Long id) {
    return ApiResponse.ok(offerService.withdraw(currentUserId(), id));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
