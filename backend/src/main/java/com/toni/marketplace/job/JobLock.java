package com.toni.marketplace.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One scheduler lease row (backlog #215, Flyway V61): at most one row per
 * job name, held by exactly one instance ({@code lockedBy}) until
 * {@code lockedUntil}. The table is the whole lock — there is deliberately
 * no surrogate id and no extra state: the primary key on {@code name} is
 * what makes two racing inserts resolve to exactly one winner, and the
 * {@code lockedUntil} deadline is what makes a crashed holder's lease
 * stealable instead of a permanently wedged job.
 *
 * <p>Rows are created on first acquisition and deleted on release (only
 * by their holder — see {@link JobLockRepository}); a row that survives
 * its holder is not an error, it is an expired-or-live lease whose
 * deadline decides.
 */
@Entity
@Table(name = "job_lock")
public class JobLock {

  /** The job's stable lock name (one of the six services' LOCK_NAME constants). */
  @Id
  @Column(length = 64, nullable = false)
  private String name;

  /** The holding instance's id (host + per-JVM random suffix). */
  @Column(name = "locked_by", length = 128, nullable = false)
  private String lockedBy;

  /** The lease deadline; a steal is only ever allowed at or past it. */
  @Column(name = "locked_until", nullable = false)
  private Instant lockedUntil;

  protected JobLock() {}

  public JobLock(String name, String lockedBy, Instant lockedUntil) {
    this.name = name;
    this.lockedBy = lockedBy;
    this.lockedUntil = lockedUntil;
  }

  public String getName() { return name; }
  public String getLockedBy() { return lockedBy; }
  public Instant getLockedUntil() { return lockedUntil; }
}
