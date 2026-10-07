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
 * Backlog #48: fail fast when the committed dev JWT secret is used with the
 * {@code mysql} profile.
 *
 * <p>The context tests override the mysql-profile datasource back to H2 via
 * command-line args (highest property-source precedence — the builder's
 * {@code properties()} API only sets lowest-precedence defaults, which lose
 * to the mysql section of {@code application.yml}): this VM has no
 * Docker/MySQL, and the point under test is the profile-gated startup check,
 * not the database.
 */
class JwtSecretStartupCheckTest {

  private static String[] bootArgs(String... extra) {
    String[] base = {
      "--spring.datasource.url=jdbc:h2:mem:jwtsecretcheck;DB_CLOSE_DELAY=-1;MODE=MySQL",
      "--spring.datasource.driver-class-name=org.h2.Driver",
      "--spring.datasource.username=sa",
      "--spring.datasource.password=",
    };
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return all;
  }

  private static String randomSecret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static boolean mentionsJwtSecretEnv(Throwable t) {
    for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
      if (cursor.getMessage() != null && cursor.getMessage().contains("APP_JWT_SECRET")) {
        return true;
      }
    }
    return false;
  }

  @Test
  void checkIsRegisteredOnlyOnTheMysqlProfile() {
    assertThat(JwtSecretStartupCheck.class.getAnnotation(Profile.class).value())
        .containsExactly("mysql");
  }

  @Test
  void placeholderSecretThrowsWithActionableMessage() {
    JwtProperties props = new JwtProperties();
    props.setSecret(JwtSecretStartupCheck.DEV_PLACEHOLDER_SECRET);

    Throwable thrown =
        catchThrowable(() -> new JwtSecretStartupCheck(props).onApplicationEvent(null));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getMessage()).contains("APP_JWT_SECRET");
  }

  @Test
  void realSecretPassesTheCheck() {
    JwtProperties props = new JwtProperties();
    props.setSecret(randomSecret());

    Throwable thrown =
        catchThrowable(() -> new JwtSecretStartupCheck(props).onApplicationEvent(null));

    assertThat(thrown).as("a real secret must not trip the startup check").isNull();
  }

  @Test
  void mysqlProfileWithPlaceholderSecretFailsFast() {
    Throwable thrown =
        catchThrowable(
            () ->
                new SpringApplicationBuilder(MarketplaceApplication.class)
                    .profiles("mysql")
                    .run(bootArgs()));

    assertThat(thrown)
        .as("expected the context to refuse to boot with the placeholder secret")
        .isNotNull();
    assertThat(mentionsJwtSecretEnv(thrown))
        .as("expected the APP_JWT_SECRET fail-fast message in the cause chain")
        .isTrue();
  }

  @Test
  void mysqlProfileWithRealSecretBootsAndRegistersTheCheck() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class)
            .profiles("mysql")
            .run(bootArgs("--app.jwt.secret=" + randomSecret()))) {
      assertThat(context.getBeansOfType(JwtSecretStartupCheck.class)).hasSize(1);
    }
  }

  @Test
  void defaultProfileIsUnaffectedByTheCheck() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class).run(bootArgs())) {
      assertThat(context.getBeansOfType(JwtSecretStartupCheck.class)).isEmpty();
    }
  }
}
