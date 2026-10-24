package com.toni.marketplace.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link PasskeyChallenge} (backlog #220). Reads are
 * keyed on the challenge hash (the verify path) or scoped to one user's
 * one purpose (the options path's replace-prior-unused discipline and
 * account-lifecycle cleanup).
 */
public interface PasskeyChallengeRepository extends JpaRepository<PasskeyChallenge, Long> {

  Optional<PasskeyChallenge> findByChallengeHash(String challengeHash);

  List<PasskeyChallenge> findByUserIdAndPurposeAndUsedAtIsNull(long userId,
      PasskeyChallengePurpose purpose);

  void deleteByUserIdAndPurposeAndUsedAtIsNull(long userId, PasskeyChallengePurpose purpose);

  void deleteByUserId(long userId);
}
