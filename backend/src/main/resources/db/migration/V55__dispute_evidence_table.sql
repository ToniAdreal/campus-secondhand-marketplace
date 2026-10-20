-- V55: dispute evidence photos (backlog #200). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- Disputes were text-only in v1 (#169): the buyer described the damage
-- but could not show it. One row per evidence photo attached to a
-- dispute. dispute_id references the dispute row (ON DELETE CASCADE —
-- evidence has no meaning without its dispute, mirroring the
-- item_photo gallery's cascade); uploader_id is a plain id mirroring
-- order_dispute's own plain-id style (the uploader is one of the
-- dispute's buyer/seller or an ADMIN, enforced in DisputeEvidenceService,
-- not by the schema).
--
-- url stores the public path (/uploads/<uuid>.<ext>) returned by
-- ImageStorageService — the same value item.photo_url and item_photo
-- store — so the file is served by the existing /uploads/** mapping,
-- deleted through the same best-effort delete seam, and protected from
-- the nightly orphan sweep by joining its reference set (#103).
CREATE TABLE dispute_evidence (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dispute_id BIGINT NOT NULL,
  uploader_id BIGINT NOT NULL,
  url VARCHAR(1024) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dispute_evidence_dispute FOREIGN KEY (dispute_id)
    REFERENCES order_dispute(id) ON DELETE CASCADE,
  CONSTRAINT fk_dispute_evidence_uploader FOREIGN KEY (uploader_id)
    REFERENCES app_user(id)
);

CREATE INDEX idx_dispute_evidence_dispute ON dispute_evidence(dispute_id, created_at);
