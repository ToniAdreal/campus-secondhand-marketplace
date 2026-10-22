package com.toni.marketplace.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

  /** Every attempt against one order, newest first. */
  List<PaymentAttempt> findByOrderIdOrderByCreatedAtDescIdDesc(long orderId);

  /** The most recent attempt against one order, if any. */
  Optional<PaymentAttempt> findFirstByOrderIdOrderByCreatedAtDescIdDesc(long orderId);

  /** The most recent DECLINED attempt against one order, if any. */
  Optional<PaymentAttempt> findFirstByOrderIdAndOutcomeOrderByCreatedAtDescIdDesc(
      long orderId, PaymentAttempt.Outcome outcome);

  long countByOrderIdAndOutcome(long orderId, PaymentAttempt.Outcome outcome);

  /**
   * ADMIN payment-attempt viewer (backlog #211): the optional outcome
   * filter is an exact match — a {@code null} parameter disables it.
   * The JPQL is a fixed string with a bound parameter only; the filter
   * value arrives as an allowlisted enum constant, never as query text.
   * Mirrors {@link WebhookEventRepository#searchWebhookEvents}.
   */
  @Query("select a from PaymentAttempt a where (:outcome is null or a.outcome = :outcome)")
  Page<PaymentAttempt> searchAttempts(@Param("outcome") PaymentAttempt.Outcome outcome,
                                      Pageable pageable);
}
