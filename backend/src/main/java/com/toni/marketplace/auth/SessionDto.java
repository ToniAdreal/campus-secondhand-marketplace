package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * One live refresh-token session for {@code GET /api/auth/sessions}
 * (backlog #62). A session is a refresh-token family: the stable
 * {@code id} is the family's root jti, so it survives rotations (unlike the
 * underlying row id, which changes on every refresh).
 *
 * @param id          the session id (the family's root jti); the path id
 *                    for {@code DELETE /api/auth/sessions/{id}}
 * @param userAgent   device label captured from the login/refresh request's
 *                    User-Agent; NULL for sessions that predate the capture
 *                    (Flyway V13) or clients that sent no User-Agent
 * @param ipAddress   remote address captured at the last login/refresh
 * @param createdAt   when the session's family was founded (the login)
 * @param lastActiveAt when the session's live token was issued (its last
 *                    login or refresh)
 * @param current     true for the session the caller presented (matched via
 *                    the presented refresh token's jti → family)
 */
public record SessionDto(String id, String userAgent, String ipAddress,
                         Instant createdAt, Instant lastActiveAt, boolean current) {
}
