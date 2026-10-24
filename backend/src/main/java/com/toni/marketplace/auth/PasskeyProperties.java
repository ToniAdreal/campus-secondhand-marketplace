package com.toni.marketplace.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebAuthn passkey configuration (backlog #220).
 *
 * <p>{@code rp-id} is the Relying Party ID the server asserts — it must be
 * the registrable domain the SPA is served from (WebAuthn binds every
 * credential to it; a credential enrolled under one rp-id can never be
 * asserted under another). {@code allowed-origins} is the exact set of
 * origins the browser's client data may carry — scheme, host and port
 * included. Both are deployment facts, so both are operator-configured;
 * the defaults describe local development (the Vite dev server on
 * {@code http://localhost:5173} against the API on {@code :8080}).
 *
 * <p>An empty {@code allowed-origins} fails closed: {@link PasskeyService}
 * builds a {@code ServerProperty} with no accepted origin, so every
 * ceremony verification rejects — a misconfigured deployment enrolls
 * nothing rather than enrolling against the wrong origin.
 */
@ConfigurationProperties(prefix = "app.auth.passkey")
public class PasskeyProperties {

  /** Relying Party ID, e.g. {@code example.com} in production. */
  private String rpId = "localhost";

  /** Exact origins accepted in the client's collected client data. */
  private List<String> allowedOrigins = List.of("http://localhost:5173");

  /** Lifetime of a stored WebAuthn challenge (registration or assertion). */
  private Duration challengeTtl = Duration.ofMinutes(5);

  public String getRpId() {
    return rpId;
  }

  public void setRpId(String rpId) {
    this.rpId = rpId;
  }

  public List<String> getAllowedOrigins() {
    return allowedOrigins;
  }

  public void setAllowedOrigins(List<String> allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  public Duration getChallengeTtl() {
    return challengeTtl;
  }

  public void setChallengeTtl(Duration challengeTtl) {
    this.challengeTtl = challengeTtl;
  }
}
