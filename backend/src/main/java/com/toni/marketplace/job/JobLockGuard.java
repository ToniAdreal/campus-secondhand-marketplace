package com.toni.marketplace.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Single-instance guard for the six mutating {@code @Scheduled} jobs
 * (backlog #215): pending-order expiry, offer expiry, saved-search
 * matching, the nightly data-retention purge, listing expiry and the
 * orphan-upload sweep. Before this guard, a second backend instance (a
 * rolling-deploy overlap is enough) double-ran every one of them —
 * duplicate saved-search notifications were the user-visible tip, and
 * each job's safety was an accident of its own queries.
 *
 * <p>The guard is a database lease (Flyway V61 {@code job_lock}: name,
 * lockedBy, lockedUntil — three columns, no new dependency; ShedLock
 * would do the same job, but the semantics here are small enough to own
 * and pin in tests):
 *
 * <ul>
 *   <li><b>Acquire</b> ({@link #tryAcquire}) inserts the job's row; the
 *       primary key makes exactly one racer win. If a row already
 *       exists, the caller may only <em>steal</em> it once its deadline
 *       has passed, via one conditional update that likewise resolves
 *       competing stealers to a single winner. Both steps commit in
 *       their own transactions ({@link JobLockTransactions}), before
 *       the job body runs.</li>
 *   <li><b>Skip</b> — a caller that cannot acquire does not run the
 *       body at all: it logs at debug and increments the per-job
 *       {@code scheduler.jobs.skipped} counter (tag {@code job} — the
 *       job name is a bounded, PII-free tag value, mirroring the
 *       metrics discipline of {@code OrderMetrics}). The job's next
 *       scheduled firing retries as normal.</li>
 *   <li><b>Release</b> ({@link #release}) deletes only the caller's own
 *       row, from the job's {@code finally} — a body that throws still
 *       releases, so a failing job retries at its next cadence instead
 *       of wedging. When the caller runs inside a transaction (the
 *       retention purge and the saved-search matcher are
 *       {@code @Transactional}), the release is deferred to that
 *       transaction's completion, so the lease is held until the body's
 *       work is actually committed (or rolled back) and no second
 *       instance can start on half-visible state. A holder that
 *       <em>crashes</em> before releasing leaves its row behind, and
 *       the lease deadline is the recovery: at most
 *       {@code app.jobs.lock-lease} (default 10 minutes) later the row
 *       is stealable.</li>
 * </ul>
 *
 * <p>Honest scope: this is mutual exclusion between instances sharing
 * one database, judged by each instance's own clock — it assumes the
 * instances' clocks roughly agree (the same assumption every expiry
 * job in this codebase already makes), and it does not fence a holder
 * that outlives its own lease (a run longer than the lease can be
 * overlapped by a stealer; every guarded job stays batch-tolerant and
 * per-row idempotent exactly as before, so an overlap degrades to the
 * pre-#215 behaviour, never below it). The SSE heartbeat in
 * {@code NotificationStreamService} is deliberately NOT guarded: it
 * mutates nothing outside its own JVM's connection registry, and
 * every instance must send its own.
 */
@Service
public class JobLockGuard {

  private static final Logger log = LoggerFactory.getLogger(JobLockGuard.class);

  private final JobLockTransactions transactions;
  private final Clock clock;
  private final JobLockProperties props;
  private final MeterRegistry registry;
  private final String instanceId;
  private final boolean disabled;
  private final Map<String, Counter> skippedCounters = new ConcurrentHashMap<>();

  @Autowired
  public JobLockGuard(JobLockTransactions transactions, Clock clock,
                      JobLockProperties props, MeterRegistry registry) {
    this(transactions, clock, props, registry, defaultInstanceId(), false);
  }

  /** Full constructor for tests: two guards, two instance ids, one database. */
  public JobLockGuard(JobLockTransactions transactions, Clock clock,
                      JobLockProperties props, MeterRegistry registry,
                      String instanceId) {
    this(transactions, clock, props, registry, instanceId, false);
  }

  private JobLockGuard(JobLockTransactions transactions, Clock clock,
                       JobLockProperties props, MeterRegistry registry,
                       String instanceId, boolean disabled) {
    this.transactions = transactions;
    this.clock = clock;
    this.props = props;
    this.registry = registry != null ? registry : new SimpleMeterRegistry();
    this.instanceId = instanceId;
    this.disabled = disabled;
  }

  /**
   * A guard that always acquires and never touches a database — the
   * pre-#215 behaviour, kept for the jobs' direct-construction unit
   * tests, which pin body logic against mocked collaborators and have
   * no database to lease from. Production wiring always injects the
   * real bean.
   */
  public static JobLockGuard disabled() {
    return new JobLockGuard(null, null, null, new SimpleMeterRegistry(),
        "disabled", true);
  }

  /**
   * Tries to take {@code jobName}'s lease for this instance.
   *
   * @return true when the caller holds the lease and must run the body
   *     (and then call {@link #release}); false when another instance
   *     holds a live lease — the run is skipped, logged and counted.
   */
  public boolean tryAcquire(String jobName) {
    if (disabled) {
      return true;
    }
    Instant now = clock.instant();
    Instant until = now.plus(props.getLockLease());
    try {
      transactions.insertLease(jobName, instanceId, until);
      return true;
    } catch (DataIntegrityViolationException e) {
      // A row already exists (live or expired): fall through to the
      // steal, which is the only other way in.
    }
    if (transactions.stealExpiredLease(jobName, instanceId, until, now)) {
      return true;
    }
    skippedCounters.computeIfAbsent(jobName, name -> Counter.builder("scheduler.jobs.skipped")
            .tag("job", name)
            .description("Scheduled job runs skipped because another instance holds the lease")
            .register(registry))
        .increment();
    log.debug("skipping {} run: another instance holds the lease", jobName);
    return false;
  }

  /**
   * Releases {@code jobName}'s lease if this instance holds it. Inside
   * an active transaction the actual delete is deferred until that
   * transaction completes (commit or rollback — either way the body is
   * finished); outside one it happens immediately. Only the holder's
   * own row can be deleted, so releasing a lease that was stolen after
   * expiring is a harmless no-op.
   */
  public void release(String jobName) {
    if (disabled) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      String holder = instanceId;
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          transactions.releaseLease(jobName, holder);
        }
      });
    } else {
      transactions.releaseLease(jobName, instanceId);
    }
  }

  /** This instance's lease-holder id (host + per-JVM random suffix). */
  public String instanceId() {
    return instanceId;
  }

  private static String defaultInstanceId() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      host = "unknown-host";
    }
    return host + "-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
