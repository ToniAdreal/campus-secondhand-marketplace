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

  /**
   * Registers a new user. Weak passwords → 400 (see
   * {@link PasswordStrengthValidator}); duplicate username or email → 409.
   */
  @Transactional
  public AuthResult register(String username, String email, String rawPassword) {
    PasswordStrengthValidator.requireStrong(rawPassword);
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
   * Changes the user's password. Requires the current password — a wrong
   * current password produces the identical 401 as a bad login, so no
   * oracle leaks whether the attempt was "wrong user" or "wrong password".
   * The new password must satisfy {@link PasswordStrengthValidator} (weak →
   * 400). On success the whole refresh-token family is revoked — every
   * other session dies — and the caller's session gets a fresh pair, so
   * they stay logged in while stolen/other sessions are cut off.
   */
  @Transactional
  public AuthResult changePassword(long userId, String currentPassword, String newPassword) {
    User user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
    if (!passwords.matches(currentPassword, user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    PasswordStrengthValidator.requireStrong(newPassword);
    user.setPasswordHash(passwords.encode(newPassword));
    users.save(user);
    jwt.revokeAll(userId);
    return new AuthResult(user, jwt.createTokenPair(user));
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
