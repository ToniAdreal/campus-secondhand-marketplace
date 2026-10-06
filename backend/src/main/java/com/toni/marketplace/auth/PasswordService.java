package com.toni.marketplace.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing via BCrypt (cost 12). Wraps the encoder so call sites stay
 * readable and tests can target this seam.
 */
public class PasswordService {

  private final PasswordEncoder encoder;

  public PasswordService() {
    this(new BCryptPasswordEncoder(12));
  }

  PasswordService(PasswordEncoder encoder) {
    this.encoder = encoder;
  }

  public String encode(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  public boolean matches(String rawPassword, String passwordHash) {
    return encoder.matches(rawPassword, passwordHash);
  }
}
