package com.toni.marketplace.auth;

import java.time.Clock;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class AuthConfig {

  @Bean
  public PasswordService passwordService() {
    return new PasswordService();
  }

  @Bean
  public JwtTokenService jwtTokenService(JwtProperties props,
                                         RefreshTokenRepository refreshTokens,
                                         UserRepository users) {
    SecretKey key = JwtTokenService.keyFromBase64(props.getSecret());
    return new JwtTokenService(key, props.getAccessTtl(), props.getRefreshTtl(),
        refreshTokens, users, Clock.systemUTC());
  }
}
