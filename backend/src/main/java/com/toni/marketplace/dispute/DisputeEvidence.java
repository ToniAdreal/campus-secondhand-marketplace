package com.toni.marketplace.dispute;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One evidence photo attached to an order dispute (backlog #200,
 * Flyway V55).
 *
 * <p>Dispute/uploader references are plain ids, mirroring
 * {@link OrderDispute}: the read path is the dispute's own evidence
 * list, never association fetching. {@code url} stores the public path
 * ({@code /uploads/<uuid>.<ext>}) returned by
 * {@code ImageStorageService.store} — the file itself lives on disk
 * under {@code app.uploads.dir} and is served by the
 * {@code /uploads/**} static mapping, exactly like a listing photo.
 * The upload went through that service, so the magic-byte (#116),
 * metadata-strip (#185) and size gates have already applied before
 * this row exists.
 */
@Entity
@Table(name = "dispute_evidence")
public class DisputeEvidence {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "dispute_id", nullable = false)
  private Long disputeId;

  @Column(name = "uploader_id", nullable = false)
  private Long uploaderId;

  /** Public URL path of the stored file ("/uploads/<uuid>.<ext>"). */
  @Column(nullable = false, length = 1024)
  private String url;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected DisputeEvidence() {}

  public DisputeEvidence(Long disputeId, Long uploaderId, String url) {
    this.disputeId = disputeId;
    this.uploaderId = uploaderId;
    this.url = url;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getDisputeId() { return disputeId; }
  public Long getUploaderId() { return uploaderId; }
  public String getUrl() { return url; }
  public Instant getCreatedAt() { return createdAt; }
}
