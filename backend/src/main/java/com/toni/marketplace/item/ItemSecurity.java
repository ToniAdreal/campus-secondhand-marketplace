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
 * <p>A photo may be attached to a listing by its seller or by an ADMIN.
 * A missing listing throws 404 rather than returning false, so the caller
 * still gets "item not found" instead of a misleading "forbidden".
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
}
