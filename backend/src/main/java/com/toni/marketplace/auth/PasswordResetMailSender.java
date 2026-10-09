package com.toni.marketplace.auth;

/**
 * The mail seam for password-reset delivery (backlog #90): the reset flow
 * hands the raw token to exactly this interface and nowhere else — the
 * database stores only its SHA-256 hash, and API responses never contain
 * it (the request endpoint answers the identical 200 envelope whether or
 * not the account exists).
 *
 * <p>Honest scope: this project has no mail server. The default bean
 * ({@link LoggingPasswordResetMailSender}) logs the request at INFO with
 * the token redacted and delivers nothing; a real deployment injects an
 * SMTP/SES implementation of this interface that emails the user a link
 * carrying the token.
 */
public interface PasswordResetMailSender {

  /** Delivers {@code rawToken} to {@code user} through the mail channel. */
  void sendPasswordReset(User user, String rawToken);
}
