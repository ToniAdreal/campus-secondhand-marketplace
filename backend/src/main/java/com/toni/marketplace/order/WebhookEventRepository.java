package com.toni.marketplace.order;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

  Optional<WebhookEvent> findByEventId(String eventId);

  boolean existsByEventId(String eventId);

  long countByOutcome(WebhookEvent.Outcome outcome);
}
