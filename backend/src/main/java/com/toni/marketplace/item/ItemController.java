package com.toni.marketplace.item;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/items")
public class ItemController {

  private final ItemService itemService;

  public ItemController(ItemService itemService) {
    this.itemService = itemService;
  }

  @GetMapping
  public ApiResponse<Page<ItemDto>> list(@PageableDefault(size = 20) Pageable pageable) {
    return ApiResponse.ok(itemService.listItems(pageable));
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
