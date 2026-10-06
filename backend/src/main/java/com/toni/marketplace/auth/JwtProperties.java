package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code app.jwt.*} from application.yml. */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

  /**
   * Base64-encoded HS256 key (≥ 256 bits). Dev default is a throwaway key;
   * production MUST override via the APP_JWT_SECRET environment variable.
   */
  private String secret;

  private Duration accessTtl = Duration.ofMinutes(15);

  private Duration refreshTtl = Duration.ofDays(7);

  public String getSecret() { return secret; }
  public void setSecret(String secret) { this.secret = secret; }
  public Duration getAccessTtl() { return accessTtl; }
  public void setAccessTtl(Duration accessTtl) { this.accessTtl = accessTtl; }
  public Duration getRefreshTtl() { return refreshTtl; }
  public void setRefreshTtl(Duration refreshTtl) { this.refreshTtl = refreshTtl; }
}
