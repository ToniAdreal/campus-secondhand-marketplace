package com.toni.marketplace.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {

  Optional<TrustedDevice> findByTokenHash(String tokenHash);

  /** The device whose CURRENT token rotated away from this hash — a replay suspect. */
  Optional<TrustedDevice> findByPreviousTokenHash(String previousTokenHash);

  /** The account's trust rows, oldest first (the sessions surface re-sorts for display). */
  List<TrustedDevice> findByUserIdOrderByCreatedAtAsc(Long userId);

  Optional<TrustedDevice> findByIdAndUserId(Long id, Long userId);

  /** Password change / TOTP disable / account closure retire every trust row at once. */
  @Modifying
  @Transactional
  @Query("delete from TrustedDevice d where d.userId = :userId")
  void deleteByUserId(@Param("userId") Long userId);
}
