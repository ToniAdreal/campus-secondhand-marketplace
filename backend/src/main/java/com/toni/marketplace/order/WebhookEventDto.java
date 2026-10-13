package com.toni.marketplace.order;

import java.time.Instant;

/**
 * One row of the ADMIN webhook-event viewer (backlog #156). Built from
 * the entity deliberately instead of serializing {@link WebhookEvent}
 * directly — the same projection rule {@code AuditLogDto} follows — so
 * the wire shape stays stable even if the ledger grows internal columns.
 *
 * <p>What is exposed: the row id, the PSP event id, the event type, the
 * PSP reference the delivery carried (a capture or refund id, #114),
 * the reconciliation {@link WebhookEvent.Outcome} and the received-at
 * timestamp ({@code createdAt}).
 *
 * <p>What is NOT exposed, by design: there is no order id on this row —
 * the ledger deliberately carries no order FK (see {@link WebhookEvent});
 * the PSP reference is the join key an operator uses against the orders
 * table. The raw delivery body and its signature are never stored in
 * the ledger at all, so there is nothing of either to leak here.
 */
public record WebhookEventDto(long id, String eventId, String eventType,
                              String pspReference, WebhookEvent.Outcome outcome,
                              Instant createdAt) {

  public static WebhookEventDto of(WebhookEvent row) {
    return new WebhookEventDto(row.getId(), row.getEventId(), row.getEventType(),
        row.getPspReference(), row.getOutcome(), row.getCreatedAt());
  }
}
