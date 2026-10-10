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
 * Backlog #130: fail fast when the refresh cookie would be served without
 * the {@code Secure} attribute on the {@code mysql} profile — the mirror
 * of {@link JwtSecretStartupCheckTest} (#48).
 *
 * <p>The context tests override the mysql-profile datasource back to H2
 * via command-line args (see the JWT test for why), and any boot expected
 * to fail or succeed on THIS check sets real JWT + TOTP secrets, so the
 * cookie guard is the only one that can refuse the boot.
 */
class RefreshCookieSecureStartupCheckTest {

  private static String[] bootArgs(String... extra) {
    String[] base = {
      "--spring.datasource.url=jdbc:h2:mem:cookiesecurecheck;DB_CLOSE_DELAY=-1;MODE=MySQL",
      "--spring.datasource.driver-class-name=org.h2.Driver",
      "--spring.datasource.username=sa",
      "--spring.datasource.password=",
      // Real credential keys, so the #48/#89 guards pass and only the
      // cookie guard under test decides this boot.
      "--app.jwt.secret=" + randomKey(),
      "--app.auth.totp.encryption-key=" + randomKey(),
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

  private static boolean mentionsCookieSecure(Throwable t) {
    for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
      if (cursor.getMessage() != null
          && cursor.getMessage().contains("app.auth.cookie-secure")) {
        return true;
      }
    }
    return false;
  }

  @Test
  void checkIsRegisteredOnlyOnTheMysqlProfile() {
    assertThat(RefreshCookieSecureStartupCheck.class.getAnnotation(Profile.class).value())
        .containsExactly("mysql");
  }

  @Test
  void insecureCookieWithoutOptOutThrowsWithActionableMessage() {
    RefreshCookieProperties props = new RefreshCookieProperties();
    assertThat(props.isCookieSecure()).isFalse();
    assertThat(props.isAllowInsecureCookie()).isFalse();

    Throwable thrown = catchThrowable(
        () -> new RefreshCookieSecureStartupCheck(props).onApplicationEvent(null));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getMessage()).contains("app.auth.cookie-secure");
    assertThat(thrown.getMessage()).contains("allow-insecure-cookie");
  }

  @Test
  void secureCookiePassesTheCheck() {
    RefreshCookieProperties props = new RefreshCookieProperties();
    props.setCookieSecure(true);

    Throwable thrown = catchThrowable(
        () -> new RefreshCookieSecureStartupCheck(props).onApplicationEvent(null));

    assertThat(thrown).as("a Secure cookie must not trip the startup check").isNull();
  }

  @Test
  void insecureCookieWithExplicitOptOutPassesTheCheck() {
    RefreshCookieProperties props = new RefreshCookieProperties();
    props.setAllowInsecureCookie(true);

    Throwable thrown = catchThrowable(
        () -> new RefreshCookieSecureStartupCheck(props).onApplicationEvent(null));

    assertThat(thrown).as("the explicit local-only opt-out must be honoured").isNull();
  }

  @Test
  void mysqlProfileWithInsecureCookieFailsFast() {
    Throwable thrown =
        catchThrowable(
            () ->
                new SpringApplicationBuilder(MarketplaceApplication.class)
                    .profiles("mysql")
                    .run(bootArgs()));

    assertThat(thrown)
        .as("expected the context to refuse to boot with cookie-secure=false")
        .isNotNull();
    assertThat(mentionsCookieSecure(thrown))
        .as("expected the app.auth.cookie-secure fail-fast message in the cause chain")
        .isTrue();
  }

  @Test
  void mysqlProfileWithSecureCookieBootsAndRegistersTheCheck() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class)
            .profiles("mysql")
            .run(bootArgs("--app.auth.cookie-secure=true"))) {
      assertThat(context.getBeansOfType(RefreshCookieSecureStartupCheck.class)).hasSize(1);
    }
  }

  @Test
  void mysqlProfileWithInsecureOptOutBoots() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class)
            .profiles("mysql")
            .run(bootArgs("--app.auth.allow-insecure-cookie=true"))) {
      assertThat(context.getBeansOfType(RefreshCookieSecureStartupCheck.class)).hasSize(1);
    }
  }

  @Test
  void defaultProfileIsUnaffectedByTheCheck() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MarketplaceApplication.class).run(bootArgs())) {
      assertThat(context.getBeansOfType(RefreshCookieSecureStartupCheck.class)).isEmpty();
    }
  }
}
