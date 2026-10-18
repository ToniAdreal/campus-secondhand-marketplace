package com.toni.marketplace.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DataExportGrantRepository extends JpaRepository<DataExportGrant, Long> {

  Optional<DataExportGrant> findByTokenHash(String tokenHash);

  /** A fresh confirm retires every prior grant of the account (live or spent). */
  @Modifying
  @Transactional
  @Query("delete from DataExportGrant g where g.userId = :userId")
  void deleteByUserId(@Param("userId") Long userId);
}
