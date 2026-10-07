package com.toni.marketplace.auth;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for the JWT signing secret on the {@code mysql} profile.
 *
 * <p>{@code application.yml} ships a committed HS256 placeholder secret so that
 * local H2 development boots with zero configuration. Nothing stopped a
 * {@code mysql}-profile deploy (the docker-compose path, i.e. the closest
 * thing this portfolio project has to "production") from forgetting to set
 * {@code APP_JWT_SECRET} and signing every access/refresh token with that
 * public placeholder — tokens anyone can forge. This listener refuses to boot
 * in that case: if the resolved secret still equals the dev placeholder, the
 * application fails fast at startup with a message naming
 * {@code APP_JWT_SECRET}.
 *
 * <p>The bean only exists on the {@code mysql} profile, so local H2 runs and
 * the default-profile test suite are unaffected.
 *
 * <p>Honest scope: this guards the <em>known placeholder</em>, not secret
 * strength in general — an operator-supplied short-but-non-placeholder value
 * still boots (jjwt will reject keys that are cryptographically too short for
 * HS256, which is a separate backstop). Key rotation after a leak is a
 * declared follow-up.
 */
@Component
@Profile("mysql")
public class JwtSecretStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

  /**
   * The dev placeholder secret. Must match the default in
   * {@code application.yml}'s {@code app.jwt.secret}; the
   * {@link JwtSecretStartupCheckTest} pins the coupling (a drifted constant
   * lets the placeholder through on the mysql profile and the fail-fast
   * context test goes red).
   */
  static final String DEV_PLACEHOLDER_SECRET =
      "74xcpYqCpoJStMnkqprFiMYwsQIZD/zPa3LgyU83nbE=";

  private final JwtProperties jwtProperties;

  public JwtSecretStartupCheck(JwtProperties jwtProperties) {
    this.jwtProperties = jwtProperties;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (DEV_PLACEHOLDER_SECRET.equals(jwtProperties.getSecret())) {
      throw new IllegalStateException(
          "Refusing to boot on the 'mysql' profile with the committed dev JWT "
              + "secret — tokens would be signed with a public key. Set the "
              + "APP_JWT_SECRET environment variable to a random 256-bit (or "
              + "larger) Base64 secret, e.g. generated with "
              + "'openssl rand -base64 32'.");
    }
  }
}
