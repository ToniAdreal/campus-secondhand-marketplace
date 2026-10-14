package com.toni.marketplace.moderation;

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
 * One user's report of one listing (backlog #160, Flyway V31).
 *
 * <p>Reporter and listing references are plain ids (not
 * {@code @ManyToOne}), mirroring {@code Notification} and the audit log:
 * the read path is the ADMIN queue's paginated projection, never
 * association fetching, and the migration declares no foreign keys on
 * purpose — a report is moderation evidence, and deleting a listing or
 * disabling an account must never be blocked by (or silently erase) the
 * reports filed against it.
 *
 * <p>The status machine is minimal: {@link ReportStatus#OPEN} →
 * {@link ReportStatus#DISMISSED} / {@link ReportStatus#ACTIONED}, both
 * terminal. Transitions live in {@link ListingReportService}; this
 * entity only carries the row.
 */
@Entity
@Table(name = "listing_report")
public class ListingReport {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "reporter_id", nullable = false)
  private Long reporterId;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private ReportReason reason;

  /** Optional free-text detail from the reporter; never required. */
  @Column(length = 1000)
  private String note;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private ReportStatus status = ReportStatus.OPEN;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ListingReport() {}

  public ListingReport(Long reporterId, Long itemId, ReportReason reason, String note) {
    this.reporterId = reporterId;
    this.itemId = itemId;
    this.reason = reason;
    this.note = note;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getReporterId() { return reporterId; }
  public Long getItemId() { return itemId; }
  public ReportReason getReason() { return reason; }
  public String getNote() { return note; }
  public ReportStatus getStatus() { return status; }
  public void setStatus(ReportStatus status) { this.status = status; }
  public Instant getCreatedAt() { return createdAt; }
}
