package com.toni.marketplace.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailChangeTokenRepository
    extends JpaRepository<EmailChangeToken, Long> {

  Optional<EmailChangeToken> findByTokenHash(String tokenHash);

  /**
   * Invalidates every still-unused token of the user — a fresh change
   * request supersedes all earlier ones (backlog #208), and a
   * successful confirm retires any sibling that could somehow still
   * be live.
   */
  void deleteByUserIdAndUsedAtIsNull(Long userId);
}
