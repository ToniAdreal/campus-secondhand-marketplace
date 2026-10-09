package com.toni.marketplace.auth;

/**
 * How many of the caller's TOTP recovery codes are still unused
 * (backlog #91). A count only — the codes themselves are never readable
 * back after the enable response that issued them.
 */
public record TotpRecoveryCodeCountResponse(long remaining) {}
