package com.toni.marketplace.audit;

/**
 * CSV rendering for the ADMIN audit-log export (backlog #232) — no new
 * dependency, the format is small enough to pin by tests.
 *
 * <p>Columns mirror {@link AuditLogDto} exactly: the row id, the actor,
 * the action and target as their enum names (the same allowlisted values
 * the list endpoint's filters accept), and the creation instant in
 * ISO-8601. Records are CRLF-separated, header included.
 *
 * <p>No quoting or formula neutralization is applied, deliberately: every
 * cell is server-generated — numeric ids, enum constant names, an
 * ISO-8601 instant — so no cell can contain a comma, a double quote, a
 * CR/LF, or a leading {@code =}/{@code +}/{@code -}/{@code @}. If a
 * free-text column ever joins this export, this class must gain the
 * {@code SellerSalesCsv} treatment (RFC 4180 quoting + formula
 * neutralization) before it ships — the assumption is stated here so a
 * future column addition cannot silently inherit the exemption.
 */
final class AuditLogCsv {

  static final String HEADER =
      "id,actor_user_id,action,target_type,target_id,created_at";

  private AuditLogCsv() {}

  /** One export record from its {@link AuditLogDto} projection — never the entity. */
  static String row(AuditLogDto dto) {
    return dto.id() + "," + dto.actorUserId() + "," + dto.action().name() + ","
        + dto.targetType().name() + "," + dto.targetId() + "," + dto.createdAt();
  }
}
