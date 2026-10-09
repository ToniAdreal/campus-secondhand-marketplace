package com.toni.marketplace.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trusted-proxy client-IP resolution for the rate-limit buckets
 * (backlog #104) through {@link AuthRateLimitFilter}. The proxy
 * {@code 10.99.0.1} (and intermediate {@code 10.99.0.2}) are configured as
 * trusted; every other remote address is not. Each test uses fresh
 * resolved identities because the limiter buckets are in-memory and
 * survive across tests within this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.attempts-per-minute=5",
    "app.auth.login-lockout.enabled=false",
    "app.security.trusted-proxies=10.99.0.1,10.99.0.2"
})
class TrustedProxyRateLimitTest {

  @TestConfiguration
  static class ControllableClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  private static final AtomicInteger SEQ = new AtomicInteger(1);
  private static final String PROXY = "10.99.0.1";
  private static final String PASSWORD = "s3cret-pass";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @BeforeEach
  void setUp() {
    users.save(new User("tpvictim", "tpvictim@example.com", passwords.encode(PASSWORD)));
  }

  private static RequestPostProcessor proxied(String remoteAddr, String forwardedFor) {
    return request -> {
      request.setRemoteAddr(remoteAddr);
      if (forwardedFor != null) {
        request.addHeader("X-Forwarded-For", forwardedFor);
      }
      return request;
    };
  }

  private void failedLogin(String remoteAddr, String forwardedFor) throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .with(proxied(remoteAddr, forwardedFor))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"tpvictim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  private void throttledLogin(String remoteAddr, String forwardedFor) throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .with(proxied(remoteAddr, forwardedFor))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"usernameOrEmail\":\"tpvictim\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @Test
  void headerIsIgnoredFromAnUntrustedPeer() throws Exception {
    int n = SEQ.getAndIncrement();
    String untrusted = "10.230.1." + n;
    // Every attempt claims a different forwarded client. If the header
    // were honored, each would land in its own bucket and never throttle;
    // ignored, all five share the remote address's bucket and the 6th 429s.
    for (int i = 0; i < 5; i++) {
      failedLogin(untrusted, "198.51.100." + (n * 10 + i));
    }
    throttledLogin(untrusted, "198.51.100.250");
  }

  @Test
  void headerIsHonoredFromATrustedProxyGivingIndependentBuckets() throws Exception {
    int n = SEQ.getAndIncrement();
    String clientA = "203.0.113." + (100 + n);
    String clientB = "203.0.113." + (150 + n);
    for (int i = 0; i < 5; i++) {
      failedLogin(PROXY, clientA);
    }
    throttledLogin(PROXY, clientA);
    // A second forwarded identity through the same proxy has its own
    // untouched bucket — it is not starved by client A's burst.
    failedLogin(PROXY, clientB);
  }

  @Test
  void spoofedMultiHopChainCannotPickAnArbitraryKey() throws Exception {
    int n = SEQ.getAndIncrement();
    String realClient = "203.0.113." + (50 + n);
    // The trusted proxy appended the real client after a client-supplied
    // spoofed prefix. Five attempts exhaust the real client's bucket...
    for (int i = 0; i < 5; i++) {
      failedLogin(PROXY, "9.9.9." + i + ", " + realClient);
    }
    // ...and swapping in a fresh spoofed prefix does not mint a new key:
    // resolution stops at the first untrusted hop (the real client).
    throttledLogin(PROXY, "8.8.4.4, " + realClient);
  }
}
