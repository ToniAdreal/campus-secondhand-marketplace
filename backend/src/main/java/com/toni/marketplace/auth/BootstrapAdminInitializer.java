package com.toni.marketplace.auth;

import java.util.EnumSet;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the first ADMIN account when — and only when — the operator asks
 * for one via {@code app.bootstrap.*} (backlog #213).
 *
 * <p>Why this exists: the ADMIN role gates the moderation queues (reports,
 * disputes, user management), but registration only ever mints USER
 * accounts, so a fresh deployment had NO supported way to obtain its first
 * admin — the only path was updating {@code app_user.roles} by hand in the
 * database (the README's lone-admin TOTP runbook documents the same kind
 * of surgery). The Playwright dispute flow (#213) needs an admin in the
 * throwaway e2e stack for exactly the same reason an operator needs one on
 * a real deployment.
 *
 * <p>Safety shape:
 * <ul>
 *   <li>Opt-in and all-or-nothing: with none of
 *       {@code APP_BOOTSTRAP_ADMIN_USERNAME} / {@code _EMAIL} /
 *       {@code _PASSWORD} set, this is a strict no-op. A PARTIAL
 *       configuration fails the boot fast instead of guessing — a
 *       half-configured seed must never silently mint (or skip) a
 *       privileged account.</li>
 *   <li>The configured password must be at least 12 characters (the same
 *       floor registration enforces) or the boot fails fast.</li>
 *   <li>Creation stores only the BCrypt hash ({@link PasswordService}) and
 *       marks the email verified — an operator-created account is trusted
 *       by construction, and the verification mail loop could never reach
 *       it (the default sender is log-only).</li>
 *   <li>Idempotent across restarts, and never a credential reset: if the
 *       username or email already belongs to an account, that account is
 *       only PROMOTED to ADMIN (roles gain {@link Role#ADMIN}); its
 *       password hash is left untouched, so rotating or removing the env
 *       values later can never lock the operator out or resurrect an old
 *       password.</li>
 *   <li>The log line names the seeded username only — never the email,
 *       never the password.</li>
 * </ul>
 */
@Component
public class BootstrapAdminInitializer implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

  /** Minimum bootstrap password length — the registration floor (12). */
  static final int MIN_PASSWORD_LENGTH = 12;

  private final BootstrapAdminProperties properties;
  private final UserRepository users;
  private final PasswordService passwords;

  public BootstrapAdminInitializer(BootstrapAdminProperties properties, UserRepository users,
                                   PasswordService passwords) {
    this.properties = properties;
    this.users = users;
    this.passwords = passwords;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    seed();
  }

  void seed() {
    if (properties.isUnset()) {
      return;
    }
    if (!properties.isFullySet()) {
      throw new IllegalStateException(
          "Partial bootstrap admin configuration: APP_BOOTSTRAP_ADMIN_USERNAME, "
              + "APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD must either "
              + "all be set (to seed the first ADMIN account) or all be unset.");
    }
    String password = properties.getAdminPassword();
    if (password.length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException(
          "APP_BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH
              + " characters (the same floor registration enforces).");
    }
    String username = properties.getAdminUsername().trim();
    String email = properties.getAdminEmail().trim();

    Optional<User> existing = users.findByUsernameIgnoreCase(username);
    if (existing.isEmpty()) {
      existing = users.findByEmailIgnoreCase(email);
    }
    if (existing.isPresent()) {
      User user = existing.get();
      if (!user.getRoles().contains(Role.ADMIN)) {
        user.setRoles(EnumSet.of(Role.USER, Role.ADMIN));
        users.save(user);
        LOG.info("Bootstrap admin: promoted existing account '{}' to ADMIN", user.getUsername());
      }
      return;
    }

    User admin = new User(username, email, passwords.encode(password));
    admin.setRoles(EnumSet.of(Role.USER, Role.ADMIN));
    admin.setEmailVerified(true);
    users.save(admin);
    LOG.info("Bootstrap admin: created ADMIN account '{}'", username);
  }
}
