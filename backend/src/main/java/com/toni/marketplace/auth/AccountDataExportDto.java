package com.toni.marketplace.auth;

import com.toni.marketplace.item.ItemDto;
import com.toni.marketplace.item.SavedSearchDto;
import com.toni.marketplace.message.MessageDto;
import com.toni.marketplace.order.OrderDto;
import java.time.Instant;
import java.util.List;

/**
 * Self-service account data export (backlog #172): the caller's own data
 * as one JSON document, served as a download by
 * {@code GET /api/users/me/export}.
 *
 * <p>Every section reuses the DTO projection its own API already shows —
 * {@link ItemDto} for listings and favorites, {@link OrderDto} for orders,
 * {@link MessageDto} for messages, {@link SavedSearchDto} for saved
 * searches — and the profile is built field-by-field below, so the export
 * cannot leak a field the API itself never shows. In particular the
 * profile carries ONLY id, username, email, roles and createdAt: the
 * password hash, the TOTP secret and every token artifact are excluded
 * by construction, and {@code AccountDataExportTest} pins that with a
 * field-denylist assertion over the serialized payload.
 *
 * <p>Honest scope: this is a personal-data copy for the account holder,
 * not a backup/restore format and not an account-migration tool —
 * nothing in the app can import it back.
 */
public record AccountDataExportDto(
    Instant exportedAt,
    Profile profile,
    List<ItemDto> listings,
    List<OrderDto> ordersAsBuyer,
    List<OrderDto> ordersAsSeller,
    List<MessageDto> messages,
    List<ItemDto> favorites,
    List<SavedSearchDto> savedSearches) {

  /**
   * The export's profile section: exactly the fields named in backlog
   * #172, built field-by-field from the entity — never by serializing
   * {@link User}, whose credential columns must stay off the wire.
   */
  public record Profile(
      Long id,
      String username,
      String email,
      List<String> roles,
      Instant createdAt) {

    public static Profile of(User user) {
      List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();
      return new Profile(user.getId(), user.getUsername(), user.getEmail(), roles,
          user.getCreatedAt());
    }
  }
}
