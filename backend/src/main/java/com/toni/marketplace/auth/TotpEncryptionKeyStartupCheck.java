package com.toni.marketplace.auth;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for the TOTP encryption key on the {@code mysql} profile
 * (backlog #89), mirroring {@link JwtSecretStartupCheck} (#48).
 *
 * <p>{@code application.yml} ships a committed placeholder key so local H2
 * development boots with zero configuration. A {@code mysql}-profile
 * deploy (the docker-compose path) that forgets {@code
 * APP_TOTP_ENCRYPTION_KEY} would encrypt every TOTP secret under that
 * public key — encryption at rest in name only, since anyone can read the
 * key from the repo and decrypt the column. This listener refuses to boot
 * in that case. Because the yml default is the placeholder, "unset" and
 * "still placeholder" are the same state and both fail fast.
 *
 * <p>The bean only exists on the {@code mysql} profile, so local H2 runs
 * and the default-profile test suite are unaffected. Malformed keys
 * (non-Base64, wrong length) fail even earlier, while the {@link
 * TotpSecretCipher} bean is constructed, on every profile.
 */
@Component
@Profile("mysql")
public class TotpEncryptionKeyStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

  /**
   * The dev placeholder key. Must match the default in {@code
   * application.yml}'s {@code app.auth.totp.encryption-key}; the {@link
   * TotpEncryptionKeyStartupCheckTest} pins the coupling the same way the
   * JWT check's test does (a drifted constant lets the placeholder through
   * on the mysql profile and the fail-fast context test goes red).
   */
  static final String DEV_PLACEHOLDER_KEY =
      "dPMyJmneAVMf3iVLfGxDnvt3/UU9XI6hjfNNpracjOM=";

  private final TotpEncryptionProperties properties;

  public TotpEncryptionKeyStartupCheck(TotpEncryptionProperties properties) {
    this.properties = properties;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (DEV_PLACEHOLDER_KEY.equals(properties.getEncryptionKey())) {
      throw new IllegalStateException(
          "Refusing to boot on the 'mysql' profile with the committed dev TOTP "
              + "encryption key — every TOTP secret would be encrypted under a "
              + "public key. Set the APP_TOTP_ENCRYPTION_KEY environment variable "
              + "to a random 256-bit Base64 key, e.g. generated with "
              + "'openssl rand -base64 32'. Note: rotating this key later makes "
              + "previously stored TOTP secrets undecryptable (affected users "
              + "must re-enroll in 2FA).");
    }
  }
}
