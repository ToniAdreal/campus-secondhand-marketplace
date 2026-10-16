package com.toni.marketplace.auth;

import com.toni.marketplace.item.ItemService;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.item.ItemDto;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import com.toni.marketplace.review.SellerReviewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public seller profiles (backlog #174). A buyer looking at a listing
 * sees only a seller id in the DTO; this service is the storefront
 * behind it: who the seller is, how long they have been around, how
 * much live stock they have, and how many sales they have completed.
 *
 * <p>Visibility rule: an unknown id is 404, and a disabled account
 * (#88) reads as 404 too — a disabled seller has no storefront, and
 * answering with a flagged profile would leak the moderation state.
 * Both reads share the same gate ({@link #requireVisibleSeller}), so
 * the profile and its listings can never disagree about whether the
 * seller exists.
 */
@Service
public class SellerProfileService {

  private final UserRepository users;
  private final ItemRepository items;
  private final OrderRepository orders;
  private final ItemService itemService;
  private final SellerReviewRepository reviews;

  public SellerProfileService(UserRepository users, ItemRepository items,
                              OrderRepository orders, ItemService itemService,
                              SellerReviewRepository reviews) {
    this.users = users;
    this.items = items;
    this.orders = orders;
    this.itemService = itemService;
    this.reviews = reviews;
  }

  @Transactional(readOnly = true)
  public PublicSellerDto profile(Long sellerId) {
    User seller = requireVisibleSeller(sellerId);
    // The review aggregate (#175) is computed from the review rows on
    // every read — never stored — so it cannot drift from the list the
    // profile page renders below the header.
    SellerReviewRepository.RatingAggregate aggregate =
        reviews.summarizeBySellerId(sellerId);
    return new PublicSellerDto(
        seller.getUsername(),
        seller.getCreatedAt(),
        items.countBySellerIdAndStatus(sellerId, ItemStatus.AVAILABLE),
        orders.countByItem_SellerIdAndStatus(sellerId, OrderStatus.COMPLETED),
        aggregate.getReviewCount(),
        aggregate.getAverageRating());
  }

  /**
   * The seller's live storefront: AVAILABLE listings only, through the
   * seller-scoped projection in {@link ItemService} — RESERVED / SOLD /
   * WITHDRAWN stock is the seller's management view (#146), not a
   * storefront.
   */
  @Transactional(readOnly = true)
  public Page<ItemDto> liveListings(Long sellerId, Pageable pageable) {
    requireVisibleSeller(sellerId);
    return itemService.listAvailableListingsForSeller(sellerId, pageable);
  }

  private User requireVisibleSeller(Long sellerId) {
    User seller = users.findById(sellerId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "seller not found"));
    if (seller.isDisabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "seller not found");
    }
    return seller;
  }
}
