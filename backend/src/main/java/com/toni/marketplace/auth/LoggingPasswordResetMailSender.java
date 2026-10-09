package com.toni.marketplace.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link PasswordResetMailSender} for this demo: there is no mail
 * server in the project, so nothing is actually delivered. The request is
 * logged at INFO — deliberately not WARN (a reset request is routine, not
 * an incident) — with the user id only and the token redacted: a raw reset
 * token in a log file would be a live credential for anyone with log
 * access. A real deployment replaces this bean with an SMTP/SES sender
 * (see the interface javadoc).
 */
@Component
public class LoggingPasswordResetMailSender implements PasswordResetMailSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetMailSender.class);

  @Override
  public void sendPasswordReset(User user, String rawToken) {
    log.info("Password reset requested for user id={}; token delivery is not wired to a mail "
        + "server in this demo (token redacted)", user.getId());
  }
}
