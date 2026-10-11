package com.toni.marketplace.common;

/**
 * Thrown on login when the credentials are correct but the account's
 * email address has not been verified yet (backlog #138). A distinct type
 * (and a 403, not the credential failure's 401) on purpose: the password
 * may be exactly right, so folding this into
 * {@link InvalidCredentialsException} would tell the user their password
 * is wrong and send them into a reset loop that fixes nothing. It is only
 * raised AFTER the password has matched, so it leaks the verification
 * state to nobody who does not already hold the password.
 */
public class EmailNotVerifiedException extends RuntimeException {

  public EmailNotVerifiedException() {
    super("email address is not verified");
  }
}
