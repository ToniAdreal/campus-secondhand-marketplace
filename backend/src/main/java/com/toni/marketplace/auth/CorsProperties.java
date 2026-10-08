package com.toni.marketplace.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS allowlist for the SPA client (backlog #63).
 *
 * <p>SecurityConfig previously had no CORS configuration at all — nothing
 * about cross-origin policy was declared. This is the explicit allowlist:
 * browsers may call the API cross-origin only from the configured origins;
 * every other origin gets no {@code Access-Control-Allow-Origin} header and
 * the browser blocks the response.
 *
 * <p>Defaults cover local development: Vite serves the SPA on
 * {@code http://localhost:5173} while the backend runs on
 * {@code http://localhost:8080}, and the docker-compose stack serves both
 * the nginx frontend and the proxied API from {@code http://localhost}
 * (same origin there, so CORS rarely engages — the entry documents the
 * intent). Production deployments override with e.g.
 * {@code app.cors.allowed-origins=https://app.example.com}.
 *
 * <p>Credentials (the httpOnly refresh cookie) are allowed only for listed
 * origins — never combined with a wildcard. The frontend's envelope errors
 * read {@code Retry-After} (429/423) and the request-correlation id
 * {@code X-Request-ID}, so both are exposed.
 */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

  /**
   * Exact origins allowed to call the API cross-origin. Wildcards are not
   * supported here: with {@code allowCredentials=true} a wildcard origin
   * would fail at startup anyway, and an allowlist is the whole point.
   */
  private List<String> allowedOrigins = new ArrayList<>(List.of(
      "http://localhost:5173", // Vite dev server
      "http://localhost" // docker-compose nginx (same-origin in practice)
  ));

  public List<String> getAllowedOrigins() {
    return allowedOrigins;
  }

  public void setAllowedOrigins(List<String> allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }
}
