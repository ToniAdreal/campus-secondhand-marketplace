package com.toni.marketplace.admin;

import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounded look-back window of the ADMIN operations dashboard (backlog
 * #196). The wire values are exactly {@code 24h}, {@code 7d} and
 * {@code 30d}; anything else is a 400 in the JSON envelope — the set is
 * deliberately a closed enum, never a free-form duration the caller
 * could stretch into an unbounded scan. An omitted parameter defaults
 * to {@link #H24} ("is the marketplace healthy today").
 */
public enum DashboardWindow {
  H24("24h", Duration.ofHours(24)),
  D7("7d", Duration.ofDays(7)),
  D30("30d", Duration.ofDays(30));

  private final String wireValue;
  private final Duration duration;

  DashboardWindow(String wireValue, Duration duration) {
    this.wireValue = wireValue;
    this.duration = duration;
  }

  public String wireValue() {
    return wireValue;
  }

  public Duration duration() {
    return duration;
  }

  /**
   * Parses the {@code ?window=} query value. {@code null}/blank means
   * the parameter was omitted and yields the {@link #H24} default; any
   * other unknown value is a 400 (the envelope handler renders
   * {@link ResponseStatusException}).
   */
  public static DashboardWindow parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return H24;
    }
    for (DashboardWindow window : values()) {
      if (window.wireValue.equals(raw.trim())) {
        return window;
      }
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "window must be one of 24h, 7d, 30d");
  }
}
