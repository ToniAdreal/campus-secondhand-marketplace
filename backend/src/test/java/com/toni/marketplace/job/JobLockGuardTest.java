package com.toni.marketplace.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Single-instance scheduler guard (backlog #215) against the real H2
 * {@code job_lock} table: two guards with different instance ids share
 * one database — the shape of two backend instances sharing one MySQL —
 * and a {@link MutableClock} drives lease expiry deterministically.
 * Lock names here are test-only, so a real scheduled firing during the
 * suite can never collide with them.
 *
 * <p>Deliberately NOT {@code @Transactional}: the guards' leases must
 * commit in their own {@code REQUIRES_NEW} transactions for the other
 * guard to see them, exactly as in production.
 */
@SpringBootTest
class JobLockGuardTest {

  private static final String JOB = "test-job-lock";

  @Autowired
  private JobLockTransactions transactions;

  @Autowired
  private JobLockRepository locks;

  private MutableClock clock;
  private JobLockProperties props;
  private SimpleMeterRegistry registryA;
  private SimpleMeterRegistry registryB;
  private JobLockGuard instanceA;
  private JobLockGuard instanceB;

  @BeforeEach
  void setUp() {
    locks.deleteAll();
    clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    props = new JobLockProperties();
    registryA = new SimpleMeterRegistry();
    registryB = new SimpleMeterRegistry();
    instanceA = new JobLockGuard(transactions, clock, props, registryA, "instance-A");
    instanceB = new JobLockGuard(transactions, clock, props, registryB, "instance-B");
  }

  private double skippedCount(SimpleMeterRegistry registry) {
    var counter = registry.find("scheduler.jobs.skipped").tag("job", JOB).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void exactlyOneOfTwoCompetingInstancesRunsTheBody() {
    List<String> bodiesRun = new CopyOnWriteArrayList<>();

    if (instanceA.tryAcquire(JOB)) {
      try {
        bodiesRun.add("A");
      } finally {
        // Not released yet while B tries: B must skip, not run.
      }
    }
    if (instanceB.tryAcquire(JOB)) {
      try {
        bodiesRun.add("B");
      } finally {
        instanceB.release(JOB);
      }
    }

    assertThat(bodiesRun).containsExactly("A");
    // The skip is visible: B's per-job counter moved, A's did not.
    assertThat(skippedCount(registryB)).isEqualTo(1.0);
    assertThat(skippedCount(registryA)).isEqualTo(0.0);

    // After A releases, the next firing belongs to whoever asks first.
    instanceA.release(JOB);
    assertThat(instanceB.tryAcquire(JOB)).isTrue();
    instanceB.release(JOB);
    assertThat(locks.findAll()).isEmpty();
  }

  @Test
  void concurrentRacersResolveToExactlyOneHolder() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Boolean> results = new CopyOnWriteArrayList<>();
    List<JobLockGuard> racers = List.of(instanceA, instanceB);
    List<Thread> threads = racers.stream().map(guard -> new Thread(() -> {
      try {
        start.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      results.add(guard.tryAcquire(JOB));
    })).toList();
    threads.forEach(Thread::start);
    start.countDown();
    for (Thread thread : threads) {
      thread.join(10_000);
    }

    // The primary key resolves the insert race: exactly one winner,
    // exactly one lease row, naming the winner as its holder.
    assertThat(results).containsExactlyInAnyOrder(true, false);
    assertThat(locks.findAll()).hasSize(1);
    // Read the holder from the row itself (thread completion order is
    // not racer order) and pin that the loser cannot release the
    // winner's lease.
    JobLock row = locks.findAll().get(0);
    assertThat(row.getLockedBy()).isIn("instance-A", "instance-B");
    JobLockGuard loser = row.getLockedBy().equals("instance-A") ? instanceB : instanceA;
    JobLockGuard holder = row.getLockedBy().equals("instance-A") ? instanceA : instanceB;
    loser.release(JOB);
    assertThat(locks.findAll()).hasSize(1);
    holder.release(JOB);
    assertThat(locks.findAll()).isEmpty();
  }

  @Test
  void expiredLeaseIsStealableButLiveLeaseIsNot() {
    assertThat(instanceA.tryAcquire(JOB)).isTrue();
    // A never releases (a crash): the row stays, with A's deadline.
    assertThat(locks.findAll()).hasSize(1);

    // One minute before the deadline the lease is still A's.
    clock.advance(Duration.ofMinutes(9));
    assertThat(instanceB.tryAcquire(JOB)).isFalse();

    // At the deadline the lease is stealable, and the steal rewrites
    // the row to the stealer with a fresh deadline.
    clock.advance(Duration.ofMinutes(1));
    assertThat(instanceB.tryAcquire(JOB)).isTrue();
    JobLock row = locks.findAll().get(0);
    assertThat(row.getLockedBy()).isEqualTo("instance-B");
    assertThat(row.getLockedUntil())
        .isEqualTo(Instant.parse("2026-01-01T00:10:00Z").plus(props.getLockLease()));

    // The stale holder's late release must not free the stealer's lease.
    instanceA.release(JOB);
    assertThat(locks.findAll()).hasSize(1);
    instanceB.release(JOB);
    assertThat(locks.findAll()).isEmpty();
  }

  @Test
  void aFailureInsideTheBodyStillReleasesTheLease() {
    assertThat(instanceA.tryAcquire(JOB)).isTrue();
    try {
      throw new IllegalStateException("job body blew up");
    } catch (IllegalStateException expected) {
      // The job's own failure handling; the finally below is the
      // pattern every guarded job uses.
    } finally {
      instanceA.release(JOB);
    }

    // No clock advance: the release, not the deadline, freed the job,
    // so the very next firing (either instance) runs immediately.
    assertThat(locks.findAll()).isEmpty();
    assertThat(instanceB.tryAcquire(JOB)).isTrue();
    instanceB.release(JOB);
  }

  @Test
  void defaultLeaseIsTenMinutes() {
    assertThat(new JobLockProperties().getLockLease()).isEqualTo(Duration.ofMinutes(10));
  }
}
