package com.toni.marketplace.item;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {
  List<Item> findByStatus(ItemStatus status);

  /**
   * One seller's listings in one status — the account-closure sweep
   * (backlog #158) flips exactly the caller's {@code AVAILABLE} rows to
   * {@code WITHDRAWN}; RESERVED rows (a live order) and SOLD rows (sale
   * history) are never touched by it.
   */
  List<Item> findBySellerIdAndStatus(Long sellerId, ItemStatus status);

  /** Count of one seller's listings in one status — the public profile's live-stock count (#174). */
  long countBySellerIdAndStatus(Long sellerId, ItemStatus status);

  /**
   * Public storefront projection (backlog #174): one seller's AVAILABLE
   * listings only, same constructor projection as {@link #findListView}
   * (single SELECT, seller username via the ad-hoc join). Unlike the
   * #146 seller management view there IS a status predicate — RESERVED /
   * SOLD / WITHDRAWN stock is not a storefront.
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt, i.expiresAt) "
          + "from Item i left join i.category c "
          + "left join User u on u.id = i.sellerId "
          + "where i.sellerId = :sellerId and i.status = :status "
          + "and not (i.status = 'AVAILABLE' and i.expiresAt is not null "
          + "and i.expiresAt <= current_timestamp)",
      countQuery = "select count(i) from Item i "
          + "where i.sellerId = :sellerId and i.status = :status "
          + "and not (i.status = 'AVAILABLE' and i.expiresAt is not null "
          + "and i.expiresAt <= current_timestamp)")
  Page<ItemDto> findListViewBySellerAndStatus(@Param("sellerId") Long sellerId,
                                              @Param("status") ItemStatus status,
                                              Pageable pageable);

  /**
   * Every non-null primary photo URL across all listings — the reference
   * set the orphan-upload sweeper (backlog #103) checks disk files
   * against. A plain column projection: no entities are materialized.
   */
  @Query("select i.photoUrl from Item i where i.photoUrl is not null")
  List<String> findAllPhotoUrls();

  /**
   * Worklist for the listing-expiry job (backlog #177): ids of listings
   * in the given status whose lifetime deadline has passed. Projects
   * only the id so the nightly scan stays narrow; the per-row flip
   * re-checks {@link Item#isExpiredAt} under its own transaction, so a
   * row renewed between the scan and the flip is skipped, never
   * wrongly expired.
   */
  @Query("select i.id from Item i where i.status = :status "
      + "and i.expiresAt is not null and i.expiresAt <= :now")
  List<Long> findIdsByStatusAndExpiresAtBefore(@Param("status") ItemStatus status,
                                               @Param("now") Instant now,
                                               Pageable pageable);

  /** True when at least one listing references the category (via the item.category association). */
  boolean existsByCategoryId(Long categoryId);

  /**
   * List view as a constructor DTO projection: one SELECT with an explicit
   * LEFT JOIN for the optional category. Rendering the list through
   * {@code findAll} + entity mapping would fire one lazy SELECT per
   * categorized item (N+1) when the mapper reads
   * {@code item.getCategory().getName()}; the projection fetches everything
   * the list view renders in a single query. The explicit LEFT JOIN (not an
   * implicit path navigation) keeps uncategorized rows in the page.
   * {@code countQuery} drives {@link Page#getTotalElements()}. The seller's
   * username rides along via an ad-hoc join on the raw {@code sellerId} FK
   * (there is no JPA association from item to user), still in the same
   * single SELECT — no per-row user lookup.
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt) "
          + "from Item i left join i.category c "
          + "left join User u on u.id = i.sellerId",
      countQuery = "select count(i) from Item i")
  Page<ItemDto> findListView(Pageable pageable);

  /**
   * Category-filtered list view, same projection as {@link #findListView}.
   * Served by the {@code idx_item_category_status_created} composite index
   * (category_id, status, created_at) from Flyway V3.
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt) "
          + "from Item i left join i.category c "
          + "left join User u on u.id = i.sellerId where i.category.id = :categoryId",
      countQuery = "select count(i) from Item i where i.category.id = :categoryId")
  Page<ItemDto> findListViewByCategoryId(@Param("categoryId") Long categoryId, Pageable pageable);

  /**
   * Combined category + keyword + price-range list view, same constructor projection as
   * {@link #findListView} (single SELECT, no N+1). Both parameters are
   * optional: a null {@code categoryId} matches every category and a null
   * {@code keyword} disables the text filter. The keyword is matched
   * case-insensitively against title and description; {@code %}, {@code _}
   * and the escape character are escaped ({@code LIKE ... ESCAPE}) so a
   * literal user search string never acts as a wildcard.
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt, i.expiresAt) "
          + "from Item i left join i.category c "
          + "left join User u on u.id = i.sellerId "
          + "where (:categoryId is null or i.category.id = :categoryId) "
          + "and (:minPriceCents is null or i.priceCents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.priceCents <= :maxPriceCents) "
          + "and i.status <> 'EXPIRED' "
          + "and not (i.status = 'AVAILABLE' and i.expiresAt is not null "
          + "and i.expiresAt <= current_timestamp) "
          + "and (:keyword is null "
          + "or lower(i.title) like lower(concat('%', :keyword, '%')) escape '\\' "
          + "or lower(i.description) like lower(concat('%', :keyword, '%')) escape '\\')",
      countQuery = "select count(i) from Item i "
          + "where (:categoryId is null or i.category.id = :categoryId) "
          + "and (:minPriceCents is null or i.priceCents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.priceCents <= :maxPriceCents) "
          + "and i.status <> 'EXPIRED' "
          + "and not (i.status = 'AVAILABLE' and i.expiresAt is not null "
          + "and i.expiresAt <= current_timestamp) "
          + "and (:keyword is null "
          + "or lower(i.title) like lower(concat('%', :keyword, '%')) escape '\\' "
          + "or lower(i.description) like lower(concat('%', :keyword, '%')) escape '\\')")
  Page<ItemDto> findListViewFiltered(@Param("categoryId") Long categoryId,
                                    @Param("keyword") String keyword,
                                    @Param("minPriceCents") Long minPriceCents,
                                    @Param("maxPriceCents") Long maxPriceCents,
                                    Pageable pageable);

  /**
   * Seller-scoped variant of {@link #findListViewFiltered} (backlog #146):
   * the same constructor projection and the same optional category /
   * keyword / price predicates, plus {@code i.sellerId = :sellerId}. The
   * seller id is always resolved server-side from the caller's token (the
   * {@code mine=true} list shortcut) — it is never a client-supplied
   * parameter, so this query cannot be pointed at another seller. Unlike
   * the public list path there is no status predicate anywhere in the
   * list projections, so the caller's RESERVED and SOLD rows ride along,
   * which is the point of the seller view.
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt, i.expiresAt) "
          + "from Item i left join i.category c "
          + "left join User u on u.id = i.sellerId "
          + "where i.sellerId = :sellerId "
          + "and (:categoryId is null or i.category.id = :categoryId) "
          + "and (:minPriceCents is null or i.priceCents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.priceCents <= :maxPriceCents) "
          + "and (:keyword is null "
          + "or lower(i.title) like lower(concat('%', :keyword, '%')) escape '\\' "
          + "or lower(i.description) like lower(concat('%', :keyword, '%')) escape '\\')",
      countQuery = "select count(i) from Item i "
          + "where i.sellerId = :sellerId "
          + "and (:categoryId is null or i.category.id = :categoryId) "
          + "and (:minPriceCents is null or i.priceCents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.priceCents <= :maxPriceCents) "
          + "and (:keyword is null "
          + "or lower(i.title) like lower(concat('%', :keyword, '%')) escape '\\' "
          + "or lower(i.description) like lower(concat('%', :keyword, '%')) escape '\\')")
  Page<ItemDto> findListViewFilteredBySeller(@Param("sellerId") Long sellerId,
                                    @Param("categoryId") Long categoryId,
                                    @Param("keyword") String keyword,
                                    @Param("minPriceCents") Long minPriceCents,
                                    @Param("maxPriceCents") Long maxPriceCents,
                                    Pageable pageable);

  /**
   * Detail view: JPQL fetch join pulls the optional category together with
   * the item, so the {@link ItemDto} mapping needs no extra lazy query.
   * The gallery ({@link ItemPhoto}) is fetch-joined in the same SELECT so
   * mapping {@link ItemDto#photos()} fires no lazy per-photo round-trip;
   * {@code distinct} collapses the photo fan-out back to one row.
   */
  @Query("select distinct i from Item i left join fetch i.category "
      + "left join fetch i.photos where i.id = :id")
  Optional<Item> findDetailById(@Param("id") Long id);

  /**
   * Detail view as a constructor DTO projection: the same ad-hoc seller
   * join as the list view, in a single SELECT. The gallery cannot be
   * expressed in a constructor projection, so the service merges it with
   * one batch query afterwards (bounded — never per-item).
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt, i.expiresAt) "
          + "from Item i left join i.category c "
          + "left join User u on u.id = i.sellerId where i.id = :id")
  Optional<ItemDto> findDetailViewById(@Param("id") Long id);

  /**
   * Batch variant of the list-view projection for a known id set — used by
   * the MySQL full-text path, whose entity hydration cannot reach the
   * seller username (no item→user association exists to fetch-join). One
   * SELECT for the whole page; the caller re-applies its own ordering.
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt, i.expiresAt) "
          + "from Item i left join i.category c "
          + "left join User u on u.id = i.sellerId where i.id in :ids")
  List<ItemDto> findListViewsByIds(@Param("ids") List<Long> ids);

  /**
   * MySQL-only full-text search, natural-language mode, served by the
   * {@code FULLTEXT(title, description)} index (Flyway
   * {@code db/vendor/mysql/V15__item_fulltext_index.sql}). Never called on
   * the H2 profile — see {@link ItemService#isMySql()} — because H2 has no
   * {@code MATCH ... AGAINST} support. Returns only the ids, relevance-ordered
   * (best match first, then newest row as a deterministic tiebreak); the
   * caller's {@link Pageable} must be unsorted because relevance ordering is
   * intrinsic to the query.
   *
   * <p>Honest MySQL quirks: words shorter than {@code ft_min_word_len}
   * (default 4) and stopwords are ignored by natural-language mode, so a
   * keyword made only of those matches nothing.
   */
  @Query(
      value = "select i.id from item i "
          + "where (:categoryId is null or i.category_id = :categoryId) "
          + "and (:minPriceCents is null or i.price_cents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.price_cents <= :maxPriceCents) "
          + "and i.status <> 'EXPIRED' "
          + "and not (i.status = 'AVAILABLE' and i.expires_at is not null "
          + "and i.expires_at <= now()) "
          + "and match(i.title, i.description) against (:keyword in natural language mode) "
          + "order by match(i.title, i.description) against (:keyword in natural language mode) desc, "
          + "i.id desc",
      countQuery = "select count(*) from item i "
          + "where (:categoryId is null or i.category_id = :categoryId) "
          + "and (:minPriceCents is null or i.price_cents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.price_cents <= :maxPriceCents) "
          + "and i.status <> 'EXPIRED' "
          + "and not (i.status = 'AVAILABLE' and i.expires_at is not null "
          + "and i.expires_at <= now()) "
          + "and match(i.title, i.description) against (:keyword in natural language mode)",
      nativeQuery = true)
  Page<Long> findIdsByFulltext(@Param("categoryId") Long categoryId,
                               @Param("keyword") String keyword,
                               @Param("minPriceCents") Long minPriceCents,
                               @Param("maxPriceCents") Long maxPriceCents,
                               Pageable pageable);

  /**
   * Price-ascending variant of {@link #findIdsByFulltext}: an explicit
   * price sort replaces relevance ordering (the caller asked for cheapest
   * first, not best match first); the price-range predicate is identical.
   */
  @Query(
      value = "select i.id from item i "
          + "where (:categoryId is null or i.category_id = :categoryId) "
          + "and (:minPriceCents is null or i.price_cents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.price_cents <= :maxPriceCents) "
          + "and i.status <> 'EXPIRED' "
          + "and not (i.status = 'AVAILABLE' and i.expires_at is not null "
          + "and i.expires_at <= now()) "
          + "and match(i.title, i.description) against (:keyword in natural language mode) "
          + "order by i.price_cents asc, i.id asc",
      countQuery = "select count(*) from item i "
          + "where (:categoryId is null or i.category_id = :categoryId) "
          + "and (:minPriceCents is null or i.price_cents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.price_cents <= :maxPriceCents) "
          + "and i.status <> 'EXPIRED' "
          + "and not (i.status = 'AVAILABLE' and i.expires_at is not null "
          + "and i.expires_at <= now()) "
          + "and match(i.title, i.description) against (:keyword in natural language mode)",
      nativeQuery = true)
  Page<Long> findIdsByFulltextPriceAsc(@Param("categoryId") Long categoryId,
                               @Param("keyword") String keyword,
                               @Param("minPriceCents") Long minPriceCents,
                               @Param("maxPriceCents") Long maxPriceCents,
                               Pageable pageable);

  /** Price-descending variant of {@link #findIdsByFulltext}. */
  @Query(
      value = "select i.id from item i "
          + "where (:categoryId is null or i.category_id = :categoryId) "
          + "and (:minPriceCents is null or i.price_cents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.price_cents <= :maxPriceCents) "
          + "and i.status <> 'EXPIRED' "
          + "and not (i.status = 'AVAILABLE' and i.expires_at is not null "
          + "and i.expires_at <= now()) "
          + "and match(i.title, i.description) against (:keyword in natural language mode) "
          + "order by i.price_cents desc, i.id desc",
      countQuery = "select count(*) from item i "
          + "where (:categoryId is null or i.category_id = :categoryId) "
          + "and (:minPriceCents is null or i.price_cents >= :minPriceCents) "
          + "and (:maxPriceCents is null or i.price_cents <= :maxPriceCents) "
          + "and i.status <> 'EXPIRED' "
          + "and not (i.status = 'AVAILABLE' and i.expires_at is not null "
          + "and i.expires_at <= now()) "
          + "and match(i.title, i.description) against (:keyword in natural language mode)",
      nativeQuery = true)
  Page<Long> findIdsByFulltextPriceDesc(@Param("categoryId") Long categoryId,
                               @Param("keyword") String keyword,
                               @Param("minPriceCents") Long minPriceCents,
                               @Param("maxPriceCents") Long maxPriceCents,
                               Pageable pageable);

}
