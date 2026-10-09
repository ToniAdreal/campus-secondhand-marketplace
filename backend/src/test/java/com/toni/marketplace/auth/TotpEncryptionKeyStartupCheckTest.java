package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.toni.marketplace.MarketplaceApplication;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;

/**
 * Backlog #89: fail fast when the committed dev TOTP encryption key is
 * used with the {@code mysql} profile — the mirror of {@link
 * JwtSecretStartupCheckTest} (#48).
 *
 * <p>The context tests override the mysql-profile datasource back to H2
 * via command-line args (see the JWT test for why), and any boot expected
 * to succeed sets BOTH real secrets: the mysql profile carries both
 * startup checks, and either placeholder fails the boot.
 */
class TotpEncryptionKeyStartupCheckTest {

  private static String[] bootArgs(String... extra) {
    String[] base = {
      "--spring.datasource.url=jdbc:h2:mem:totpkeycheck;DB_CLOSE_DELAY=-1;MODE=MySQL",
      "--spring.datasource.driver-class-name=org.h2.Driver",
      "--spring.datasource.username=sa",
      "--spring.datasource.password=",
    };
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return all;
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static boolean mentionsTotpKeyEnv(Throwable t) {
    for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
      if (cursor.getMessage() != null
          && cursor.getMessage().contains("APP_TOTP_ENCRYPTION_KEY")) {
        return true;
      }
    }
    return false;
  }

  @Test
  void checkIsRegisteredOnlyOnTheMysqlProfile() {
    assertThat(TotpEncryptionKeyStartupCheck.class.getAnnotation(Profile.class).value())
        .containsExactly("mysql");
  }

  @Test
  void placeholderKeyThrowsWithActionableMessage() {
    TotpEncryptionProperties props = new TotpEncryptionProperties();
    props.setEncryptionKey(TotpEncryptionKeyStartupCheck.DEV_PLACEHOLDER_KEY);

    Throwable thrown =
        catchThrowable(() -> new TotpEncryptionKeyStartupCheck(props).onApplicationEvent(null));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getMessage()).contains("APP_TOTP_ENCRYPTION_KEY");
  }

  @Test
  void realKeyPassesTheCheck() {
    TotpEncryptionProperties props = new TotpEncryptionProperties();
    props.setEncryptionKey(randomKey());

    Throwable thrown =
        catchThrowable(() -> new TotpEncryptionKeyStartupCheck(props).onApplicationEvent(null));

    assertThat(thrown).as("a real key must not trip the startup check").isNull();
  }

  @Test
  void mysqlProfileWithPlaceholderKeyFailsFast() {
    Throwable thrown =
        catchThrowable(
            () ->
                new SpringApplicationBuilder(MarketplaceApplication.class)
                    .profiles("mysql")
                    // Real JWT secret, so the ONLY failing check is the
                    // TOTP one under test.
                    .run(bootArgs("--app.jwt.secret=" + randomKey())));

    assertThat(thrown)
        .as("expected the context to refuse to boot with the placeholder TOTP key")
        .isNotNull();
    assertThat(mentionsTotpKeyEnv(thrown))
        .as("expected the APP_TOTP_ENCRYPTION_KEY fail-fast message in the cause chain")
        .isTrue();
  }

  @Test
  void mysqlProfileWithRealKeyBootsAndRegistersTheCheck() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class)
            .profiles("mysql")
            .run(bootArgs("--app.jwt.secret=" + randomKey(),
                "--app.auth.totp.encryption-key=" + randomKey()))) {
      assertThat(context.getBeansOfType(TotpEncryptionKeyStartupCheck.class)).hasSize(1);
    }
  }

  @Test
  void defaultProfileIsUnaffectedByTheCheck() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class).run(bootArgs())) {
      assertThat(context.getBeansOfType(TotpEncryptionKeyStartupCheck.class)).isEmpty();
    }
  }
}
