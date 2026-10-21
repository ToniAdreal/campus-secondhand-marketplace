package com.toni.marketplace.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailChangeMailSender} for this demo: there is no
 * mail server in the project, so nothing is actually delivered. The
 * request is logged at INFO with the user id only and the token
 * redacted: a raw change token in a log file would let anyone with log
 * access move someone else's account to an address they control. A
 * real deployment replaces this bean with an SMTP/SES sender (see the
 * interface javadoc).
 */
@Component
public class LoggingEmailChangeMailSender implements EmailChangeMailSender {

  private static final Logger log =
      LoggerFactory.getLogger(LoggingEmailChangeMailSender.class);

  @Override
  public void sendEmailChange(User user, String pendingEmail, String rawToken) {
    log.info("Email change requested for user id={}; token delivery is not wired to a "
        + "mail server in this demo (token redacted)", user.getId());
  }
}
