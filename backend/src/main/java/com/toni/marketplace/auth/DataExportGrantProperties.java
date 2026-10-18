package com.toni.marketplace.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Data-export grant policy (backlog #188). The grant is a bearer
 * <redacted> for the heaviest sensitive read in the app, so it is
 * single-use and deliberately short-lived: 5 minutes is enough for
 * the SPA's confirm-then-download sequence and nothing more.
 * Override with {@code app.auth.data-export.grant-ttl=10m}.
 */
@ConfigurationProperties(prefix = "app.auth.data-export")
public class DataExportGrantProperties {

  /** How long an issued export grant stays consumable. Must be positive. */
  private Duration grantTtl = Duration.ofMinutes(5);

  public Duration getGrantTtl() {
    return grantTtl;
  }

  public void setGrantTtl(Duration grantTtl) {
    this.grantTtl = grantTtl;
  }
}
