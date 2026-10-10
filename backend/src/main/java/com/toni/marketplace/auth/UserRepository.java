package com.toni.marketplace.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * User lookups are always case-insensitive ({@code IgnoreCase} derived
 * queries). Usernames and emails are stored canonical-lowercase by
 * {@link AuthService}, but rows written before that normalization — and the
 * differing default collations of H2 vs MySQL — make exact-match queries
 * behave differently per database. The case-insensitive contract holds on
 * every database.
 */
public interface UserRepository extends JpaRepository<User, Long> {

  java.util.Optional<User> findByUsernameIgnoreCase(String username);

  java.util.Optional<User> findByEmailIgnoreCase(String email);

  /**
   * ADMIN user list/search (backlog #105): substring match over the
   * canonical-lowercase username/email. The caller passes an already
   * lowercased, LIKE-escaped term (see AuthService#listAdminUsers);
   * {@code lower(...)} on both sides keeps the match case-insensitive
   * even for rows written before the #47 normalization, on every
   * database collation.
   */
  @Query("select u from User u where lower(u.username) like concat('%', :q, '%') escape '\\'"
      + " or lower(u.email) like concat('%', :q, '%') escape '\\'")
  Page<User> searchAdminUsers(@Param("q") String q, Pageable pageable);
}
