package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * One row of the caller's own login history for
 * {@code GET /api/auth/login-history} (backlog #171). A projection of
 * {@link LoginEvent} — never the entity. Only rows whose {@code user_id}
 * is the caller's are ever mapped here, so {@code identifier} is always
 * an identifier the caller themselves typed (or their username, on
 * second-factor rows); unknown-identifier rows are recorded with a NULL
 * user and are never exposed by the read API.
 *
 * @param id         the login_event row id
 * @param outcome    the attempt's verdict (see {@link LoginOutcome})
 * @param identifier the attempted identifier as typed
 * @param ipAddress  client IP resolved at capture time; NULL when unknown
 * @param userAgent  device label captured at capture time; NULL when the client sent none
 * @param createdAt  when the attempt happened
 */
public record LoginEventDto(Long id, LoginOutcome outcome, String identifier,
                            String ipAddress, String userAgent, Instant createdAt) {

  public static LoginEventDto of(LoginEvent event) {
    return new LoginEventDto(event.getId(), event.getOutcome(), event.getIdentifier(),
        event.getIpAddress(), event.getUserAgent(), event.getCreatedAt());
  }
}
