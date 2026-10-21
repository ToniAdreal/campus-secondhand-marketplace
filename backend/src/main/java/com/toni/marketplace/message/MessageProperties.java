package com.toni.marketplace.message;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.messages.*}: tuning knobs for the messaging domain
 * (backlog #206).
 *
 * <ul>
 *   <li>{@code edit-window}: how long after sending the sender may edit
 *   the message (PUT /api/messages/{id}). Default 15 minutes — long
 *   enough to fix a typo or a wrong pickup time, short enough that a
 *   conversation's record cannot be quietly rewritten after the other
 *   party has read and relied on it. An edit at or before
 *   {@code createdAt + edit-window} is accepted; later is 422. Soft
 *   delete (DELETE /api/messages/{id}) is deliberately NOT windowed:
 *   retracting a regretted message has no such reliance argument, and
 *   the tombstone keeps the deletion itself visible.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.messages")
public class MessageProperties {

  /** How long after sending the sender may still edit the message. */
  private Duration editWindow = Duration.ofMinutes(15);

  public Duration getEditWindow() {
    return editWindow;
  }

  public void setEditWindow(Duration editWindow) {
    this.editWindow = editWindow;
  }
}
