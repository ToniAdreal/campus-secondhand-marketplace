package com.toni.marketplace.dispute;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.item.ImageStorageService;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dispute evidence photos (backlog #200). Disputes were text-only in
 * v1 (#169) — a buyer disputing a damaged item could describe the
 * damage but not show it. Evidence attaches to an OPEN dispute:
 *
 * <ul>
 *   <li><b>Visibility:</b> only the dispute's buyer, its seller, or an
 *       ADMIN may add or read evidence. Anyone else gets the same 404
 *       as an unknown dispute id, mirroring the receipt's
 *       no-existence-leak discipline (#180) — a dispute id never
 *       confirms it exists to a stranger. Anonymous callers get the
 *       chain's 401 envelope before routing.</li>
 *   <li><b>Upload:</b> the file goes through
 *       {@link ImageStorageService#store} unchanged, so the allowlist,
 *       size cap, magic-byte gate (#116) and metadata strip (#185)
 *       apply exactly as for listing photos; a rejected file is the
 *       same 400 envelope and leaves neither a file nor a row. New
 *       evidence is accepted only while the dispute is OPEN — a
 *       resolved dispute is 422, its record is closed.</li>
 *   <li><b>Removal:</b> the uploader (or an ADMIN) may remove a piece
 *       of evidence; the other party may view but not delete it (403 —
 *       they can see the dispute, so there is no existence to hide).
 *       An evidence id that belongs to another dispute is 404. The
 *       file is deleted best-effort alongside the row delete in the
 *       #40 orphan discipline: a filesystem failure is logged, never
 *       rolled back into the row operation, and the nightly orphan
 *       sweeper (#103, whose reference set now includes evidence
 *       URLs) is the backstop for a file that outlives its row.</li>
 *   <li><b>Download:</b> a party (or an ADMIN) reads one photo through the
 *       authenticated download route (backlog #419), which streams the
 *       stored file with its content type — the same 404 no-leak
 *       discipline as the list: a stranger, an unknown evidence id, an
 *       evidence id from another dispute, or a row whose file is gone
 *       from disk all answer 404. The SPA renders evidence through
 *       this route, never the raw {@code /uploads/} URL; the stored
 *       URL still travels in the list DTO and the files are still
 *       served by the public {@code /uploads/**} mapping, so a party
 *       that holds a URL can still share a direct link — only the
 *       panel no longer exposes one. Downloads write no audit row:
 *       reads are not audited anywhere in this codebase.</li>
 *   <li><b>Audit:</b> upload and removal each write an audit row in the
 *       same transaction as the evidence row itself
 *       ({@code DISPUTE_EVIDENCE_UPLOADED} /
 *       {@code DISPUTE_EVIDENCE_DELETED}, backlog #413) — actor is the
 *       uploader/remover, target is the evidence row id. Before #413 a
 *       deleted photo left no trail at all, which is exactly what an
 *       ADMIN resolving the dispute could not reconstruct. The row
 *       stores the evidence id and nothing of the file (no bytes, no
 *       {@code /uploads/} URL); the dispute id and order id are one
 *       lookup away via the evidence row and the dispute row.</li>
 * </ul>
 */
@Service
public class DisputeEvidenceService {

  private static final Logger log = LoggerFactory.getLogger(DisputeEvidenceService.class);

  private final OrderDisputeRepository disputes;
  private final DisputeEvidenceRepository evidence;
  private final ImageStorageService storage;
  private final AuditService audit;

  public DisputeEvidenceService(OrderDisputeRepository disputes,
                                DisputeEvidenceRepository evidence,
                                ImageStorageService storage,
                                AuditService audit) {
    this.disputes = disputes;
    this.evidence = evidence;
    this.storage = storage;
    this.audit = audit;
  }

  /** Adds one evidence photo to an OPEN dispute (see the class javadoc). */
  @Transactional
  public DisputeEvidenceDto addEvidence(Long callerId, boolean admin,
                                        Long disputeId, MultipartFile file) {
    OrderDispute dispute = requireVisibleDispute(callerId, admin, disputeId);
    if (dispute.getStatus() != DisputeStatus.OPEN) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "evidence can only be added while the dispute is open");
    }
    // Store before saving the row, mirroring ItemService: the storage
    // gates (type/size/magic bytes) throw the 400 envelope before any
    // row exists, and a rejected upload leaves no file behind.
    String url = storage.store(file);
    try {
      DisputeEvidence saved = evidence.save(new DisputeEvidence(disputeId, callerId, url));
      // The audit row joins this transaction (backlog #413): a rolled-back
      // save leaves no row, and a rejected upload never reaches here.
      audit.record(callerId, AuditAction.DISPUTE_EVIDENCE_UPLOADED,
          AuditTargetType.DISPUTE_EVIDENCE, saved.getId());
      return DisputeEvidenceDto.from(saved);
    } catch (RuntimeException e) {
      // The row never landed: the just-stored file is an orphan by
      // construction, so remove it best-effort right here (#40).
      deleteQuietly(url);
      throw e;
    }
  }

  /** Lists one dispute's evidence, oldest first (see the class javadoc). */
  @Transactional(readOnly = true)
  public List<DisputeEvidenceDto> listEvidence(Long callerId, boolean admin, Long disputeId) {
    requireVisibleDispute(callerId, admin, disputeId);
    return evidence.findByDisputeIdOrderByCreatedAtAscIdAsc(disputeId).stream()
        .map(DisputeEvidenceDto::from)
        .toList();
  }

  /**
   * Reads one evidence photo back for the authenticated download route
   * (backlog #419): the caller must be a party to the dispute (same
   * 404 discipline as the list), the evidence id must belong to this
   * dispute, and the stored file must still be on disk — a row whose
   * file is gone is the same 404, never a 500. A disk failure while
   * reading a file that does exist is the 500 envelope: the file is
   * there, the server just could not read it, and that must not be
   * reported as "not found".
   */
  @Transactional(readOnly = true)
  public ImageStorageService.StoredFile downloadEvidence(Long callerId, boolean admin,
                                                         Long disputeId, Long evidenceId) {
    requireVisibleDispute(callerId, admin, disputeId);
    DisputeEvidence row = evidence.findById(evidenceId)
        .filter(e -> e.getDisputeId().equals(disputeId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "dispute evidence not found"));
    try {
      return storage.load(row.getUrl())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
              "dispute evidence not found"));
    } catch (IOException e) {
      log.warn("could not read dispute evidence file {}: {}", row.getUrl(), e.toString());
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "could not read evidence file");
    }
  }

  /** Removes one piece of evidence (see the class javadoc for who may). */
  @Transactional
  public void removeEvidence(Long callerId, boolean admin,
                             Long disputeId, Long evidenceId) {
    requireVisibleDispute(callerId, admin, disputeId);
    DisputeEvidence row = evidence.findById(evidenceId)
        .filter(e -> e.getDisputeId().equals(disputeId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "dispute evidence not found"));
    if (!admin && !row.getUploaderId().equals(callerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the uploader can remove this evidence");
    }
    String url = row.getUrl();
    evidence.delete(row);
    // The audit row joins this transaction (backlog #413) and targets the
    // deleted row's id, so the trail survives the deletion; the best-effort
    // file cleanup below it must not roll the removal back (#40).
    audit.record(callerId, AuditAction.DISPUTE_EVIDENCE_DELETED,
        AuditTargetType.DISPUTE_EVIDENCE, evidenceId);
    // Best-effort file cleanup after the row delete (#40 discipline):
    // a disk failure must not roll the removal back.
    deleteQuietly(url);
  }

  /**
   * The dispute, if the caller may see it at all: buyer, seller or
   * ADMIN. Unknown ids and strangers' disputes are the identical 404.
   */
  private OrderDispute requireVisibleDispute(Long callerId, boolean admin, Long disputeId) {
    OrderDispute dispute = disputes.findById(disputeId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "dispute not found"));
    if (!admin
        && !dispute.getBuyerId().equals(callerId)
        && !dispute.getSellerId().equals(callerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dispute not found");
    }
    return dispute;
  }

  private void deleteQuietly(String url) {
    try {
      storage.delete(url);
    } catch (IOException e) {
      log.warn("could not delete dispute evidence file {}: {}", url, e.toString());
    }
  }
}
