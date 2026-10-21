package com.toni.marketplace.auth;

/**
 * The mail seam for email-change delivery (backlog #208): the pending
 * email-change flow hands the raw token to exactly this interface and
 * nowhere else — the database stores only its SHA-256 hash, and API
 * responses never contain it (the request endpoint answers a plain 200
 * envelope whether it staged a change or not beyond its documented
 * 401/409 failures, and the confirm endpoint's token IS the credential).
 *
 * <p>This is deliberately NOT the {@link EmailVerificationMailSender}
 * seam: a verification token flips a flag, while a change token moves
 * the account's address — conflating the two would let a registration
 * link switch an address, or a change link verify one. Each flow has
 * its own token table and its own seam (the "own purpose" rule).
 *
 * <p>Honest scope: this project has no mail server. The default bean
 * ({@link LoggingEmailChangeMailSender}) logs the request at INFO with
 * the token redacted and delivers nothing; a real deployment injects
 * an SMTP/SES implementation of this interface that emails the NEW
 * address a link carrying the token.
 */
public interface EmailChangeMailSender {

  /** Delivers {@code rawToken} to the account's pending (new) address. */
  void sendEmailChange(User user, String pendingEmail, String rawToken);
}
