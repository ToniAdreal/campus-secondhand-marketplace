package com.toni.marketplace.dispute;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.disputes.*}: tuning knobs for the order-dispute domain
 * (backlog #169).
 *
 * <ul>
 *   <li>{@code window}: how long after the order's PAID / SHIPPED /
 *   COMPLETED transition the buyer may still open a dispute. Default 7 days.
 *   Measured from the transition instant (backlog #418): {@code paidAt} for
 *   PAID/SHIPPED, {@code completedAt} for COMPLETED — with an
 *   {@code updatedAt} fallback only for rows transitioned before those
 *   columns existed, so a legacy dispute is judged by its last status
 *   change rather than refused outright.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.disputes")
public class DisputeProperties {

  /** How long after the PAID/SHIPPED/COMPLETED transition a dispute may be opened. */
  private Duration window = Duration.ofDays(7);

  public Duration getWindow() {
    return window;
  }

  public void setWindow(Duration window) {
    this.window = window;
  }
}
