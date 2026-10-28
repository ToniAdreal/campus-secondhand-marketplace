package com.toni.marketplace.review;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.reviews.*}: tuning knobs for the review domain
 * (backlog #254), mirroring {@code MessageProperties} (#206).
 *
 * <ul>
 *   <li>{@code edit-window}: how long after writing the buyer may edit
 *       the review (PUT /api/reviews/{id}). Default 15 minutes — long
 *       enough for a typo, short enough that a read review cannot be
 *       quietly rewritten after the seller has relied on it. An edit
 *       landing exactly at {@code createdAt + edit-window} is
 *       accepted; later is 422. Once the seller has responded, edits
 *       stop regardless of the window (the response must never end up
 *       answering a changed review), so the window is not a promise
 *       that an edit will succeed for its whole length.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.reviews")
public class ReviewProperties {

  /** How long after writing the buyer may still edit the review. */
  private Duration editWindow = Duration.ofMinutes(15);

  public Duration getEditWindow() {
    return editWindow;
  }

  public void setEditWindow(Duration editWindow) {
    this.editWindow = editWindow;
  }
}
