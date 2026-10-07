package com.toni.marketplace.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /** Follows the rotation chain: the row issued in place of a revoked token. */
  Optional<RefreshToken> findByJti(String jti);

  /** Hard-delete the whole family (logout / refresh-token theft detected). */
  void deleteByUserId(Long userId);

  /**
   * Ids of rows that are authentication-dead (revoked, or already expired as
   * of {@code now}) and were created before {@code createdBefore} — the
   * retention cut-off computed by the purge job. Ordered by id so the batch
   * loop can always re-read the first page without skipping rows.
   */
  @Query("select r.id from RefreshToken r "
      + "where (r.revoked = true or r.expiresAt <= :now) and r.createdAt < :createdBefore "
      + "order by r.id")
  List<Long> findStaleIds(Instant now, Instant createdBefore, Pageable page);
}
