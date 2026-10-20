package com.toni.marketplace.dispute;

import java.time.Instant;

/**
 * Public shape of one dispute evidence photo (backlog #200): ids, the
 * uploader and the public {@code /uploads/} URL — no JPA types. The
 * URL is served without auth like every upload (see
 * {@code UploadWebConfig}); the dispute-scoped visibility rule lives
 * on the list endpoint, not on the file URL, mirroring listing photos.
 */
public record DisputeEvidenceDto(
    Long id,
    Long disputeId,
    Long uploaderId,
    String url,
    Instant createdAt) {

  public static DisputeEvidenceDto from(DisputeEvidence evidence) {
    return new DisputeEvidenceDto(
        evidence.getId(),
        evidence.getDisputeId(),
        evidence.getUploaderId(),
        evidence.getUrl(),
        evidence.getCreatedAt());
  }
}
