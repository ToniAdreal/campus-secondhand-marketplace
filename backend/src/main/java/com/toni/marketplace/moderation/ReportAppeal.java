package com.toni.marketplace.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One seller's appeal of one ACTIONED listing report (backlog #190,
 * Flyway V50).
 *
 * <p>Report and seller references are plain ids (not
 * {@code @ManyToOne}), mirroring {@link ListingReport}: the read paths
 * are the ADMIN queue's and the seller's own paginated projections,
 * never association fetching, and the migration declares no foreign
 * keys on purpose — an appeal is moderation evidence, and deleting a
 * listing or disabling an account must never be blocked by (or
 * silently erase) the appeals filed against its reports.
 * {@code sellerId} is the listing's seller at appeal time (a listing's
 * seller never changes, so the value cannot drift).
 *
 * <p>{@code listingRestored} is written only by an uphold resolution —
 * {@code true} when the listing was still WITHDRAWN and flipped back
 * to AVAILABLE, {@code false} when it had moved on (RESERVED / SOLD /
 * EXPIRED) and was left untouched; it stays null on OPEN and REJECTED
 * rows, mirroring {@code OrderDispute.listingRestored}.
 *
 * <p>Concurrency contract, mirroring {@code OrderDispute}:
 * {@code @Version} optimistic locking — two racing ADMIN resolutions
 * on the same appeal fail fast instead of both applying; the loser is
 * mapped to 409. The DB-generated {@code live_report_id} guard column
 * enforces "one OPEN appeal per report" at the database level.
 */
@Entity
@Table(name = "report_appeal")
public class ReportAppeal {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "report_id", nullable = false)
  private Long reportId;

  @Column(name = "seller_id", nullable = false)
  private Long sellerId;

  /** The seller's reason, free text only (no evidence upload in v1), length-capped at the boundary. */
  @Column(nullable = false, length = 500)
  private String reason;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private ReportAppealStatus status = ReportAppealStatus.OPEN;

  /** Uphold-resolution outcome for the listing; null until (and unless) upheld. */
  @Column(name = "listing_restored")
  private Boolean listingRestored;

  /** Optimistic locking: concurrent resolutions on one appeal fail fast. */
  @Version
  private Long version;

  /** Read-only view of the DB-generated one-open-appeal guard column. */
  @Column(name = "live_report_id", insertable = false, updatable = false)
  private Long liveReportId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** When an ADMIN resolved the appeal; null while OPEN. */
  @Column(name = "resolved_at")
  private Instant resolvedAt;

  protected ReportAppeal() {}

  public ReportAppeal(Long reportId, Long sellerId, String reason) {
    this.reportId = reportId;
    this.sellerId = sellerId;
    this.reason = reason;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getReportId() { return reportId; }
  public Long getSellerId() { return sellerId; }
  public String getReason() { return reason; }
  public ReportAppealStatus getStatus() { return status; }
  public void setStatus(ReportAppealStatus status) { this.status = status; }
  public Boolean getListingRestored() { return listingRestored; }
  public void setListingRestored(Boolean listingRestored) {
    this.listingRestored = listingRestored;
  }
  public Long getVersion() { return version; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getResolvedAt() { return resolvedAt; }
  public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
