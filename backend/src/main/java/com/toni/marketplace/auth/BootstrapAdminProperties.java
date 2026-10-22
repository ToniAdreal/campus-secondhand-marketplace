package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.bootstrap.*} (backlog #213): the optional first-ADMIN
 * seed. All three fields default to blank — with none of them set the
 * {@link BootstrapAdminInitializer} is a strict no-op and the deployment
 * behaves exactly as before (the first ADMIN historically required direct
 * database surgery, the same runbook case the README documents for a lone
 * admin's TOTP recovery). The values are meant to come from the
 * {@code APP_BOOTSTRAP_ADMIN_*} environment variables, never from a
 * committed file.
 */
@ConfigurationProperties(prefix = "app.bootstrap")
public class BootstrapAdminProperties {

  /** Username for the seeded ADMIN account. Blank = no seeding. */
  private String adminUsername = "";

  /** Email for the seeded ADMIN account. Blank = no seeding. */
  private String adminEmail = "";

  /**
   * Password for the seeded ADMIN account, used ONLY when the account is
   * created. It is BCrypt-hashed before storage and is never applied to an
   * existing account (a restart never resets the admin's password).
   */
  private String adminPassword = "";

  public String getAdminUsername() {
    return adminUsername;
  }

  public void setAdminUsername(String adminUsername) {
    this.adminUsername = adminUsername;
  }

  public String getAdminEmail() {
    return adminEmail;
  }

  public void setAdminEmail(String adminEmail) {
    this.adminEmail = adminEmail;
  }

  public String getAdminPassword() {
    return adminPassword;
  }

  public void setAdminPassword(String adminPassword) {
    this.adminPassword = adminPassword;
  }

  /** True when none of the three fields carries a value. */
  public boolean isUnset() {
    return isBlank(adminUsername) && isBlank(adminEmail) && isBlank(adminPassword);
  }

  /** True when all three fields carry a value. */
  public boolean isFullySet() {
    return !isBlank(adminUsername) && !isBlank(adminEmail) && !isBlank(adminPassword);
  }

  static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
