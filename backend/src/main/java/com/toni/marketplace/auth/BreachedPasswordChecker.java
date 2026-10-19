package com.toni.marketplace.auth;

import java.util.Set;

/**
 * Breach-corpus seam for the shared password policy (backlog #197), in
 * the HaveIBeenPwned k-anonymity shape: the caller hashes the candidate
 * password with SHA-1 and hands this interface ONLY the first five hex
 * characters of that hash (the prefix). The implementation answers with
 * every known-breached hash SUFFIX (the remaining 35 hex characters)
 * under that prefix, and the caller compares its own suffix locally —
 * so neither the password nor its full hash ever leaves the validator,
 * and an implementation never sees more than the 5-character prefix.
 *
 * <p>Failure semantics are part of the contract, via {@link #failClosed()}:
 * the default {@link LocalDenylistBreachedPasswordChecker} is an
 * in-memory set that cannot be "down", so it is fail-CLOSED (a failure
 * rejects the candidate); a remote corpus checker is fail-OPEN (an
 * outage must not lock every registration and password change in the
 * deployment — the validator logs the outage and lets the candidate
 * through on the strength rules alone).
 */
public interface BreachedPasswordChecker {

  /**
   * Returns the breached hash suffixes known under {@code prefix}
   * (exactly five uppercase hex characters). An empty set means no
   * known breach under that prefix. Implementations signal a lookup
   * failure by throwing a {@link RuntimeException}; the caller applies
   * this checker's {@link #failClosed()} policy to it.
   */
  Set<String> suffixesForPrefix(String prefix);

  /** True when a lookup failure must reject the candidate password. */
  boolean failClosed();
}
