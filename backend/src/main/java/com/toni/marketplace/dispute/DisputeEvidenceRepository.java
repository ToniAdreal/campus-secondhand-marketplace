package com.toni.marketplace.dispute;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidence, Long> {

  /** One dispute's evidence, oldest first — the thread order the SPA renders. */
  List<DisputeEvidence> findByDisputeIdOrderByCreatedAtAscIdAsc(Long disputeId);

  /**
   * Every stored evidence URL — the orphan sweeper's third reference
   * set (backlog #200 extending #103), so a live evidence file is
   * never swept as an orphan.
   */
  @Query("select e.url from DisputeEvidence e")
  List<String> findAllUrls();
}
