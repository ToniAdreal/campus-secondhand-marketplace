package com.toni.marketplace.auth;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.item.FavoriteService;
import com.toni.marketplace.item.ItemDto;
import com.toni.marketplace.item.ItemService;
import com.toni.marketplace.item.SavedSearchService;
import com.toni.marketplace.message.MessageDto;
import com.toni.marketplace.message.MessageRepository;
import com.toni.marketplace.order.OrderDto;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.review.SellerReview;
import com.toni.marketplace.review.SellerReviewDto;
import com.toni.marketplace.review.SellerReviewRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds the caller's account data export (backlog #172). It is the
 * heaviest read in the app — every section of one user's data in a
 * single document — which is why the endpoint carries its own
 * rate-limit bucket in {@code AuthRateLimitFilter}, why every export
 * is audit-logged here, in this service's transaction, as the sensitive
 * read it is ({@link AuditAction#DATA_EXPORTED}, actor = target = the
 * account holder), and why backlog #188 additionally requires the
 * single-use step-up grant minted by {@link DataExportGrantService}
 * after a fresh password (+ TOTP) proof.
 *
 * <p>Section sourcing, deliberately reusing each domain's own read seam
 * so the export can never drift from what the API shows:
 * <ul>
 *   <li>listings — {@link ItemService}'s seller-scoped list view (the
 *       #146 projection, galleries merged), paged through in bounded
 *       pages of {@value #LISTING_PAGE_SIZE} until the seller's rows are
 *       exhausted;</li>
 *   <li>orders — {@link OrderDto} projections of the caller's buyer rows
 *       and of the rows placed on the caller's listings, newest first;</li>
 *   <li>messages — {@link MessageDto} projections of every row the
 *       caller sent or received, chronological (an archive reads in
 *       conversation order);</li>
 *   <li>favorites — {@link FavoriteService#listFavorites} as-is;</li>
 *   <li>saved searches — {@link SavedSearchService#listSavedSearches}
 *       as-is, so each row carries the same live match count the SPA
 *       shows;</li>
 *   <li>reviews — both directions (backlog #243), through the same
 *       {@link SellerReviewDto} the public seller page shows: reviews
 *       the caller wrote as a buyer (order ids included) and reviews
 *       the caller received as a seller (ratings included). The
 *       received rows resolve their reviewers' usernames in one batch
 *       lookup, mirroring {@code ReviewService.listReviews}; a review
 *       whose reviewer row is gone reads as a null username, never as
 *       another user's review.</li>
 * </ul>
 * No entity is ever serialized: every section is a DTO projection.
 */
@Service
public class AccountDataExportService {

  /** Bounded page for the listings sweep — never one unbounded scan. */
  static final int LISTING_PAGE_SIZE = 100;

  private final UserRepository users;
  private final ItemService itemService;
  private final OrderRepository orders;
  private final MessageRepository messages;
  private final FavoriteService favoriteService;
  private final SavedSearchService savedSearchService;
  private final SellerReviewRepository reviews;
  private final AuditService audit;
  private final Clock clock;
  private final DataExportGrantService exportGrants;

  public AccountDataExportService(UserRepository users, ItemService itemService,
                                  OrderRepository orders, MessageRepository messages,
                                  FavoriteService favoriteService,
                                  SavedSearchService savedSearchService,
                                  SellerReviewRepository reviews,
                                  AuditService audit, Clock clock,
                                  DataExportGrantService exportGrants) {
    this.users = users;
    this.itemService = itemService;
    this.orders = orders;
    this.messages = messages;
    this.favoriteService = favoriteService;
    this.savedSearchService = savedSearchService;
    this.reviews = reviews;
    this.audit = audit;
    this.clock = clock;
    this.exportGrants = exportGrants;
  }

  /**
   * The caller's full export. Backlog #188: the presented step-up
   * grant is consumed FIRST, inside this transaction — a missing,
   * unknown, expired, used or other-account grant is the identical
   * 401 before any data is read, and a successful build commits the
   * consumption with it (a failed build rolls it back, so the grant
   * survives for a retry). A principal whose row no longer exists
   * (closed mid-session) is 404 — the token outlived the account, and
   * there is no data to export.
   */
  @Transactional
  public AccountDataExportDto export(long userId, String rawGrant) {
    exportGrants.consumeForExport(userId, rawGrant);
    User user = users.findById(userId).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    AccountDataExportDto dto = new AccountDataExportDto(
        clock.instant(),
        AccountDataExportDto.Profile.of(user),
        allListings(userId),
        orders.findByBuyerIdOrderByCreatedAtDescIdDesc(userId).stream()
            .map(OrderDto::from).toList(),
        orders.findExportRowsBySellerId(userId).stream()
            .map(OrderDto::from).toList(),
        messages.findExportByParticipant(userId).stream()
            .map(MessageDto::from).toList(),
        favoriteService.listFavorites(userId),
        savedSearchService.listSavedSearches(userId),
        reviewsWritten(user, userId),
        reviewsReceived(userId));
    audit.record(userId, AuditAction.DATA_EXPORTED, AuditTargetType.USER, userId);
    return dto;
  }

  /** Reviews the caller wrote as a buyer, newest first; the reviewer is the caller. */
  private List<SellerReviewDto> reviewsWritten(User user, long userId) {
    return reviews.findByBuyerIdOrderByCreatedAtDescIdDesc(userId).stream()
        .map(r -> SellerReviewDto.from(r, user.getUsername())).toList();
  }

  /** Reviews the caller received as a seller, newest first; reviewers resolved in one batch. */
  private List<SellerReviewDto> reviewsReceived(long userId) {
    List<SellerReview> rows =
        reviews.findBySellerIdOrderByCreatedAtDescIdDesc(userId);
    Map<Long, String> usernames = new HashMap<>();
    users.findAllById(rows.stream().map(SellerReview::getBuyerId).toList())
        .forEach(u -> usernames.put(u.getId(), u.getUsername()));
    return rows.stream()
        .map(r -> SellerReviewDto.from(r, usernames.get(r.getBuyerId()))).toList();
  }

  /** Every page of the caller's seller-scoped list view, newest first. */
  private List<ItemDto> allListings(long userId) {
    List<ItemDto> all = new ArrayList<>();
    Page<ItemDto> page;
    int index = 0;
    do {
      page = itemService.listItems(PageRequest.of(index, LISTING_PAGE_SIZE),
          null, null, null, null, "newest", userId);
      all.addAll(page.getContent());
      index++;
    } while (page.hasNext());
    return all;
  }
}
