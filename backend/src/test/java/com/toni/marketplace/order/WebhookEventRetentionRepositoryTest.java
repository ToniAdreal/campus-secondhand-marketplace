package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

/**
 * Slice tests for the retention query behind the nightly webhook-event
 * purge (backlog #152): selection is by {@code created_at} only — rows of
 * every outcome age out together once they predate the cut-off, and a row
 * inside the window is never selected, so the purge cannot erase one
 * specific delivery while keeping its neighbours. Rows are persisted
 * "now" (the entity stamps {@code createdAt} on persist), so the cut-off
 * is moved around the rows' actual creation instants instead of the
 * other way round.
 */
@DataJpaTest
class WebhookEventRetentionRepositoryTest {

  @Autowired
  private WebhookEventRepository webhookEvents;

  @Autowired
  private TestEntityManager entityManager;

  @Test
  void selectsOnlyRowsCreatedStrictlyBeforeTheCutoff() {
    WebhookEvent matched = webhookEvents.saveAndFlush(new WebhookEvent(
        "evt_old_matched", "capture.succeeded", "cap_mock_1", WebhookEvent.Outcome.MATCHED));
    WebhookEvent mismatched = webhookEvents.saveAndFlush(new WebhookEvent(
        "evt_old_mismatch", "refund.succeeded", "rfd_mock_1", WebhookEvent.Outcome.MISMATCH));

    // A cut-off exactly at a row's creation instant does not select it:
    // the predicate is strictly-before, so the row ages out on a later
    // run once the cut-off has moved past it. The boundary is read back
    // from the database (entityManager.clear + reload): the in-memory
    // Instant carries more precision than the stored timestamp, so
    // comparing against it would test the rounding, not the predicate.
    entityManager.clear();
    Instant matchedCreatedAt = webhookEvents.findById(matched.getId())
        .orElseThrow().getCreatedAt();
    Instant mismatchedCreatedAt = webhookEvents.findById(mismatched.getId())
        .orElseThrow().getCreatedAt();
    assertThat(webhookEvents.findPurgeableIds(matchedCreatedAt,
        PageRequest.of(0, 500))).isEmpty();

    Instant afterBoth = mismatchedCreatedAt.plusSeconds(1);
    List<Long> ids = webhookEvents.findPurgeableIds(afterBoth, PageRequest.of(0, 500));
    assertThat(ids).containsExactly(matched.getId(), mismatched.getId());
  }

  @Test
  void selectionIgnoresOutcome() {
    // One MATCHED and one MISMATCH delivery, both old: both are
    // selected — there is no predicate by which one delivery could be
    // spared (or targeted) over another.
    WebhookEvent a = webhookEvents.saveAndFlush(new WebhookEvent(
        "evt_a", "capture.succeeded", "cap_mock_2", WebhookEvent.Outcome.MATCHED));
    WebhookEvent b = webhookEvents.saveAndFlush(new WebhookEvent(
        "evt_b", "capture.succeeded", "cap_mock_3", WebhookEvent.Outcome.MISMATCH));

    entityManager.clear();
    Instant cutoff = webhookEvents.findById(b.getId())
        .orElseThrow().getCreatedAt().plusSeconds(60);
    assertThat(webhookEvents.findPurgeableIds(cutoff, PageRequest.of(0, 500)))
        .containsExactly(a.getId(), b.getId());
  }

  @Test
  void emptyTableSelectsNothing() {
    assertThat(webhookEvents.findPurgeableIds(Instant.now(), PageRequest.of(0, 500)))
        .isEmpty();
  }
}
