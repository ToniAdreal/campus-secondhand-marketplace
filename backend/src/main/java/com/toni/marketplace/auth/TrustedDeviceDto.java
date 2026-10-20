package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * One trusted device for {@code GET /api/auth/trusted-devices}
 * (backlog #199) — the sessions surface's trust list. The token and
 * its hashes are never exposed: the row is identified by its database
 * id, which is also the path id for
 * {@code DELETE /api/auth/trusted-devices/{id}}.
 *
 * @param id         the trust row id; the path id for the revoke call
 * @param userAgent  device label captured at trust time / last trusted use;
 *                   NULL for clients that sent no User-Agent
 * @param ipAddress  remote address captured at trust time / last trusted use
 * @param createdAt  when the device was trusted (the TOTP completion)
 * @param lastUsedAt when a login last skipped the challenge via this trust;
 *                   NULL until the first trusted login
 * @param expiresAt  when the trust lapses without another trusted use
 *                   (sliding — every trusted login extends it)
 */
public record TrustedDeviceDto(Long id, String userAgent, String ipAddress,
                               Instant createdAt, Instant lastUsedAt, Instant expiresAt) {

  static TrustedDeviceDto of(TrustedDevice row) {
    return new TrustedDeviceDto(row.getId(), row.getUserAgent(), row.getIpAddress(),
        row.getCreatedAt(), row.getLastUsedAt(), row.getExpiresAt());
  }
}
