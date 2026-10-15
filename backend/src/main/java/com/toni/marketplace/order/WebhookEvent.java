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
 * One accepted mock-PSP webhook delivery (backlog #140, Flyway V28).
 * Appended by {@link WebhookEventRecorder} in its own {@code REQUIRES_NEW}
 * transaction (backlog #161 — the split is what lets a concurrent
 * duplicate's insert failure be answered with the deduped 200 instead of
 * a rollback-only 500), keyed on the unique {@code eventId}: a replayed
 * delivery finds this row and is answered 200 without re-applying or
 * re-counting anything. Rows are append-only with exactly one exception
 * (backlog #170): an ADMIN replay attempt updates the replay bookkeeping
 * ({@code replayCount} / {@code lastReplayAt}, Flyway V38), and a replay
 * that applies a previously mismatched event flips {@code outcome} to
 * {@link Outcome#MATCHED} — the one outcome rewrite, performed only by
 * {@code WebhookEventRecorder.applyOutcome}.
 *
 * <p>The webhook never moves money state by itself — {@code outcome} only
 * records whether the PSP reference it carried named an order already in
 * the matching state ({@link Outcome#MATCHED}) or not
 * ({@link Outcome#MISMATCH}: unknown reference, or an order not yet / no
 * longer in that state). Mismatches are an operational signal (a missed
 * synchronous transition, a PSP retrying out of order), never a trigger
 * to mutate the order from the callback.
 */
@Entity
@Table(name = "webhook_event")
public class WebhookEvent {

  /** Reconciliation outcome of one webhook delivery (backlog #140). */
  public enum Outcome {
    MATCHED,
    MISMATCH
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The PSP's event id — the idempotency key of the delivery. */
  @Column(name = "event_id", nullable = false, length = 128, unique = true)
  private String eventId;

  /** Event type as delivered, e.g. {@code capture.succeeded}. */
  @Column(name = "event_type", nullable = false, length = 64)
  private String eventType;

  /** PSP reference carried by the event (a capture or refund id, #114). */
  @Column(name = "psp_reference", length = 64)
  private String pspReference;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private Outcome outcome;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** ADMIN replay attempts against this row (backlog #170, Flyway V38). */
  @Column(name = "replay_count", nullable = false)
  private int replayCount;

  /** When the last ADMIN replay attempt happened (backlog #170). */
  @Column(name = "last_replay_at")
  private Instant lastReplayAt;

  protected WebhookEvent() {}

  public WebhookEvent(String eventId, String eventType, String pspReference, Outcome outcome) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.pspReference = pspReference;
    this.outcome = outcome;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  /** One ADMIN replay attempt (backlog #170): counted even when refused. */
  public void recordReplay(Instant at) {
    this.replayCount++;
    this.lastReplayAt = at;
  }

  /**
   * The one outcome rewrite (backlog #170): a replay that re-reconciles a
   * previously mismatched event to {@link Outcome#MATCHED} stores the new
   * verdict, so a second replay is the deduped no-op instead of applying
   * twice. Never called with {@link Outcome#MISMATCH} — a replay that
   * still does not reconcile leaves the stored outcome untouched.
   */
  public void applyOutcome(Outcome outcome) {
    this.outcome = outcome;
  }

  public Long getId() { return id; }
  public String getEventId() { return eventId; }
  public String getEventType() { return eventType; }
  public String getPspReference() { return pspReference; }
  public Outcome getOutcome() { return outcome; }
  public Instant getCreatedAt() { return createdAt; }
  public int getReplayCount() { return replayCount; }
  public Instant getLastReplayAt() { return lastReplayAt; }
}
