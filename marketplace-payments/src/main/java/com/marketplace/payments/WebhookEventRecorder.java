package com.marketplace.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplication gate for provider webhook events (CodeRabbit #241: the
 * findByEventId-then-save sequence was check-then-act — two concurrent
 * deliveries of the same event could both pass the lookup and both dispatch
 * the payment transition, the loser then failing on the unique
 * {@code event_id} write with a 5xx).
 *
 * <p>{@link #record} runs in its own transaction ({@code REQUIRES_NEW}) and
 * flushes the insert immediately, so the unique index — not a post-commit
 * constraint violation — decides which concurrent delivery owns the event.
 * The losing insert surfaces as {@link DataIntegrityViolationException} at
 * this component's transactional boundary (a PostgreSQL transaction is
 * aborted after a failed statement, so the exception must be caught OUTSIDE
 * the boundary — in {@code PaymentsService.handleVerifiedWebhook} — where the
 * caller's transaction is still clean) and is answered as "already
 * processed", never a 5xx.</p>
 *
 * <p>{@link #delete} is the compensating action: the event row is committed
 * BEFORE the caller dispatches, so a dispatch that fails and rolls back the
 * caller's transaction must also remove the row — otherwise the provider's
 * retry would hit the dedup gate for an event that was never processed
 * (recorded-and-lost, the exact hazard CodeRabbit flagged on
 * {@code payment_intent.succeeded}).</p>
 */
@Component
class WebhookEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventRecorder.class);

    private final PaymentWebhookEventRepository repository;

    WebhookEventRecorder(PaymentWebhookEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts the event row and COMMITS it before returning — the caller may
     * only dispatch after this succeeds. The unique event_id index makes the
     * insert the serialization point between concurrent deliveries.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String provider, String eventId, String eventType) {
        repository.saveAndFlush(PaymentWebhookEvent.create(provider, eventId, eventType));
        log.debug("Recorded webhook event {} ({} {}) as the dedup gate", eventId, provider, eventType);
    }

    /**
     * Compensating delete for a dispatch that failed after the row committed.
     * Runs in its own transaction: the caller's transaction is already
     * rollback-only at this point.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String eventId) {
        repository.deleteByEventId(eventId);
        log.info("Removed webhook event {} after a failed dispatch — the provider retry re-processes it",
                eventId);
    }
}
