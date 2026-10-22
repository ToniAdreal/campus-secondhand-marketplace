package com.toni.marketplace.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One capture attempt against an order (backlog #211, Flyway V60).
 * Appended by {@link PaymentAttemptRecorder}: a DECLINED row is written
 * in a {@code REQUIRES_NEW} transaction so it survives the pay
 * transaction's rollback (the decline exception is exactly what rolls
 * that transaction back), while a SUCCEEDED row joins the capture
 * transaction so a pay that later rolls back (e.g. the 409 flush) leaves
 * no success claim behind.
 *
 * <p>The row carries the order id as a plain column (no FK — like
 * {@link WebhookEvent} this is an audit ledger, not a second source of
 * truth for order state), the attempt {@link Outcome}, and the
 * order-scoped PSP idempotency key the attempt ran under (backlog #65 —
 * an internal correlation id an operator may see). The buyer's payment
 * token is never stored, in any column, in any form: there is
 * deliberately no field that could hold it.
 */
@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

  /** Outcome of one capture attempt (backlog #211). */
  public enum Outcome {
    SUCCEEDED,
    DECLINED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The order the capture was attempted against (plain id, no FK). */
  @Column(name = "order_id", nullable = false)
  private long orderId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private Outcome outcome;

  /** The order-scoped PSP idempotency key of the attempt (backlog #65). */
  @Column(name = "idempotency_key", length = 64)
  private String idempotencyKey;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PaymentAttempt() {}

  public PaymentAttempt(long orderId, Outcome outcome, String idempotencyKey) {
    this.orderId = orderId;
    this.outcome = outcome;
    this.idempotencyKey = idempotencyKey;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public long getOrderId() { return orderId; }
  public Outcome getOutcome() { return outcome; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public Instant getCreatedAt() { return createdAt; }
}
