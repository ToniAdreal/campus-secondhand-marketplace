package com.toni.marketplace.auth;

import java.time.Clock;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, AuthRateLimitProperties.class})
public class AuthConfig {

  /**
   * Application clock, exposed as a bean so time-dependent components (the
   * JWT service, the auth rate limiter) can be driven by a controllable
   * clock in tests.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public PasswordService passwordService() {
    return new PasswordService();
  }

  /**
   * Token bucket for the credential endpoints
   * ({@code POST /api/auth/login}, {@code POST /api/auth/register}) —
   * default 5 attempts/minute per client IP.
   */
  @Bean
  public AuthRateLimiter credentialRateLimiter(Clock clock, AuthRateLimitProperties props) {
    return new AuthRateLimiter(clock, props);
  }

  /**
   * Separate token bucket for {@code POST /api/auth/refresh} (default
   * 30/minute per client IP). The refresh endpoint is its own
   * unauthenticated brute-force surface: it must not share the tight
   * credential bucket (legitimate multi-tab clients refresh routinely),
   * and the credential bucket must not be consumed by refresh traffic.
   */
  @Bean
  public AuthRateLimiter refreshRateLimiter(Clock clock, AuthRateLimitProperties props) {
    AuthRateLimitProperties refreshProps = new AuthRateLimitProperties();
    refreshProps.setEnabled(props.isEnabled());
    refreshProps.setAttemptsPerMinute(props.getRefreshAttemptsPerMinute());
    return new AuthRateLimiter(clock, refreshProps);
  }

  @Bean
  public JwtTokenService jwtTokenService(JwtProperties props,
                                         RefreshTokenRepository refreshTokens,
                                         UserRepository users,
                                         Clock clock) {
    SecretKey key = JwtTokenService.keyFromBase64(props.getSecret());
    return new JwtTokenService(key, props.getAccessTtl(), props.getRefreshTtl(),
        refreshTokens, users, clock);
  }
}
