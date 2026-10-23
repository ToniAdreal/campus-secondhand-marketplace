package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * One row of the caller's following list (backlog #217): who is followed
 * (id + public username only — never an email or any other account
 * field, mirroring {@link BlockedUserDto}) and when the follow was
 * created. No follower counts are carried or exposed: the follow is a
 * notification tool, not a social graph.
 */
public record FollowedSellerDto(Long sellerId, String sellerUsername, Instant createdAt) {}
