-- V13: device info on refresh_token (backlog #62 — per-session management)
--
-- GET /api/auth/sessions lists the caller's live sessions with a device
-- label; the label is captured from the login/refresh request's User-Agent
-- (user_agent) and remote address (ip_address) by JwtTokenService and
-- refreshed onto each rotated row. Nullable: rows written before this
-- migration predate the capture and simply show no device label.
--
-- Portability: plain H2 (the @DataJpaTest slice tests) rejects
-- multi-ADD-COLUMN in one ALTER, so two statements — the same reason V11
-- split its two column adds (see its header).
ALTER TABLE refresh_token ADD COLUMN user_agent VARCHAR(512);
ALTER TABLE refresh_token ADD COLUMN ip_address VARCHAR(64);
