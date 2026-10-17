package com.toni.marketplace.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Auth-domain Micrometer counters (backlog #79), exported on
 * {@code /actuator/prometheus} (backlog #57) alongside the JVM metrics.
 *
 * <ul>
 *   <li>{@code auth.login.success} — a password was verified at login.
 *       For a TOTP-enabled account this fires when the password stage
 *       passes and the 2FA challenge is issued (#78); completing the
 *       challenge is not counted separately.</li>
 *   <li>{@code auth.login.failure} — a login was rejected for an unknown
 *       identifier or a wrong password (the identical-401 pair, #60).
 *       Attempts against an already-locked account count neither: the
 *       lock was already reported when it triggered.</li>
 *   <li>{@code auth.lockout.triggered} — a failed login crossed the
 *       per-account threshold and locked the account (#60). Fires once
 *       per lock transition, not per locked attempt.</li>
 *   <li>{@code auth.refresh.theft_detected} — refresh-token theft
 *       detection revoked a user's sessions (#39/#74). Counted at the
 *       same single choke point that WARN-logs the revocation, so the
 *       counter and the log line can never disagree.</li>
 *   <li>{@code auth.password.changed} — a password change completed
 *       (#38). Rejected attempts (wrong current password, reuse, weak
 *       new password) do not count.</li>
 * </ul>
 *
 * <p>Latency timers (backlog #153) live here too, so the counters and
 * the timers cannot drift apart:
 * <ul>
 *   <li>{@code auth.login.duration} — one sample per {@code login} call:
 *       {@code outcome=success} when the call returns normally (a token
 *       pair, or a TOTP challenge — the same boundary as
 *       {@code auth.login.success}), {@code outcome=failure} when it
 *       throws. Login has no idempotent-replay path, so every call
 *       samples exactly once.</li>
 *   <li>{@code auth.refresh.duration} — one sample per {@code refresh}
 *       call, success on a completed rotation, failure when rotation
 *       throws (unknown / replayed-after-grace token). A grace-window
 *       replay is a completed rotation here, not a no-op replay, so it
 *       samples as success.</li>
 * </ul>
 *
 * <p>The event is encoded in the counter name itself — deliberately no
 * tags carrying usernames, ids, or IPs: tag values would be both a
 * cardinality hazard and a PII leak into the metrics store. The timers
 * carry exactly one tag, {@code outcome} ({@code success} /
 * {@code failure}), for the same reason. Honest scope: these meters
 * exist and are scrapeable; alert rules over them are provided in
 * docs/alert-rules.yml with docs/runbook.md (loaded by an operator's
 * own Prometheus — no Alertmanager is deployed or bundled; see the
 * README observability row).
 */
@Component
public class AuthMetrics {

  private final MeterRegistry registry;
  private final Counter loginSuccess;
  private final Counter loginFailure;
  private final Counter lockoutTriggered;
  private final Counter refreshTheftDetected;
  private final Counter passwordChanged;
  private final Timer loginSuccessTimer;
  private final Timer loginFailureTimer;
  private final Timer refreshSuccessTimer;
  private final Timer refreshFailureTimer;

  public AuthMetrics(MeterRegistry registry) {
    MeterRegistry reg = registry != null ? registry : new SimpleMeterRegistry();
    this.registry = reg;
    this.loginSuccess = Counter.builder("auth.login.success")
        .description("Logins whose password verified (TOTP challenge issuance included)")
        .register(reg);
    this.loginFailure = Counter.builder("auth.login.failure")
        .description("Logins rejected for an unknown identifier or a wrong password")
        .register(reg);
    this.lockoutTriggered = Counter.builder("auth.lockout.triggered")
        .description("Per-account login lockouts triggered by consecutive failures")
        .register(reg);
    this.refreshTheftDetected = Counter.builder("auth.refresh.theft_detected")
        .description("Refresh-token theft-detection revocations")
        .register(reg);
    this.passwordChanged = Counter.builder("auth.password.changed")
        .description("Completed password changes")
        .register(reg);
    this.loginSuccessTimer = Timer.builder("auth.login.duration")
        .tag("outcome", "success")
        .description("Login call latency, successful calls")
        .register(reg);
    this.loginFailureTimer = Timer.builder("auth.login.duration")
        .tag("outcome", "failure")
        .description("Login call latency, rejected calls")
        .register(reg);
    this.refreshSuccessTimer = Timer.builder("auth.refresh.duration")
        .tag("outcome", "success")
        .description("Refresh-rotation call latency, successful calls")
        .register(reg);
    this.refreshFailureTimer = Timer.builder("auth.refresh.duration")
        .tag("outcome", "failure")
        .description("Refresh-rotation call latency, rejected calls")
        .register(reg);
  }

  /**
   * Counters on a throwaway registry — for code constructed outside
   * Spring (direct unit tests) that does not assert on metrics. The
   * increments still happen; they are simply never scraped.
   */
  public static AuthMetrics noop() {
    return new AuthMetrics(new SimpleMeterRegistry());
  }

  public void loginSuccess() {
    loginSuccess.increment();
  }

  public void loginFailure() {
    loginFailure.increment();
  }

  public void lockoutTriggered() {
    lockoutTriggered.increment();
  }

  public void refreshTheftDetected() {
    refreshTheftDetected.increment();
  }

  public void passwordChanged() {
    passwordChanged.increment();
  }

  /** Starts a login timer sample; stop it exactly once via {@link #stopLoginTimer}. */
  public Timer.Sample startLoginTimer() {
    return Timer.start(registry);
  }

  /** Stops a login sample on the success or failure timer, per {@code success}. */
  public void stopLoginTimer(Timer.Sample sample, boolean success) {
    if (sample != null) {
      sample.stop(success ? loginSuccessTimer : loginFailureTimer);
    }
  }

  /** Starts a refresh timer sample; stop it exactly once via {@link #stopRefreshTimer}. */
  public Timer.Sample startRefreshTimer() {
    return Timer.start(registry);
  }

  /** Stops a refresh sample on the success or failure timer, per {@code success}. */
  public void stopRefreshTimer(Timer.Sample sample, boolean success) {
    if (sample != null) {
      sample.stop(success ? refreshSuccessTimer : refreshFailureTimer);
    }
  }
}
