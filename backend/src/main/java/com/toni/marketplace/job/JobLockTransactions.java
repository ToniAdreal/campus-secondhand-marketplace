package com.toni.marketplace.job;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lock table's transactional steps (backlog #215), each in its own
 * {@code REQUIRES_NEW} transaction so a lease commits the moment it is
 * taken — independently of whatever transaction the guarded job body is
 * about to run (or is already running: two of the six jobs are
 * {@code @Transactional} themselves, and a lease that only became
 * visible at their commit would guard nothing).
 *
 * <p>Acquisition is two steps, in two transactions on purpose:
 * <ol>
 *   <li>{@link #insertLease} — the fast path. When no row exists the
 *       insert commits and the caller holds the lease. When a row
 *       exists the primary key rejects the insert, that transaction
 *       rolls back cleanly, and the constraint failure propagates to
 *       {@link JobLockGuard}, which alone decides what it means.</li>
 *   <li>{@link #stealExpiredLease} — the recovery path, a separate
 *       transaction so it never runs inside the failed insert's
 *       rolled-back state: one conditional update that matches only an
 *       expired lease.</li>
 * </ol>
 * Release is a third step ({@link #releaseLease}), again
 * {@code REQUIRES_NEW}, so it commits even when the job body's own
 * transaction rolled back — a failed run must free its job, never
 * wedge it behind its own lease.
 */
@Component
public class JobLockTransactions {

  private final JobLockRepository locks;

  public JobLockTransactions(JobLockRepository locks) {
    this.locks = locks;
  }

  /**
   * Inserts a fresh lease row (a native INSERT via
   * {@link JobLockRepository#insertLease} — {@code save()} on an
   * assigned primary key would merge over the holder's row instead of
   * colliding with it).
   *
   * @throws org.springframework.dao.DataIntegrityViolationException when
   *     a row for {@code name} already exists (live or expired — the
   *     caller then tries the steal).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void insertLease(String name, String lockedBy, Instant lockedUntil) {
    locks.insertLease(name, lockedBy, lockedUntil);
  }

  /** Steals the lease iff its stored deadline has passed. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean stealExpiredLease(String name, String lockedBy,
                                   Instant lockedUntil, Instant now) {
    return locks.stealIfExpired(name, lockedBy, lockedUntil, now) == 1;
  }

  /** Deletes the caller's own lease row; another holder's row is untouched. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseLease(String name, String lockedBy) {
    locks.deleteByNameAndLockedBy(name, lockedBy);
  }
}
