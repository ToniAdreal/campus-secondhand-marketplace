package com.toni.marketplace.item;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ItemService {

  private static final Logger log = LoggerFactory.getLogger(ItemService.class);

  private final ItemRepository items;
  private final CategoryRepository categories;
  private final ItemPhotoRepository itemPhotos;
  private final ItemMapper mapper;
  private final ImageStorageService imageStorage;
  private final DataSource dataSource;

  /** Maximum gallery photos per listing (backlog #69). */
  static final int MAX_PHOTOS = 6;

  /**
   * Lazily probed once: whether the connected database is MySQL (see
   * {@link #isMySql()}). Volatile so concurrent first requests see the same
   * answer without re-probing the pool.
   */
  private volatile Boolean mySqlDatabase;

  public ItemService(ItemRepository items, CategoryRepository categories,
                     ItemPhotoRepository itemPhotos, ItemMapper mapper,
                     ImageStorageService imageStorage, DataSource dataSource) {
    this.items = items;
    this.categories = categories;
    this.itemPhotos = itemPhotos;
    this.mapper = mapper;
    this.imageStorage = imageStorage;
    this.dataSource = dataSource;
  }

  /**
   * List view; optionally narrowed to one category and/or a free-text
   * keyword. Two search paths, chosen by the connected database:
   *
   * <ul>
   *   <li>MySQL — {@code MATCH(title, description) AGAINST (? IN NATURAL
   *       LANGUAGE MODE)} served by the FULLTEXT index (Flyway
   *       {@code db/vendor/mysql/V15__item_fulltext_index.sql}), results
   *       relevance-ordered.
   *   <li>Anything else (local H2) — the original case-insensitive
   *       leading-wildcard LIKE ({@link ItemRepository#findListViewFiltered}).
   * </ul>
   *
   * Both paths stay single-SELECT-per-page (constructor DTO projection on
   * the LIKE path; id-page + fetch-join hydrate on the MySQL path), so the
   * list view never degrades into N+1 lazy loads. The JPQL projections
   * cannot express the nested photo gallery, so the DTOs they build carry
   * an empty gallery and the service merges the real galleries with one
   * additional batch query per page ({@link #withPhotos(List)}) — still
   * bounded, never per-item.
   */
  @Transactional(readOnly = true)
  public Page<ItemDto> listItems(Pageable pageable, Long categoryId, String keyword) {
    return listItems(pageable, categoryId, keyword, null, null, null);
  }

  /**
   * List view with price-range filtering and an allowlisted sort
   * (backlog #96). {@code minPriceCents}/{@code maxPriceCents} are
   * inclusive bounds (null = unbounded); a negative bound, a min above
   * the max, or a sort outside {newest, price-asc, price-desc} is 400 in
   * the envelope — the sort is resolved to a fixed {@link Sort} here,
   * never passed through as a raw property name, so a caller cannot
   * inject an arbitrary ORDER BY. On the MySQL full-text path the price
   * predicate joins the native query and an explicit price sort replaces
   * relevance ordering; the default (newest) keeps relevance ordering
   * there, as before.
   */
  @Transactional(readOnly = true)
  public Page<ItemDto> listItems(Pageable pageable, Long categoryId, String keyword,
                                 Long minPriceCents, Long maxPriceCents, String sort) {
    String canonicalSort = validatePriceRangeAndSort(minPriceCents, maxPriceCents, sort);
    if (isMySql() && keyword != null && !keyword.isBlank()) {
      return searchFulltext(pageable, categoryId, keyword.trim(),
          minPriceCents, maxPriceCents, canonicalSort);
    }
    Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
        sortFor(canonicalSort));
    Page<ItemDto> page = items.findListViewFiltered(categoryId, normalizeKeyword(keyword),
        minPriceCents, maxPriceCents, sorted);
    return new PageImpl<>(withPhotos(page.getContent()), sorted, page.getTotalElements());
  }

  /**
   * Validates the price range and resolves the sort allowlist, returning
   * the canonical sort name. Shared by both search paths so validation
   * behaves identically on H2 and MySQL.
   */
  static String validatePriceRangeAndSort(Long minPriceCents, Long maxPriceCents, String sort) {
    if (minPriceCents != null && minPriceCents < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "minPriceCents must be >= 0");
    }
    if (maxPriceCents != null && maxPriceCents < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "maxPriceCents must be >= 0");
    }
    if (minPriceCents != null && maxPriceCents != null && minPriceCents > maxPriceCents) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "minPriceCents must not exceed maxPriceCents");
    }
    if (sort == null || sort.isBlank() || sort.equals("newest")) {
      return "newest";
    }
    if (sort.equals("price-asc") || sort.equals("price-desc")) {
      return sort;
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown sort: " + sort);
  }

  /** Maps a canonical sort name to a fixed {@link Sort} (never user input). */
  static Sort sortFor(String canonicalSort) {
    return switch (canonicalSort) {
      case "price-asc" -> Sort.by(Sort.Order.asc("priceCents"), Sort.Order.asc("id"));
      case "price-desc" -> Sort.by(Sort.Order.desc("priceCents"), Sort.Order.desc("id"));
      default -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    };
  }

  /**
   * Merges the real photo galleries into list-view DTOs. The JPQL
   * constructor projections leave {@link ItemDto#photos()} empty (a
   * projection cannot build a nested collection), so this runs one batch
   * query for the whole page and splices the galleries in, ordered by
   * position. Empty pages skip the query entirely.
   */
  private List<ItemDto> withPhotos(List<ItemDto> dtos) {
    if (dtos.isEmpty()) {
      return dtos;
    }
    List<Long> ids = dtos.stream().map(ItemDto::id).toList();
    Map<Long, List<ItemPhotoDto>> byItem = new HashMap<>();
    for (ItemPhoto photo : itemPhotos.findByItemIdInOrderByPositionAsc(ids)) {
      byItem.computeIfAbsent(photo.getItem().getId(), k -> new ArrayList<>())
          .add(mapper.toPhotoDto(photo));
    }
    return dtos.stream()
        .map(d -> new ItemDto(
            d.id(), d.title(), d.description(), d.priceCents(), d.status(),
            d.sellerId(), d.sellerUsername(), d.categoryId(), d.categoryName(),
            d.photoUrl(), d.createdAt(), d.updatedAt(),
            byItem.getOrDefault(d.id(), List.of())))
        .toList();
  }

  /**
   * True when the app is connected to a real MySQL (docker-compose profile
   * or the Testcontainers CI path). Probed once from the JDBC database
   * product name and cached. Any probe failure — including a null/closed
   * data source — falls back to {@code false} (the LIKE path) rather than
   * failing the request.
   */
  boolean isMySql() {
    Boolean cached = mySqlDatabase;
    if (cached != null) {
      return cached;
    }
    boolean detected = false;
    try {
      try (Connection c = dataSource == null ? null : dataSource.getConnection()) {
        if (c != null) {
          detected = c.getMetaData().getDatabaseProductName()
              .toLowerCase(Locale.ROOT).contains("mysql");
        }
      }
    } catch (Exception e) {
      log.warn("Database product probe failed; falling back to LIKE-based listing search", e);
    }
    mySqlDatabase = detected;
    return detected;
  }

  /**
   * MySQL full-text search: fetch the relevance-ordered id page first, then
   * hydrate the rows through the list-view projection in one SELECT and
   * re-apply the relevance order in memory — SQL {@code IN} does not
   * preserve it. The unescaped keyword is passed to {@code MATCH}: unlike
   * LIKE, natural-language mode has no wildcard characters to escape.
   */
  private Page<ItemDto> searchFulltext(Pageable pageable, Long categoryId, String keyword,
                                       Long minPriceCents, Long maxPriceCents,
                                       String canonicalSort) {
    Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    Page<Long> ids = switch (canonicalSort) {
      case "price-asc" -> items.findIdsByFulltextPriceAsc(
          categoryId, keyword, minPriceCents, maxPriceCents, unsorted);
      case "price-desc" -> items.findIdsByFulltextPriceDesc(
          categoryId, keyword, minPriceCents, maxPriceCents, unsorted);
      default -> items.findIdsByFulltext(
          categoryId, keyword, minPriceCents, maxPriceCents, unsorted);
    };
    if (ids.isEmpty()) {
      return new PageImpl<>(List.of(), pageable, ids.getTotalElements());
    }
    // Hydrate through the list-view projection (not entity mapping) so the
    // seller username rides along — the entity has no user association the
    // mapper could read. One batch SELECT for the page, relevance order
    // re-applied in memory, galleries merged by withPhotos as on the LIKE
    // path.
    Map<Long, ItemDto> byId = items.findListViewsByIds(ids.getContent()).stream()
        .collect(Collectors.toMap(ItemDto::id, Function.identity()));
    List<ItemDto> dtos = ids.getContent().stream()
        .map(byId::get)
        .filter(Objects::nonNull)
        .toList();
    return new PageImpl<>(withPhotos(dtos), pageable, ids.getTotalElements());
  }

  /**
   * Normalizes a raw {@code ?q=} parameter: trims whitespace, treats blank
   * as "no filter", and escapes the LIKE wildcards ({@code %}, {@code _},
   * backslash) so the search string is matched literally.
   */
  static String normalizeKeyword(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /**
   * Detail view: the projection SELECT carries the category and the seller
   * username (ad-hoc join on the raw sellerId FK); the gallery is merged
   * with the same single batch query the list view uses — two bounded
   * SELECTs total, never a per-item lookup.
   */
  @Transactional(readOnly = true)
  public ItemDto getItem(Long id) {
    ItemDto dto = items.findDetailViewById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    return withPhotos(List.of(dto)).get(0);
  }

  /**
   * Creates a listing for the authenticated user. The seller id is the JWT
   * principal ({@link Long}), never a request parameter — a client cannot
   * post on someone else's behalf. An unknown category id is rejected 404
   * with the JSON envelope before anything is persisted.
   */
  @Transactional
  public ItemDto createItem(Long sellerId, ItemCreateRequest request) {
    Category category = null;
    if (request.categoryId() != null) {
      category = categories.findById(request.categoryId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "category not found"));
    }
    Item item = items.save(mapper.toEntity(request, sellerId, category));
    return mapper.toDto(item);
  }

  /**
   * Removes a listing permanently, and best-effort deletes its stored
   * photos — both the legacy {@code photoUrl} file and every gallery row's
   * file — so a deleted listing stops leaking files under
   * {@code /uploads/}. The row delete (which cascades to the gallery rows)
   * is forced (flush) before the filesystem is touched: a database-level
   * failure must surface first, so a failed row delete never leaves behind
   * a prematurely deleted file. Role checks live on the controller's
   * {@code @PreAuthorize}; the service stays role-agnostic.
   */
  @Transactional
  public void deleteItem(Long id) {
    Item item = items.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    List<String> photoUrls = new ArrayList<>();
    photoUrls.add(item.getPhotoUrl());
    photoUrls.addAll(itemPhotos.findByItemIdOrderByPositionAsc(id).stream()
        .map(ItemPhoto::getUrl)
        .toList());
    items.delete(item);
    items.flush();
    for (String url : photoUrls) {
      deletePhotoBestEffort(url);
    }
  }

  /**
   * Edits a listing's editable fields (title, description, price, category)
   * for the seller or an ADMIN. Ownership/role checks live on the
   * controller's {@code @PreAuthorize} ({@link ItemSecurity}); the service
   * stays role-agnostic.
   *
   * <p>All request fields are nullable: {@code null} means "leave the field
   * alone" (partial update — there is deliberately no way to clear a
   * category through this endpoint). A blank-but-present title is 400;
   * the size/positivity rules are enforced by the controller's
   * {@code @Valid}. An unknown {@code categoryId} is 404 before anything
   * is persisted.
   *
   * <p>Price integrity: the price may not move on a RESERVED listing (422)
   * — a live order snapshotted {@code amountCents} at creation (see the
   * order package), so editing the price under it would rewrite history.
   * The same holds for SOLD (the listing is final). Price edits on
   * AVAILABLE listings are allowed.
   *
   * <p>The write is optimistic-locked via {@code Item.@Version}, so a
   * concurrent edit fails fast instead of silently overwriting.
   */
  @Transactional
  public ItemDto updateItem(Long id, ItemUpdateRequest request) {
    Item item = items.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    Category category = null;
    if (request.categoryId() != null) {
      category = categories.findById(request.categoryId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "category not found"));
    }
    if (request.title() != null) {
      if (request.title().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
      }
      item.setTitle(request.title());
    }
    if (request.description() != null) {
      item.setDescription(request.description());
    }
    if (request.priceCents() != null && !request.priceCents().equals(item.getPriceCents())) {
      if (item.getStatus() != ItemStatus.AVAILABLE) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "price is locked while the listing has a buyer");
      }
      item.setPriceCents(request.priceCents());
    }
    if (category != null) {
      item.setCategory(category);
    }
    return mapper.toDto(item);
  }

  /**
   * Marks a listing SOLD. Only the AVAILABLE → SOLD and RESERVED → SOLD
   * transitions exist: the requested value must be {@link ItemStatus#SOLD}
   * (this endpoint marks listings sold, it is not a general status machine)
   * and a listing that is already SOLD has no onward transition. Both
   * violations are answered 422 with the JSON envelope. The flip is
   * optimistic-locked via {@code Item.@Version}, so a concurrent flip fails
   * fast instead of silently overwriting.
   *
   * <p>Ownership/role checks live on the controller's {@code @PreAuthorize}
   * ({@link ItemSecurity}); the service stays role-agnostic.
   */
  @Transactional
  public ItemDto markSold(Long id, ItemStatus requested) {
    Item item = items.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    if (requested != ItemStatus.SOLD) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "only the SOLD transition is supported on this endpoint");
    }
    if (item.getStatus() != ItemStatus.AVAILABLE && item.getStatus() != ItemStatus.RESERVED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "item is already SOLD");
    }
    item.setStatus(ItemStatus.SOLD);
    return mapper.toDto(item);
  }

  /**
   * Attaches a photo to a listing: validates and stores the upload, then
   * records its public URL path ({@code /uploads/<uuid>.<ext>}) on the item.
   * Replacing an existing photo best-effort deletes the old file, so
   * repeated photo uploads stop leaking one file per replace. Ownership/role
   * checks live on the controller's {@code @PreAuthorize}
   * ({@link ItemSecurity}); the service stays role-agnostic.
   *
   * <p>Ordering is deliberate: the new file is stored <i>first</i>, so a
   * validation failure throws before the old URL is touched — a rejected
   * upload never deletes the listing's current photo. The old file is only
   * removed after the URL has been replaced.
   *
   * <p>Note: the file is written to disk before the transaction commits, so
   * a rollback after a successful write can leave an orphan file — accepted
   * at this scale (a periodic cleanup keyed on DB rows would be the
   * production follow-up).
   */
  @Transactional
  public ItemDto attachPhoto(Long itemId, MultipartFile file) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    String newUrl = imageStorage.store(file);
    String oldUrl = item.getPhotoUrl();
    item.setPhotoUrl(newUrl);
    if (!Objects.equals(oldUrl, newUrl)) {
      deletePhotoBestEffort(oldUrl);
    }
    return mapper.toDto(item);
  }

  /**
   * Appends a photo to a listing's gallery: validates and stores the
   * upload, then records its public URL path ({@code /uploads/<uuid>.<ext>})
   * as a new gallery row at the next position. The first photo in the
   * gallery is the listing's primary photo. A listing holds at most
   * {@value #MAX_PHOTOS} photos — beyond that the request is answered 422
   * with the JSON envelope. Ownership/role checks live on the controller's
   * {@code @PreAuthorize} ({@link ItemSecurity}); the service stays
   * role-agnostic.
   *
   * <p>Ordering is deliberate: the new file is stored <i>first</i>, so a
   * validation failure throws before any row is touched — a rejected upload
   * never leaves a dangling gallery row.
   *
   * <p>Note: the file is written to disk before the transaction commits, so
   * a rollback after a successful write can leave an orphan file — accepted
   * at this scale (same trade-off as {@link #attachPhoto}).
   */
  @Transactional
  public ItemDto addPhoto(Long itemId, MultipartFile file) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    if (itemPhotos.countByItemId(itemId) >= MAX_PHOTOS) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "photo limit reached: a listing holds at most " + MAX_PHOTOS + " photos");
    }
    String url = imageStorage.store(file);
    int position = itemPhotos.findTopByItemIdOrderByPositionDesc(itemId)
        .map(p -> p.getPosition() + 1)
        .orElse(0);
    ItemPhoto photo = itemPhotos.save(new ItemPhoto(item, url, position));
    item.getPhotos().add(photo);
    return mapper.toDto(item);
  }

  /**
   * Removes one gallery photo: deletes the row and best-effort deletes its
   * file (reusing {@link #deletePhotoBestEffort}, so a disk hiccup can never
   * roll back the row delete). The lookup is scoped to the listing — a
   * photo id from another listing is 404, never a cross-listing delete.
   * The photo is detached from the item's in-memory collection first: the
   * {@code cascade = ALL} on {@link Item#getPhotos()} would otherwise
   * re-save the deleted row on flush. Ownership/role checks live on the
   * controller's {@code @PreAuthorize}; the service stays role-agnostic.
   */
  @Transactional
  public void deletePhoto(Long itemId, Long photoId) {
    ItemPhoto photo = itemPhotos.findByIdAndItemId(photoId, itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "photo not found"));
    String url = photo.getUrl();
    photo.getItem().getPhotos().removeIf(p -> photoId.equals(p.getId()));
    itemPhotos.delete(photo);
    itemPhotos.flush();
    deletePhotoBestEffort(url);
  }

  /**
   * Removes the file behind a stored photo URL without touching the
   * database transaction. A filesystem failure is logged and swallowed, so
   * it can never roll back (or block) the row operation it accompanies —
   * the row is the source of truth and the file is just its attachment.
   * A leftover file on a disk failure is the accepted cost; it is inert
   * (unreferenced) rather than a broken reference.
   */
  private void deletePhotoBestEffort(String publicPath) {
    if (publicPath == null || publicPath.isBlank()) {
      return;
    }
    try {
      if (imageStorage.delete(publicPath)) {
        log.debug("deleted orphaned upload {}", publicPath);
      }
    } catch (IOException e) {
      log.warn("could not delete upload {} — row operation proceeds, file left on disk",
          publicPath, e);
    }
  }
}
