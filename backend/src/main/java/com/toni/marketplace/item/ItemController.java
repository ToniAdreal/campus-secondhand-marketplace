package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "items", description = "Listings")
@RequestMapping("/api/items")
public class ItemController {

  private final ItemService itemService;

  public ItemController(ItemService itemService) {
    this.itemService = itemService;
  }

  @GetMapping
  public ApiResponse<Page<ItemDto>> list(
      @RequestParam(required = false) Long categoryId,
      @RequestParam(name = "q", required = false) String keyword,
      @PageableDefault(size = 20) Pageable pageable) {
    return ApiResponse.ok(itemService.listItems(pageable, categoryId, keyword));
  }

  @GetMapping("/{id}")
  public ApiResponse<ItemDto> get(@PathVariable Long id) {
    return ApiResponse.ok(itemService.getItem(id));
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
