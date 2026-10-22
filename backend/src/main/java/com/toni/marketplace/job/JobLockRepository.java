package com.toni.marketplace.job;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobLockRepository extends JpaRepository<JobLock, String> {

  /**
   * Inserts a fresh lease row — a real SQL INSERT, never
   * {@code JpaRepository.save}: with an assigned primary key, save()
   * would MERGE over an existing row instead of colliding with it, and
   * the collision is the whole mutual-exclusion primitive.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException when
   *     a row for {@code name} already exists.
   */
  @Modifying
  @Query(value = "INSERT INTO job_lock (name, locked_by, locked_until) "
      + "VALUES (:name, :lockedBy, :lockedUntil)", nativeQuery = true)
  void insertLease(@Param("name") String name,
                   @Param("lockedBy") String lockedBy,
                   @Param("lockedUntil") Instant lockedUntil);

  /**
   * Atomically steals an EXPIRED lease (backlog #215): the conditional
   * update matches only when the stored deadline is at or before
   * {@code now}, so two instances racing to steal the same dead lease
   * resolve to exactly one updated row — the loser updates zero rows and
   * knows it lost. A live lease (deadline in the future) never matches,
   * whoever asks.
   *
   * @return 1 when the caller now holds the lease, 0 when it does not.
   */
  @Modifying
  @Query("update JobLock l set l.lockedBy = :lockedBy, l.lockedUntil = :lockedUntil "
      + "where l.name = :name and l.lockedUntil <= :now")
  int stealIfExpired(@Param("name") String name,
                     @Param("lockedBy") String lockedBy,
                     @Param("lockedUntil") Instant lockedUntil,
                     @Param("now") Instant now);

  /**
   * Holder-only release (backlog #215): the delete matches the holder id
   * as well as the name, so an instance can never release a lease another
   * instance stole after its own deadline passed.
   *
   * @return 1 when the caller's row was deleted, 0 otherwise.
   */
  @Modifying
  @Query("delete from JobLock l where l.name = :name and l.lockedBy = :lockedBy")
  int deleteByNameAndLockedBy(@Param("name") String name,
                              @Param("lockedBy") String lockedBy);
}
