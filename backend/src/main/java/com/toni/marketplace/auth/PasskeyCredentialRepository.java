package com.toni.marketplace.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link PasskeyCredential} (backlog #220). The list and
 * delete reads are user-scoped; the credential-id read is global because
 * the id is unique across the table (a presented id that belongs to
 * another user simply fails that user's lookup — no cross-user oracle,
 * mirroring {@code DELETE /api/auth/sessions/{id}}).
 */
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, Long> {

  List<PasskeyCredential> findByUserIdOrderByCreatedAtDesc(long userId);

  Optional<PasskeyCredential> findByUserIdAndCredentialId(long userId, String credentialId);

  Optional<PasskeyCredential> findByCredentialId(String credentialId);

  boolean existsByUserId(long userId);

  long countByUserId(long userId);

  void deleteByUserId(long userId);
}
