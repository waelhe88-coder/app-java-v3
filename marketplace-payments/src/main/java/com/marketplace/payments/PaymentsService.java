package com.marketplace.payments;

import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

import io.micrometer.observation.annotation.Observed;

import java.util.Set;
import java.util.UUID;

@Service
@Transactional
@Validated
public class PaymentsService implements PaymentsSpi {

    private static final Logger log = LoggerFactory.getLogger(PaymentsService.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserProvider currentUserProvider;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final PaymentWebhookSecurity paymentWebhookSecurity;
    private final WebhookEventRecorder webhookEventRecorder;
    private final ObjectProvider<PspChannel> pspChannel;

    public PaymentsService(PaymentIntentRepository paymentIntentRepository,
                           PaymentRepository paymentRepository,
                           PaymentWebhookEventRepository webhookEventRepository,
                           ApplicationEventPublisher eventPublisher,
                           CurrentUserProvider currentUserProvider,
                           BookingParticipantProvider bookingParticipantProvider,
                           PaymentWebhookSecurity paymentWebhookSecurity,
                           WebhookEventRecorder webhookEventRecorder,
                           ObjectProvider<PspChannel> pspChannel) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.eventPublisher = eventPublisher;
        this.currentUserProvider = currentUserProvider;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentWebhookSecurity = paymentWebhookSecurity;
        this.webhookEventRecorder = webhookEventRecorder;
        this.pspChannel = pspChannel;
    }

    public boolean processWebhookEvent(String provider, String eventId, String eventType, String signature) {
        return processWebhookEvent(provider, eventId, eventType, signature, null, null);
    }

    public boolean processWebhookEvent(String provider, String eventId, String eventType, String signature,
                                       UUID paymentIntentId, String externalId) {
        paymentWebhookSecurity.validateSignature(eventId + eventType, signature);
        return handleVerifiedWebhook(provider, eventId, eventType, paymentIntentId, externalId);
    }

    /**
     * Provider-verified webhook dispatch: the signature was verified by the
     * channel itself (Stripe: SDK constructEvent with DEFAULT_TOLERANCE), so
     * only deduplication and dispatch remain. The event row is inserted and
     * committed FIRST (in its own transaction, {@link WebhookEventRecorder})
     * — the unique event_id index is the serialization point, so a concurrent
     * delivery of the same event loses cleanly here and is answered as
     * already-processed instead of failing later with a 5xx (CodeRabbit
     * #241). A dispatch that fails rolls this transaction back AND removes
     * the committed row, so the provider's retry re-processes the event
     * instead of hitting the dedup gate forever.
     */
    boolean handleVerifiedWebhook(String provider, String eventId, String eventType,
                                  UUID paymentIntentId, String externalId) {
        if (webhookEventRepository.findByEventId(eventId).isPresent()) {
            return false;
        }
        try {
            webhookEventRecorder.record(provider, eventId, eventType);
        } catch (DataIntegrityViolationException ex) {
            // Distinguish a lost concurrent-duplicate race from every OTHER
            // integrity failure (CodeRabbit #242 round 2: e.g. an oversized
            // provider/eventId/eventType on the legacy route makes the insert
            // fail on column limits — that is NOT "already processed", and
            // acknowledging it with 200 would swallow the event). Only a row
            // that actually exists under this eventId is the concurrent
            // duplicate; anything else must surface.
            if (webhookEventRepository.findByEventId(eventId).isEmpty()) {
                throw ex;
            }
            log.info("Webhook event {} concurrently recorded by another delivery — answering already-processed: {}",
                    eventId, ex.getMostSpecificCause().getMessage());
            return false;
        }
        try {
            dispatchWebhookEvent(eventType, paymentIntentId, externalId);
        } catch (RuntimeException ex) {
            // The row is committed but the event was NOT processed: remove it
            // so the provider retry re-processes instead of being deduplicated
            // against a tombstone (recorded-and-lost).
            try {
                webhookEventRecorder.delete(eventId);
            } catch (RuntimeException cleanupEx) {
                // Never mask the ORIGINAL dispatch failure — but the surviving
                // dedup row would acknowledge the provider's retry without
                // processing it, so the orphan is logged as a loud operator
                // signal (delete the payment_webhook_events row for this
                // event to re-arm it).
                log.error("Webhook event {} dispatch failed AND the compensating delete failed — the dedup row"
                        + " survives and the provider retry will be acknowledged without processing. Operator"
                        + " action: delete the payment_webhook_events row for event {}. Cleanup failure:",
                        eventId, eventId, cleanupEx);
            }
            throw ex;
        }
        return true;
    }

    /**
     * Stripe webhook entry point: verifies the notification with the
     * provider's own signature scheme, resolves the local intent via the
     * psp_intent_id link (V33) — metadata as the documented cross-check —
     * and runs the verified dispatch. The 503 SU-001 inert answer mirrors
     * the MAIL / MEDIA_S3 provider gates when the channel is unbound.
     */
    @Observed(name = "payment.psp.webhook")
    public boolean handleStripeWebhook(String rawPayload, String signatureHeader) {
        PspChannel channel = requireChannel();
        PspChannel.VerifiedWebhook verified = channel.verifyWebhook(rawPayload, signatureHeader);
        UUID intentId = verified.marketplaceIntentId();
        if (intentId == null && verified.pspIntentId() != null) {
            intentId = paymentIntentRepository.findByPspIntentId(verified.pspIntentId())
                    .map(PaymentIntent::getId)
                    .orElse(null);
        }
        if (intentId == null && "payment_intent.succeeded".equals(verified.eventType())) {
            // Reject BEFORE recording: a recorded-but-unresolved event is
            // deduplicated forever while never having confirmed the intent —
            // the provider would keep answering 202 for a lost transition
            // (CodeRabbit #241). A non-2xx answer makes Stripe retry, and by
            // the next delivery the intent exists and resolves through the
            // metadata path or the V33 psp_intent_id link.
            throw new ConflictException(
                    "payment_intent.succeeded event " + verified.eventId()
                            + " could not be resolved to a marketplace payment intent —"
                            + " rejecting so the provider retries it");
        }
        return handleVerifiedWebhook("stripe", verified.eventId(), verified.eventType(),
                intentId, verified.pspIntentId());
    }

    private void dispatchWebhookEvent(String eventType, UUID paymentIntentId, String externalId) {
        switch (eventType) {
            case "payment_intent.succeeded" -> {
                if (paymentIntentId != null) {
                    log.info("Webhook dispatch: payment_intent.succeeded for intent {}", paymentIntentId);
                    confirmIntent(paymentIntentId, externalId);
                } else {
                    log.warn("Webhook payment_intent.succeeded missing paymentIntentId: eventType={}", eventType);
                }
            }
            case "payment_intent.processing" ->
                log.info("Webhook: payment intent processing confirmed by gateway: eventType={}", eventType);
            case "payment_intent.payment_failed" ->
                log.warn("Webhook: payment intent failed: eventType={}", eventType);
            default ->
                log.debug("Unhandled webhook event type: {}", eventType);
        }
    }

    @Transactional(readOnly = true)
    @Cacheable("paymentIntents")
    public PaymentIntent getIntent(UUID id) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<PaymentIntent> listIntents(Pageable pageable) {
        return paymentIntentRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<PaymentSummary> listIntentsSummaries(Pageable pageable) {
        return paymentIntentRepository.findAllSummariesBy(pageable).map(this::toPaymentSummaryFromView);
    }

    private PaymentSummary toPaymentSummaryFromView(PaymentIntentSummaryView view) {
        return new PaymentSummary(
                view.getId(),
                view.getBookingId(),
                view.getConsumerId(),
                view.getAmountCents(),
                view.getCurrency(),
                view.getStatus().name(),
                view.getRefundedAmountCents(),
                view.getCreatedAt(),
                view.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public PaymentSummary getIntentSummary(UUID id) {
        return toPaymentSummary(getIntent(id));
    }

    @Transactional(readOnly = true)
    public PaymentIntent getIntentForUser(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntent(id);
        verifyConsumerOwnership(intent, authentication);
        return intent;
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntent createIntent(UUID bookingId, UUID consumerId, String idempotencyKey) {
        // Idempotency: return existing intent if same key
        if (idempotencyKey != null) {
            var existing = paymentIntentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().getConsumerId().equals(consumerId)) {
                    throw new AccessDeniedException("Idempotency key belongs to another consumer");
                }
                return existing.get();
            }
        }

        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(bookingId);
        bookingInfo.requireParticipant(consumerId);
        bookingInfo.requireStatus("CONFIRMED", "create payment intent");

        PaymentIntent intent = PaymentIntent.create(bookingId, consumerId, bookingInfo.priceCents(),
                bookingInfo.currency(), idempotencyKey);
        PaymentIntent saved = paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(saved.getId(), "INITIATED"));
        return saved;
    }

    /**
     * Result of processing a payment intent: the local intent plus the PSP
     * client secret when the real channel is bound (the calling client needs
     * it to complete the payment on the provider side). Null clientSecret =
     * channel inert, existing behavior.
     */
    public record ProcessIntentResult(PaymentIntent intent, String clientSecret) {}

    @Observed(name = "payment.process")
    @PreAuthorize("hasRole('CONSUMER')")
    @Retry(name = "paymentProcessing")
    @CircuitBreaker(name = "paymentProcessing")
    @ConcurrencyLimit(5)
    public ProcessIntentResult processIntent(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntentForUser(id, authentication);
        intent.markProcessing();
        // Real channel (roadmap B3): create the remote intent when the PSP is
        // bound. The idempotency key is derived from the local intent id, so
        // retries replay the SAME remote intent (official idempotency-key
        // contract) instead of double-charging the flow.
        PspChannel channel = pspChannel.getIfAvailable();
        String clientSecret = null;
        if (channel != null) {
            var remote = channel.createRemoteIntent(
                    intent.getId(), intent.getAmountCents(), intent.getCurrency(),
                    "marketplace-intent-" + intent.getId());
            intent.assignPspIntentId(remote.pspIntentId());
            clientSecret = remote.clientSecret();
        }
        Payment payment = Payment.create(intent.getId(), intent.getAmountCents());
        paymentRepository.save(payment);
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
        return new ProcessIntentResult(intent, clientSecret);
    }

    @Observed(name = "payment.confirm")
    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public PaymentIntent confirmIntent(UUID id, String externalId) {
        PaymentIntent intent = getIntent(id);
        intent.markSucceeded();
        // Mark the payment as completed
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(p -> p.markCompleted(externalId));
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "COMPLETED"));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
        return intent;
    }

    @Observed(name = "payment.cancel")
    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntent cancelIntent(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntentForUser(id, authentication);
        intent.cancel();
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
        return intent;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public Payment refundPayment(UUID paymentId) {
        return refundPayment(paymentId, null);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public Payment refundPayment(UUID paymentId, @Min(1) Long amountCents) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        PaymentIntent intent = paymentIntentRepository.findById(payment.getPaymentIntentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + payment.getPaymentIntentId()));

        long alreadyRefunded = payment.getRefundedAmountCents();
        if (amountCents != null) {
            if (alreadyRefunded + amountCents > payment.getAmountCents()) {
                throw new ConflictException("Refund amount exceeds payment amount");
            }
            if (intent.getRefundedAmountCents() + amountCents > intent.getAmountCents()) {
                throw new ConflictException("Refund amount exceeds intent amount");
            }
        }
        boolean isFullRefund = (amountCents == null || alreadyRefunded + amountCents == payment.getAmountCents());
        if (isFullRefund) {
            payment.markRefunded();
            intent.markRefunded();
        } else {
            payment.markPartiallyRefunded(amountCents);
            intent.markPartiallyRefunded(amountCents);
        }
        paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), intent.getStatus().name()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), intent.getId()));
        return payment;
    }

    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoRefundByBooking(UUID bookingId) {
        paymentIntentRepository.findByBookingId(bookingId).ifPresent(intent -> {
            intent.markRefunded();
            paymentIntentRepository.save(intent);
            paymentRepository.findByPaymentIntentId(intent.getId()).ifPresent(payment -> {
                payment.markRefunded();
                paymentRepository.save(payment);
            });
            eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "REFUNDED"));
        });
    }

    private PaymentSummary toPaymentSummary(PaymentIntent paymentIntent) {
        return new PaymentSummary(
                paymentIntent.getId(),
                paymentIntent.getBookingId(),
                paymentIntent.getConsumerId(),
                paymentIntent.getAmountCents(),
                paymentIntent.getCurrency(),
                paymentIntent.getStatus().name(),
                paymentIntent.getRefundedAmountCents(),
                paymentIntent.getCreatedAt(),
                paymentIntent.getUpdatedAt()
        );
    }

    private void verifyConsumerOwnership(PaymentIntent intent, Authentication authentication) {
        if (currentUserProvider.isAdmin(authentication)) {
            return;
        }
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!intent.getConsumerId().equals(currentUserId)) {
            throw new AccessDeniedException("You do not own this payment intent");
        }
    }

    private PspChannel requireChannel() {
        PspChannel channel = pspChannel.getIfAvailable();
        if (channel == null) {
            throw new ServiceUnavailableException(
                    "Real payment channel is not configured. Set PAYMENTS_STRIPE_API_KEY and "
                            + "PAYMENTS_STRIPE_WEBHOOK_SECRET to enable the PSP channel.");
        }
        return channel;
    }
}
