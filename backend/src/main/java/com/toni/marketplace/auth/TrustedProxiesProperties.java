package com.toni.marketplace.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trusted reverse proxies for client-IP resolution (backlog #104).
 *
 * <p>{@code app.security.trusted-proxies} lists the exact remote addresses
 * of reverse proxies whose {@code X-Forwarded-For} header the rate-limit
 * filter may honor. The default is empty: no proxy is trusted, the header
 * is always ignored, and client identity is exactly
 * {@code request.getRemoteAddr()} — the behaviour the filter had before
 * #104.
 *
 * <p>Trust is exact-match on the immediate peer address only. There is
 * deliberately no CIDR or wildcard support: the list names the one proxy
 * a deployment actually runs (in the docker-compose stack, the nginx
 * container's address on the compose network), and a broader matcher
 * would widen the spoofing surface this property exists to close. A
 * deployment behind that proxy sets e.g.
 * {@code app.security.trusted-proxies=172.18.0.5}; local dev and tests
 * leave it empty.
 */
@ConfigurationProperties(prefix = "app.security")
public class TrustedProxiesProperties {

  /**
   * Exact remote addresses of trusted reverse proxies. Empty by default.
   * Entries are trimmed and blank entries ignored when the filter resolves
   * an identity; the raw list is what Spring binds from configuration.
   */
  private List<String> trustedProxies = new ArrayList<>();

  public List<String> getTrustedProxies() {
    return trustedProxies;
  }

  public void setTrustedProxies(List<String> trustedProxies) {
    this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
  }

  /** Trimmed, non-blank trusted addresses as a lookup set. */
  public Set<String> trustedProxySet() {
    Set<String> set = new HashSet<>();
    for (String entry : trustedProxies) {
      if (entry != null && !entry.isBlank()) {
        set.add(entry.trim());
      }
    }
    return Set.copyOf(set);
  }
}
