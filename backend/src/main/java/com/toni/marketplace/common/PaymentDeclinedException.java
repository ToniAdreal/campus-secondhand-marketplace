package com.toni.marketplace.common;

/**
 * Thrown by the mock PSP seam when a capture is declined (backlog #92).
 * Mapped to {@code 402 Payment Required} in the JSON envelope by
 * {@link GlobalExceptionHandler}. A decline is not an error in the order
 * state machine: the order stays PENDING and the buyer may retry.
 */
public class PaymentDeclinedException extends RuntimeException {

  public PaymentDeclinedException() {
    super("payment declined by the (mock) payment provider");
  }
}
