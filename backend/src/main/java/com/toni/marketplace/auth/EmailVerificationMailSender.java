package com.toni.marketplace.auth;

/**
 * The mail seam for email-verification delivery (backlog #138): the
 * verification flow hands the raw token to exactly this interface and
 * nowhere else — the database stores only its SHA-256 hash, and API
 * responses never contain it (registration answers a session-less success
 * envelope, and the resend endpoint answers the identical 200 envelope
 * whether or not the address belongs to an unverified account).
 *
 * <p>Honest scope: this project has no mail server. The default bean
 * ({@link LoggingEmailVerificationMailSender}) logs the request at INFO
 * with the token redacted and delivers nothing; a real deployment injects
 * an SMTP/SES implementation of this interface that emails the user a
 * link carrying the token.
 */
public interface EmailVerificationMailSender {

  /** Delivers {@code rawToken} to {@code user} through the mail channel. */
  void sendEmailVerification(User user, String rawToken);
}
