package com.toni.marketplace.order;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

  Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
