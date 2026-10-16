package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * Public seller profile (backlog #174): the seller's username, when
 * they joined, how many listings they currently have live (AVAILABLE),
 * and how many sales they have completed (COMPLETED orders on their
 * listings) — plus, since backlog #175, the review aggregate folded in
 * from the {@code review} package: how many post-completion reviews
 * the seller has received and their average rating (computed from the
 * review rows on every read, never stored; null when the seller has
 * no reviews yet). Built field-by-field in {@link SellerProfileService}
 * from the {@link User} row and the counts, so the email address and
 * every other User field (password hash, TOTP state, lockout
 * counters, …) are excluded by construction, never by filtering a
 * serialized entity. A test pins the exact JSON key set.
 */
public record PublicSellerDto(
    String username,
    Instant memberSince,
    long availableListingCount,
    long completedSalesCount,
    long reviewCount,
    Double averageRating) {}
