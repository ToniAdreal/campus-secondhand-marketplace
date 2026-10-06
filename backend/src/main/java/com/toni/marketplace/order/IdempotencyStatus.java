package com.toni.marketplace.order;

/**
 * Lifecycle of an idempotency-key record. {@code IN_PROGRESS} means a request
 * reserved the key but has not finished creating its order yet;
 * {@code COMPLETED} carries the created order id and replays it forever;
 * {@code FAILED} means creation threw — the key is burned, the client must
 * retry with a new one.
 */
public enum IdempotencyStatus {
  IN_PROGRESS,
  COMPLETED,
  FAILED
}
