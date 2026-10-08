package com.toni.marketplace.item;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ownership policy for listing write operations, referenced from
 * {@code @PreAuthorize} on the controller (role checks live on the
 * controller layer, not in the service).
 *
 * <p>A photo may be attached to (or removed from) a listing's gallery by
 * its seller or by an ADMIN. A missing listing throws 404 rather than
 * returning false, so the caller still gets "item not found" instead of a
 * misleading "forbidden". The same ownership rule guards the gallery
 * endpoints (POST /api/items/{id}/photos, DELETE
 * /api/items/{id}/photos/{photoId}); the per-photo scoping (a photo id only
 * deletes under its own listing) lives in the service.
 */
@Component("itemSecurity")
public class ItemSecurity {

  private final ItemRepository items;

  public ItemSecurity(ItemRepository items) {
    this.items = items;
  }

  public boolean canAttachPhoto(Authentication auth, Long itemId) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    boolean admin = auth.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return admin || item.getSellerId().equals(auth.getPrincipal());
  }

  /**
   * A listing may be edited by its seller or by an ADMIN. Same ownership
   * semantics as {@link #canAttachPhoto}: a missing listing is 404, not a
   * misleading 403. Guards {@code PATCH /api/items/{id}} (title / description
   * / price / category edits; status still has its own endpoint).
   */
  public boolean canEdit(Authentication auth, Long itemId) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    boolean admin = auth.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return admin || item.getSellerId().equals(auth.getPrincipal());
  }

  /**
   * A listing may be marked SOLD by its seller or by an ADMIN. Same
   * ownership semantics as {@link #canAttachPhoto}: a missing listing is
   * 404, not a misleading 403.
   */
  public boolean canMarkSold(Authentication auth, Long itemId) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    boolean admin = auth.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return admin || item.getSellerId().equals(auth.getPrincipal());
  }
}
