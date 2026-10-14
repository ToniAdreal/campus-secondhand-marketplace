package com.toni.marketplace.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, Long> {

  /** The caller's own saved searches, newest save first (id tiebreak). */
  List<SavedSearch> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

  long countByUserId(Long userId);

  /**
   * Scoped by user on purpose: another user's row reads as absent, so the
   * service answers 404 without leaking that the id exists (#164, the
   * #157/#143 no-existence-leak rule).
   */
  Optional<SavedSearch> findByIdAndUserId(Long id, Long userId);
}
