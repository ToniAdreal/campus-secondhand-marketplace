package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the trusted-proxy client-IP resolution in
 * {@link AuthRateLimitFilter#resolveClientIp} (backlog #104). The MockMvc
 * proof that the resolved identity actually keys the buckets lives in
 * {@code TrustedProxyRateLimitTest}; these pin the chain-walking rules in
 * isolation.
 */
class ClientIpResolutionTest {

  private static final Set<String> TRUSTED = Set.of("10.0.0.1", "10.0.0.2");
  private static final String PROXY = "10.0.0.1";

  @Test
  void emptyTrustedListIgnoresTheHeader() {
    assertThat(AuthRateLimitFilter.resolveClientIp("192.0.2.10", "198.51.100.7", Set.of()))
        .isEqualTo("192.0.2.10");
  }

  @Test
  void untrustedPeerCannotSpoofItsKey() {
    assertThat(AuthRateLimitFilter.resolveClientIp("192.0.2.10", "198.51.100.7", TRUSTED))
        .isEqualTo("192.0.2.10");
  }

  @Test
  void trustedProxyForwardsTheSingleClientHop() {
    assertThat(AuthRateLimitFilter.resolveClientIp(PROXY, "198.51.100.7", TRUSTED))
        .isEqualTo("198.51.100.7");
  }

  @Test
  void trustedProxyWithoutAHeaderFallsBackToTheProxyAddress() {
    assertThat(AuthRateLimitFilter.resolveClientIp(PROXY, null, TRUSTED)).isEqualTo(PROXY);
    assertThat(AuthRateLimitFilter.resolveClientIp(PROXY, "  ", TRUSTED)).isEqualTo(PROXY);
    assertThat(AuthRateLimitFilter.resolveClientIp(PROXY, " , ,", TRUSTED)).isEqualTo(PROXY);
  }

  @Test
  void spoofedPrefixBeyondTheRealClientIsNeverSelected() {
    // The client prepended 9.9.9.9; the trusted proxy appended the real
    // client address 203.0.113.7. Walking from the nearest hop outwards,
    // 203.0.113.7 is the first untrusted hop — the spoof is never reached,
    // so changing the spoofed prefix cannot mint a fresh bucket key.
    assertThat(AuthRateLimitFilter.resolveClientIp(PROXY, "9.9.9.9, 203.0.113.7", TRUSTED))
        .isEqualTo("203.0.113.7");
    assertThat(AuthRateLimitFilter.resolveClientIp(PROXY, "8.8.8.8, 203.0.113.7", TRUSTED))
        .isEqualTo("203.0.113.7");
  }

  @Test
  void trustedIntermediateHopsAreSkipped() {
    // Chain: client 203.0.113.7 -> trusted 10.0.0.2 -> trusted remote 10.0.0.1.
    assertThat(AuthRateLimitFilter.resolveClientIp(PROXY, "203.0.113.7, 10.0.0.2", TRUSTED))
        .isEqualTo("203.0.113.7");
  }

  @Test
  void allTrustedChainResolvesToTheLeftmostHop() {
    assertThat(AuthRateLimitFilter.resolveClientIp(PROXY, "10.0.0.2, 10.0.0.1", TRUSTED))
        .isEqualTo("10.0.0.2");
  }

  @Test
  void missingRemoteAddressIsTheSharedUnknownBucket() {
    assertThat(AuthRateLimitFilter.resolveClientIp(null, "198.51.100.7", TRUSTED))
        .isEqualTo("unknown");
    assertThat(AuthRateLimitFilter.resolveClientIp(" ", "198.51.100.7", TRUSTED))
        .isEqualTo("unknown");
  }

  @Test
  void trustedListEntriesAreTrimmedAndBlanksIgnored() {
    TrustedProxiesProperties props = new TrustedProxiesProperties();
    props.setTrustedProxies(java.util.List.of(" 10.0.0.1 ", "", "10.0.0.2"));
    assertThat(props.trustedProxySet()).containsExactlyInAnyOrder("10.0.0.1", "10.0.0.2");
    assertThat(AuthRateLimitFilter.resolveClientIp(
        PROXY, "198.51.100.7", props.trustedProxySet())).isEqualTo("198.51.100.7");
  }
}
