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
 *   Orders carry no {@code paidAt} / {@code completedAt} column, so the
 *   window is measured from the order's {@code updatedAt} — the last
 *   status change, which for a PAID, SHIPPED or COMPLETED order is exactly that
 *   transition (nothing else writes a disputable order while it sits in
 *   any of those states). Documented honestly rather than pretending a
 *   dedicated transition timestamp exists.</li>
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
