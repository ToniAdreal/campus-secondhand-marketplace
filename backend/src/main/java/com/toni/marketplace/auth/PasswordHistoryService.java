package com.toni.marketplace.auth;

import com.toni.marketplace.common.PasswordReuseException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Password-history enforcement (backlog #139) — the ONE reuse check both
 * credential-setting paths share, so they cannot drift (the #127 seam
 * lesson): {@code AuthService.changePassword} (authenticated change) and
 * {@code PasswordResetService.confirmReset} (mailbox recovery) both call
 * {@link #requireNotReused} before replacing the hash and {@link
 * #recordOutgoing} as part of the replacement.
 *
 * <p>Before #139 the only guard was changePassword comparing the new
 * password against the CURRENT hash — nothing stopped A→B→A cycling,
 * and the reset path had no reuse check at all (a reset could even set
 * the password straight back to the one already in force). Now a new
 * password is rejected with {@link PasswordReuseException} (400
 * envelope) when it matches:
 *
 * <ul>
 *   <li>the current hash — message "must differ from the current
 *       password", the pre-#139 wording, unchanged; or</li>
 *   <li>any of the last {@code app.auth.password-history-size}
 *       (default 5) outgoing hashes in the V27 {@code password_history}
 *       table — message "must differ from a recent password". Once a
 *       password falls out of that window (it is the sixth-oldest or
 *       older) it may be used again; the window slides, it is not a
 *       lifetime ban.</li>
 * </ul>
 *
 * <p>No transaction annotation here: both callers are already
 * transactional, and the history write must join THEIR transaction —
 * a change or reset that rolls back leaves no history row, and a
 * committed one leaves exactly one (the outgoing hash).
 */
@Service
public class PasswordHistoryService {

  private final PasswordHistoryRepository history;
  private final PasswordService passwords;
  private final PasswordPolicyProperties policy;

  public PasswordHistoryService(PasswordHistoryRepository history, PasswordService passwords,
                                PasswordPolicyProperties policy) {
    this.history = history;
    this.passwords = passwords;
    this.policy = policy;
  }

  /** Policy accessor with a defaults fallback, mirroring AuthService's. */
  private PasswordPolicyProperties policy() {
    return policy != null ? policy : new PasswordPolicyProperties();
  }

  /**
   * Rejects {@code rawNewPassword} when it reuses the user's current
   * password or one of their last {@code password-history-size}
   * outgoing passwords. Comparison is always via
   * {@code PasswordEncoder.matches} against stored BCrypt hashes — the
   * raw candidate is never stored or logged.
   */
  public void requireNotReused(User user, String rawNewPassword) {
    if (passwords.matches(rawNewPassword, user.getPasswordHash())) {
      throw new PasswordReuseException(
          "new password must differ from the current password");
    }
    int size = policy().getPasswordHistorySize();
    if (size <= 0) {
      return;
    }
    for (PasswordHistory row : history.findByUserIdOrderByIdDesc(
        user.getId(), PageRequest.of(0, size))) {
      if (passwords.matches(rawNewPassword, row.getPasswordHash())) {
        throw new PasswordReuseException(
            "new password must differ from a recent password");
      }
    }
  }

  /**
   * Appends the user's CURRENT (about-to-be-replaced) hash to the
   * history. Callers invoke this before they overwrite the hash, inside
   * the transaction that commits the replacement.
   */
  public void recordOutgoing(User user) {
    history.save(new PasswordHistory(user.getId(), user.getPasswordHash()));
  }
}
