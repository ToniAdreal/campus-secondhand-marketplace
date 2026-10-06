package com.toni.marketplace.order;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns the idempotency-key lifecycle for {@code POST /api/orders}.
 *
 * <p>Every method runs in its own committed transaction
 * ({@code REQUIRES_NEW}): the reservation must be visible to concurrent
 * requests the moment it commits, and a rollback of the order-creation
 * transaction must never take the key record down with it. The ordering in
 * {@link OrderService#createOrder} is: reserve (own tx) → create order (own
 * tx) → complete (own tx), with {@code fail()} on the error path — so a key
 * is never left IN_PROGRESS by application-level failures, only by a process
 * crash between reserve and complete.
 *
 * <p>Two requests racing with the same key: exactly one insert wins the
 * {@code uq_idem_user_key} unique constraint; the loser gets
 * {@code DataIntegrityViolationException}, whose transaction rolls back
 * harmlessly, and re-reads the winner's committed record via
 * {@link #reread}.
 */
@Service
public class IdempotencyKeyService {

  /** An IN_PROGRESS record older than this is treated as owner-crashed and reclaimed. */
  private static final Duration STALE_AFTER = Duration.ofMinutes(10);

  private final IdempotencyKeyRepository keys;

  public IdempotencyKeyService(IdempotencyKeyRepository keys) {
    this.keys = keys;
  }

  /** Outcome of a key reservation attempt. */
  public sealed interface Reservation permits Reservation.Created, Reservation.Replayed {
    /** Fresh IN_PROGRESS record — the caller now owns the key. */
    record Created(IdempotencyKey record) implements Reservation {}
    /** The key already completed — replay the stored order, create nothing. */
    record Replayed(IdempotencyKey record) implements Reservation {}
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Reservation reserve(Long userId, String key, Long itemId) {
    IdempotencyKey existing = keys.findByUserIdAndIdempotencyKey(userId, key).orElse(null);
    if (existing != null) {
      if (!existing.getItemId().equals(itemId)) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "idempotency key already used with a different item");
      }
      if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
        return new Reservation.Replayed(existing);
      }
      if (existing.getStatus() == IdempotencyStatus.FAILED) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "previous request with this idempotency key failed; retry with a new key");
      }
      // IN_PROGRESS: a live owner is still working — tell the client to
      // retry. A stale owner (crashed between reserve and complete) has its
      // key reclaimed so the retry can proceed. The delete is flushed before
      // the re-insert below: Hibernate runs insertions before deletions
      // within one flush, which would otherwise trip uq_idem_user_key.
      if (existing.getUpdatedAt().isBefore(Instant.now().minus(STALE_AFTER))) {
        keys.delete(existing);
        keys.flush();
      } else {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "request with this idempotency key is already in progress");
      }
    }
    return new Reservation.Created(keys.save(new IdempotencyKey(userId, key, itemId)));
  }

  /**
   * Re-reads the winner's committed record after losing the same-key insert
   * race. The winner's reservation transaction has committed by the time the
   * loser's insert failed, so this always sees a record.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Reservation reread(Long userId, String key, Long itemId) {
    IdempotencyKey rec = keys.findByUserIdAndIdempotencyKey(userId, key)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "idempotency reservation lost"));
    if (!rec.getItemId().equals(itemId)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "idempotency key already used with a different item");
    }
    return switch (rec.getStatus()) {
      case COMPLETED -> new Reservation.Replayed(rec);
      case FAILED -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "previous request with this idempotency key failed; retry with a new key");
      // Winner reserved the key but has not finished: the client retries and
      // lands on the replay path.
      case IN_PROGRESS -> throw new ResponseStatusException(HttpStatus.CONFLICT,
          "request with this idempotency key is already in progress");
    };
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(Long recordId, Long orderId) {
    IdempotencyKey rec = keys.findById(recordId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "idempotency record missing on complete"));
    rec.complete(orderId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(Long recordId) {
    keys.findById(recordId).ifPresent(IdempotencyKey::fail);
  }
}
