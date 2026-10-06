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
