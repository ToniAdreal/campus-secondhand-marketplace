package com.toni.marketplace.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration / login / refresh flows. HTTP concerns (cookies, status codes)
 * live in {@link AuthController}; this class owns the domain logic.
 */
@Service
public class AuthService {

  private final UserRepository users;
  private final PasswordService passwords;
  private final JwtTokenService jwt;

  public AuthService(UserRepository users, PasswordService passwords, JwtTokenService jwt) {
    this.users = users;
    this.passwords = passwords;
    this.jwt = jwt;
  }

  /** Registered user plus their first token pair. */
  public record AuthResult(User user, JwtTokenService.TokenPair pair) {}

  /** Registers a new user. Duplicate username or email → 409. */
  @Transactional
  public AuthResult register(String username, String email, String rawPassword) {
    if (users.findByUsername(username).isPresent()) {
      throw new DuplicateUserException("username is already taken");
    }
    if (users.findByEmail(email).isPresent()) {
      throw new DuplicateUserException("email is already registered");
    }
    User user = users.save(new User(username, email, passwords.encode(rawPassword)));
    return new AuthResult(user, jwt.createTokenPair(user));
  }

  /**
   * Verifies credentials and issues a fresh token pair. Unknown identifier
   * and wrong password produce the identical error — no user enumeration.
   */
  @Transactional
  public AuthResult login(String usernameOrEmail, String rawPassword) {
    User user = users.findByUsername(usernameOrEmail)
        .or(() -> users.findByEmail(usernameOrEmail))
        .orElseThrow(InvalidCredentialsException::new);
    if (!passwords.matches(rawPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    return new AuthResult(user, jwt.createTokenPair(user));
  }

  /**
   * Consumes a refresh token and issues a fresh pair. The old token is
   * revoked by rotation; replaying it is treated as possible theft and
   * revokes the whole family — see {@link JwtTokenService#rotateRefreshToken}.
   */
  @Transactional
  public AuthResult refresh(String refreshToken) {
    JwtTokenService.TokenPair pair = jwt.rotateRefreshToken(refreshToken);
    // Rotation re-reads the user for role changes; resolve it for the response.
    // (Only the stored hash is known here, so re-derive the user from the new
    // access token's claims — the subject is the user id.)
    long userId = jwt.parseAccessToken(pair.accessToken()).userId();
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    return new AuthResult(user, pair);
  }

  /**
   * Logs out: deletes every refresh token of the user (all their sessions,
   * on all devices), so no presented refresh cookie can mint new access
   * tokens afterwards. The httpOnly cookie itself is cleared by the
   * controller with an expired {@code Set-Cookie}.
   */
  @Transactional
  public void logout(long userId) {
    jwt.revokeAll(userId);
  }
}
