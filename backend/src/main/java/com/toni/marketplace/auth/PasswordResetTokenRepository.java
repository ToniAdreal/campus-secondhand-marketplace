package com.toni.marketplace.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  /**
   * Invalidates every still-unused token of the user — a fresh reset
   * request supersedes all earlier ones (backlog #90), and a successful
   * confirm retires any sibling that could somehow still be live.
   */
  void deleteByUserIdAndUsedAtIsNull(Long userId);
}
