package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TOTP secret encryption-at-rest key (backlog #89).
 *
 * <p>Binds {@code app.auth.totp.encryption-key}: a Base64-encoded 256-bit
 * AES key used by {@link TotpSecretCipher} to encrypt the TOTP shared
 * secret before it is stored on the {@code app_user} row. The committed
 * default in {@code application.yml} is a dev-only placeholder (sourced
 * from the {@code APP_TOTP_ENCRYPTION_KEY} environment variable when set);
 * on the {@code mysql} profile {@link TotpEncryptionKeyStartupCheck}
 * refuses to boot with that placeholder, mirroring the JWT secret guard
 * from backlog #48.
 */
@ConfigurationProperties(prefix = "app.auth.totp")
public class TotpEncryptionProperties {

  /** Base64-encoded 32-byte AES-256 key. Dev placeholder by default. */
  private String encryptionKey;

  public String getEncryptionKey() {
    return encryptionKey;
  }

  public void setEncryptionKey(String encryptionKey) {
    this.encryptionKey = encryptionKey;
  }
}
