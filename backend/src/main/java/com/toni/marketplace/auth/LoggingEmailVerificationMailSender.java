package com.toni.marketplace.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailVerificationMailSender} for this demo: there is no
 * mail server in the project, so nothing is actually delivered. The
 * request is logged at INFO — deliberately not WARN (a signup is routine,
 * not an incident) — with the user id only and the token redacted: a raw
 * verification token in a log file would let anyone with log access
 * confirm someone else's address. A real deployment replaces this bean
 * with an SMTP/SES sender (see the interface javadoc).
 */
@Component
public class LoggingEmailVerificationMailSender implements EmailVerificationMailSender {

  private static final Logger log =
      LoggerFactory.getLogger(LoggingEmailVerificationMailSender.class);

  @Override
  public void sendEmailVerification(User user, String rawToken) {
    log.info("Email verification requested for user id={}; token delivery is not wired to a "
        + "mail server in this demo (token redacted)", user.getId());
  }
}
