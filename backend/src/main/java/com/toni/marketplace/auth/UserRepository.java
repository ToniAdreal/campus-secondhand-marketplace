package com.toni.marketplace.auth;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
