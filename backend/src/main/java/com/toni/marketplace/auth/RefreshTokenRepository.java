package com.toni.marketplace.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /** Hard-delete the whole family (logout / refresh-token theft detected). */
  void deleteByUserId(Long userId);
}
