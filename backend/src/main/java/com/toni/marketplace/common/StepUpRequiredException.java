package com.toni.marketplace.common;

/**
 * Step-up re-authentication required (backlog #218): the presented access
 * token is valid but its last credential proof ({@code auth_time} claim) is
 * missing or older than {@code app.auth.step-up.max-age}, so a sensitive
 * action — sessions revoke-others, the data-export grant, TOTP setup
 * (backlog #250) and disable, account closure, passkey enrolment/
 * management, username change (backlog #261), email-change request
 * (backlog #272) — is refused. Answered with a dedicated 403 step-up
 * envelope (never a 401, which would claim the credential itself is bad);
 * the SPA's re-auth modal re-proves the credential and retries the action
 * exactly once.
 */
public class StepUpRequiredException extends RuntimeException {

  public StepUpRequiredException() {
    super("re-authentication required — please sign in again to continue");
  }
}
