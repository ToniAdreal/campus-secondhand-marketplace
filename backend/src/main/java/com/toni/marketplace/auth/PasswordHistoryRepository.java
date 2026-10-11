package com.toni.marketplace.auth;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {

  /**
   * The user's stored outgoing hashes, newest first. Ordered by id (not
   * {@code created_at}) so the window is deterministic even when several
   * changes land inside the same timestamp tick. The caller passes a
   * one-page {@link Pageable} sized to
   * {@code app.auth.password-history-size} (backlog #139).
   */
  List<PasswordHistory> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

  long countByUserId(Long userId);
}
