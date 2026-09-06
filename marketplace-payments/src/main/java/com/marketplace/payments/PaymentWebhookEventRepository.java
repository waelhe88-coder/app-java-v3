package com.marketplace.payments;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID>, RevisionRepository<PaymentWebhookEvent, UUID, Integer> {
    Optional<PaymentWebhookEvent> findByEventId(String eventId);

    /**
     * Compensating delete used by {@link WebhookEventRecorder#delete} after a
     * failed dispatch — removes the dedup row so the provider retry
     * re-processes the event (CodeRabbit #241).
     */
    void deleteByEventId(String eventId);
}
