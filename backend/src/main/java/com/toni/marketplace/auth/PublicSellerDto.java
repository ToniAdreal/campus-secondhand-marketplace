package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * Public seller profile (backlog #174): exactly four fields — the
 * seller's username, when they joined, how many listings they currently
 * have live (AVAILABLE), and how many sales they have completed
 * (COMPLETED orders on their listings). Built field-by-field in
 * {@link SellerProfileService} from the {@link User} row and two counts,
 * so the email address and every other User field (password hash, TOTP
 * state, lockout counters, …) are excluded by construction, never by
 * filtering a serialized entity. A test pins the exact JSON key set.
 */
public record PublicSellerDto(
    String username,
    Instant memberSince,
    long availableListingCount,
    long completedSalesCount) {}
