package com.toni.marketplace.notification;

/**
 * Body of {@code POST /api/notifications/stream-ticket} (backlog #203):
 * a one-time, 60-second ticket the SPA trades for the SSE stream, so the
 * access token never appears in the stream URL.
 */
public record StreamTicketResponse(String ticket) {}
