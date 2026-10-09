package com.toni.marketplace.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TotpRecoveryCodeRepository extends JpaRepository<TotpRecoveryCode, Long> {

  /** Collision pre-check for issuance: a hash must be globally unique. */
  boolean existsByCodeHash(String codeHash);

  /** The live (unused) row for one presented code of one user, if any. */
  Optional<TotpRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(Long userId, String codeHash);

  /** How many of the user's codes are still unused — the count endpoint's whole answer. */
  long countByUserIdAndUsedAtIsNull(Long userId);

  /**
   * Invalidates the user's whole set: re-enrollment (setup + enable)
   * replaces it with a fresh one, and setup alone retires the old set
   * because the old secret — the enrollment the codes belonged to — is
   * already gone at that point.
   */
  void deleteByUserId(Long userId);
}
