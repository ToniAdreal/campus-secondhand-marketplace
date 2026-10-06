package com.toni.marketplace.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  java.util.Optional<User> findByUsername(String username);

  java.util.Optional<User> findByEmail(String email);
}
