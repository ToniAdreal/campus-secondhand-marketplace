package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Password policy knobs (backlog #131), bound from {@code app.auth.*}.
 *
 * <p>Before #131 {@link PasswordStrengthValidator} had no length rule at
 * all — a 2-character password like {@code "a1"} passed (one letter + one
 * digit, not blocklisted), on every credential-setting path at once
 * (register, password change, reset confirm — all three share the one
 * validator). The minimum length now lives here so a deployment can raise
 * (or, deliberately, lower) it without a code change:
 * {@code app.auth.password-min-length} /
 * {@code APP_AUTH_PASSWORD_MIN_LENGTH}.
 *
 * <p>Honest scope: the rule applies when a password is SET. Existing
 * accounts keep working with their current (possibly shorter) password
 * until their next change/reset — there is no forced migration.
 */
@ConfigurationProperties(prefix = "app.auth")
public class PasswordPolicyProperties {

  /** Minimum password length, in characters. Matches the validator default. */
  private int passwordMinLength = PasswordStrengthValidator.DEFAULT_MIN_LENGTH;

  public int getPasswordMinLength() {
    return passwordMinLength;
  }

  public void setPasswordMinLength(int passwordMinLength) {
    this.passwordMinLength = passwordMinLength;
  }
}
