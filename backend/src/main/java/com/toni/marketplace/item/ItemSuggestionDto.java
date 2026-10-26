package com.toni.marketplace.item;

/**
 * One search-suggestion row (backlog #235): exactly the listing id and
 * its title — nothing else. The dropdown only ever displays the title
 * and selecting one navigates to the existing {@code ?q=} results, so
 * the suggestion payload carries no description, price or seller data
 * (the full {@link ItemDto} would leak the browse projection's fields
 * into a typeahead response for no benefit). The title match itself is
 * prefix-only: see {@link ItemRepository#findTitleSuggestions}.
 */
public record ItemSuggestionDto(Long id, String title) {
}
