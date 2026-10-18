package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * One row of the caller's blocked-users list (backlog #187): who was
 * blocked (id + public username only — never an email or any other
 * account field) and when the block was created.
 */
public record BlockedUserDto(Long blockedId, String blockedUsername, Instant createdAt) {}
