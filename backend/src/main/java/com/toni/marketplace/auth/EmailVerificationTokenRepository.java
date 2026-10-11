package com.toni.marketplace.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, Long> {

  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  /**
   * Invalidates every still-unused token of the user — a resend supersedes
   * all earlier ones (backlog #138), and a successful confirm retires any
   * sibling that could somehow still be live.
   */
  void deleteByUserIdAndUsedAtIsNull(Long userId);
}
