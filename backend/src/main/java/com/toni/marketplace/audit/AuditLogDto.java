package com.toni.marketplace.audit;

import java.time.Instant;

/**
 * One row of the ADMIN audit-log read view (backlog #109). Built from the
 * entity deliberately instead of serializing {@link AuditLog} directly —
 * the same projection rule {@code AdminUserDto} follows (#105) — so the
 * wire shape stays stable even if the entity grows internal columns.
 * Enum fields serialize as their constant names (the allowlisted values
 * the read endpoint's filters accept).
 */
public record AuditLogDto(long id, long actorUserId, AuditAction action,
                          AuditTargetType targetType, long targetId, Instant createdAt) {

  public static AuditLogDto of(AuditLog row) {
    return new AuditLogDto(row.getId(), row.getActorUserId(), row.getAction(),
        row.getTargetType(), row.getTargetId(), row.getCreatedAt());
  }
}
