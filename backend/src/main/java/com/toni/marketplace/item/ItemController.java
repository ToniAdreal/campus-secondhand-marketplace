package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import com.toni.marketplace.common.EtagSupport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "items", description = "Listings")
@RequestMapping("/api/items")
public class ItemController {

  private final ItemService itemService;

  public ItemController(ItemService itemService) {
    this.itemService = itemService;
  }

  /**
   * Browse listings. Filters compose (category + keyword + inclusive
   * price range in cents); {@code sort} is an allowlist — newest
   * (default), price-asc, price-desc — anything else is 400, as are
   * negative price bounds (@PositiveOrZero) and min &gt; max (checked in
   * the service), all in the {code,message,data} envelope.
   *
   * <p>{@code mine=true} (backlog #146) narrows the list to the
   * caller's own listings — the "My listings" seller view, including
   * RESERVED/SOLD rows. The seller id is resolved server-side from the
   * JWT principal; there is deliberately no {@code sellerId} request
   * parameter. Trade-off, honestly: a client-supplied sellerId would
   * only expose what the public projection already shows (seller ids
   * and statuses are public list fields), but it would invite bulk
   * scraping of one seller's full inventory in a single query, so the
   * shortcut stays self-only. {@code mine=true} without a token is
   * 401 — the security chain already requires authentication for this
   * route, and the explicit principal check below keeps the contract
   * if that ever loosens.
   */
  @GetMapping
  public ApiResponse<Page<ItemDto>> list(
      @RequestParam(required = false) Long categoryId,
      @RequestParam(name = "q", required = false) String keyword,
      @RequestParam(required = false) @PositiveOrZero Long minPriceCents,
      @RequestParam(required = false) @PositiveOrZero Long maxPriceCents,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") boolean mine,
      @PageableDefault(size = 20) Pageable pageable) {
    Long sellerId = null;
    if (mine) {
      Object principal = SecurityContextHolder.getContext().getAuthentication() == null
          ? null
          : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
      if (!(principal instanceof Long callerId)) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthorized");
      }
      sellerId = callerId;
    }
    return ApiResponse.ok(itemService.listItems(
        pageable, categoryId, keyword, minPriceCents, maxPriceCents, sort, sellerId));
  }

  /**
   * Listing detail with a strong ETag / conditional GET (backlog #166,
   * extending #145's category pattern). The validator is the SHA-256 of
   * (id, version, updatedAt, full DTO): every write path — edit, status
   * flip, photo add/delete/reorder, order-driven RESERVED/SOLD flips —
   * bumps the entity's {@code @Version} (and changes the DTO), so a
   * stale validator can never 304 a changed listing; the DTO component
   * additionally covers gallery changes on paths that touch only the
   * photo rows. A matching {@code If-None-Match} (weak and {@code *}
   * forms included, via the shared {@link EtagSupport} parser) answers
   * 304 with an empty body and the ETag echoed. The LIST endpoint
   * deliberately stays validator-free (per-user filtered, rapidly
   * changing — the #145 scope decision).
   */
  @GetMapping("/{id}")
  public org.springframework.http.ResponseEntity<ApiResponse<ItemDto>> get(
      @PathVariable Long id,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = org.springframework.http.HttpHeaders.IF_NONE_MATCH, required = false)
          String ifNoneMatch) {
    ItemService.ItemDetailView view = itemService.getItemView(id);
    String etag = EtagSupport.sha256Hex(
        view.dto().id() + ":" + view.version() + ":" + view.updatedAt() + ":" + view.dto());
    if (EtagSupport.matchesIfNoneMatch(ifNoneMatch, etag)) {
      return org.springframework.http.ResponseEntity
          .status(org.springframework.http.HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return org.springframework.http.ResponseEntity.ok().eTag(etag)
        .body(ApiResponse.ok(view.dto()));
  }

  /**
   * Creates a listing for the authenticated user. Constraint violations
   * (blank title, non-positive price, …) are answered 400 with the JSON
   * envelope by {@code GlobalExceptionHandler#handleValidation}; no token
   * at all is answered 401 by the security entry point.
   */
  @PostMapping
  public ApiResponse<ItemDto> create(@Valid @RequestBody ItemCreateRequest request) {
    Long sellerId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(itemService.createItem(sellerId, request));
  }

  /**
   * Attaches a photo to a listing. The listing's seller — or an ADMIN — may
   * upload; everyone else gets 403 and anonymous callers get 401. The file
   * is validated (image type, size) and stored under a UUID name by
   * {@link ImageStorageService}; the response carries the public
   * {@code /uploads/<uuid>.<ext>} URL in {@code photoUrl}.
   */
  @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@itemSecurity.canAttachPhoto(authentication, #id)")
  public ApiResponse<ItemDto> uploadPhoto(@PathVariable Long id,
                                         @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(itemService.attachPhoto(id, file));
  }

  /**
   * Appends a photo to a listing's gallery. The listing's seller — or an
   * ADMIN — may upload; everyone else gets 403 and anonymous callers get
   * 401. The file is validated (image type, size) and stored under a UUID
   * name by {@link ImageStorageService}; the gallery row carries the public
   * {@code /uploads/<uuid>.<ext>} URL, and the response's {@code photos}
   * list shows the full gallery with the first (lowest position) photo as
   * primary. A listing holds at most 6 photos — beyond that, 422 with the
   * JSON envelope.
   */
  @PostMapping(value = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@itemSecurity.canAttachPhoto(authentication, #id)")
  public ApiResponse<ItemDto> addPhoto(@PathVariable Long id,
                                      @RequestParam("file") MultipartFile file) {
    return ApiResponse.ok(itemService.addPhoto(id, file));
  }

  /**
   * Removes one gallery photo from a listing. Same ownership as the upload:
   * the listing's seller or an ADMIN; everyone else gets 403 and anonymous
   * callers get 401. A photo id that does not belong to this listing is
   * 404 — never a cross-listing delete.
   */
  @DeleteMapping("/{id}/photos/{photoId}")
  @PreAuthorize("@itemSecurity.canAttachPhoto(authentication, #id)")
  public ApiResponse<Void> deletePhoto(@PathVariable Long id,
                                       @PathVariable Long photoId) {
    itemService.deletePhoto(id, photoId);
    return ApiResponse.ok(null);
  }

  /**
   * Reorders a listing's gallery (backlog #162). The body is the
   * complete ordered photo-id list for the listing and must be an
   * exact permutation of the current gallery — a missing, extra or
   * foreign id is 400 with the JSON envelope and nothing moves; the
   * first id becomes the primary photo. Same ownership as the other
   * gallery endpoints: the listing's seller or an ADMIN; everyone
   * else gets 403 and anonymous callers get 401. A concurrent gallery
   * edit landing mid-reorder loses its {@code @Version} race to 409.
   */
  @PutMapping("/{id}/photos/order")
  @PreAuthorize("@itemSecurity.canAttachPhoto(authentication, #id)")
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "photoIds is not an exact permutation of the gallery (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the listing's seller or an ADMIN (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown listing id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
          description = "version conflict with a concurrent gallery edit (envelope)")})
  public ApiResponse<ItemDto> reorderPhotos(@PathVariable Long id,
                                            @Valid @RequestBody ItemPhotoOrderRequest request) {
    return ApiResponse.ok(itemService.reorderPhotos(id, request.photoIds()));
  }

  /**
   * Edits a listing's editable fields (title, description, price,
   * category). The listing's seller — or an ADMIN — may do so; everyone
   * else gets 403 and anonymous callers get 401. All fields are optional:
   * omitted fields are left untouched (partial update). Status is
   * deliberately untouched — the status machine still lives on
   * {@code PATCH /api/items/{id}/status}; price changes on a RESERVED or
   * SOLD listing are answered 422 (the live order snapshotted the price).
   */
  @PatchMapping("/{id}")
  @PreAuthorize("@itemSecurity.canEdit(authentication, #id)")
  public ApiResponse<ItemDto> update(@PathVariable Long id,
                                     @Valid @RequestBody ItemUpdateRequest request) {
    return ApiResponse.ok(itemService.updateItem(id, request));
  }

  /**
   * Marks a listing SOLD. The listing's seller — or an ADMIN — may do so;
   * everyone else gets 403 and anonymous callers get 401. Only the
   * AVAILABLE/RESERVED → SOLD transitions exist: any other requested status
   * and any already-SOLD listing are answered 422 with the JSON envelope.
   */
  @PatchMapping("/{id}/status")
  @PreAuthorize("@itemSecurity.canMarkSold(authentication, #id)")
  public ApiResponse<ItemDto> markSold(@PathVariable Long id,
                                      @Valid @RequestBody ItemStatusUpdateRequest request) {
    return ApiResponse.ok(itemService.markSold(id, request.status()));
  }

  /**
   * Removes a listing. Admins only — a regular user's listing removal is a
   * separate product decision (owner-side delete) that this endpoint does
   * not implement.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    itemService.deleteItem(id);
    return ApiResponse.ok(null);
  }
}
