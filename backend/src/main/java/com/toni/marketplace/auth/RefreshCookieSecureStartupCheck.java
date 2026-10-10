package com.toni.marketplace.auth;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for the refresh cookie's {@code Secure} flag on the
 * {@code mysql} profile (backlog #130), mirroring
 * {@link JwtSecretStartupCheck} (#48) and
 * {@link TotpEncryptionKeyStartupCheck} (#89).
 *
 * <p>{@code app.auth.cookie-secure} defaults to {@code false} so local
 * plain-HTTP development works with zero configuration — but nothing
 * stopped a {@code mysql}-profile deploy (the docker-compose path, i.e.
 * the closest thing this portfolio project has to "production", served
 * behind HTTPS) from silently issuing the 7-day httpOnly refresh cookie
 * without the {@code Secure} attribute, so the browser would happily send
 * that long-lived credential over any plain-HTTP request to the same
 * host. This listener refuses to boot in that case.
 *
 * <p>The one escape hatch is explicit: {@code
 * app.auth.allow-insecure-cookie=true} (documented as local-only) lets a
 * mysql-profile run on a developer machine boot without the flag. It is
 * an opt-out the operator must type, never a default.
 *
 * <p>The bean only exists on the {@code mysql} profile, so local H2 runs
 * and the default-profile test suite are unaffected. {@code @Order}
 * places this check after the JWT/TOTP placeholder checks, so a boot
 * that would fail several guards reports the credential-key problems
 * first.
 */
@Component
@Profile("mysql")
@Order(Ordered.LOWEST_PRECEDENCE)
public class RefreshCookieSecureStartupCheck
    implements ApplicationListener<ApplicationReadyEvent> {

  private final RefreshCookieProperties properties;

  public RefreshCookieSecureStartupCheck(RefreshCookieProperties properties) {
    this.properties = properties;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (!properties.isCookieSecure() && !properties.isAllowInsecureCookie()) {
      throw new IllegalStateException(
          "Refusing to boot on the 'mysql' profile with "
              + "app.auth.cookie-secure=false — the httpOnly refresh_token "
              + "cookie would be served without the Secure attribute on an "
              + "HTTPS deployment, so browsers could send the 7-day "
              + "refresh credential over plain HTTP. Set "
              + "app.auth.cookie-secure=true (APP_AUTH_COOKIE_SECURE=true) "
              + "for HTTPS deployments, or — local-only, never in "
              + "production — set app.auth.allow-insecure-cookie=true to "
              + "acknowledge an intentionally insecure cookie.");
    }
  }
}
