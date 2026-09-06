package com.marketplace.payments;

import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the channel-activation semantics of the PSP layer (roadmap B3):
 * <ul>
 *   <li>inert channel — processIntent keeps the byte-for-byte legacy behavior
 *       (no remote call, null clientSecret)</li>
 *   <li>bound channel — remote intent created with the deterministic
 *       idempotency key, psp_intent_id link assigned, clientSecret returned</li>
 *   <li>Stripe webhook — 503 SU-001 when unbound; verified dispatch with
 *       psp_intent_id fallback resolution when bound</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentsServicePspTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWebhookEventRepository webhookEventRepository = mock(PaymentWebhookEventRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final com.marketplace.shared.api.BookingParticipantProvider bookingParticipantProvider =
            mock(com.marketplace.shared.api.BookingParticipantProvider.class);
    private final PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
    @Mock
    private PspChannel pspChannel;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> boundChannel = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> inertChannel = mock(ObjectProvider.class);
    private final Authentication authentication = mock(Authentication.class);

    private PaymentsService service(ObjectProvider<PspChannel> channel) {
        return new PaymentsService(intentRepository, paymentRepository, webhookEventRepository,
                eventPublisher, currentUserProvider, bookingParticipantProvider, webhookSecurity,
                new WebhookEventRecorder(webhookEventRepository), channel);
    }

    private PaymentIntent ownedIntent() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, "key-1");
        UUID consumerId = intent.getConsumerId();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        return intent;
    }

    @Test
    void processIntent_inertChannel_keepsLegacyBehavior() {
        PaymentIntent intent = ownedIntent();
        when(inertChannel.getIfAvailable()).thenReturn(null);

        PaymentsService.ProcessIntentResult result =
                service(inertChannel).processIntent(intent.getId(), authentication);

        assertEquals(PaymentIntentStatus.PROCESSING, result.intent().getStatus());
        assertNull(result.clientSecret(), "inert channel must not leak a client secret");
        assertNull(result.intent().getPspIntentId(), "inert channel must not link a PSP intent");
        verifyNoInteractions(pspChannel);
    }

    @Test
    void processIntent_boundChannel_createsRemoteIntentWithDeterministicKey() {
        PaymentIntent intent = ownedIntent();
        UUID intentId = intent.getId();
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteIntent(eq(intentId), eq(5000L), eq("SAR"),
                eq("marketplace-intent-" + intentId)))
                .thenReturn(new PspChannel.RemoteIntent("pi_remote_1", "pi_remote_1_secret"));

        PaymentsService.ProcessIntentResult result =
                service(boundChannel).processIntent(intentId, authentication);

        assertEquals(PaymentIntentStatus.PROCESSING, result.intent().getStatus());
        assertEquals("pi_remote_1", result.intent().getPspIntentId());
        assertEquals("pi_remote_1_secret", result.clientSecret());
        // Retry replay: same key derived from the SAME local intent id —
        // the official idempotency contract.
        verify(pspChannel).createRemoteIntent(eq(intentId), anyLong(), anyString(),
                eq("marketplace-intent-" + intentId));
    }

    @Test
    void handleStripeWebhook_unboundChannel_answers503() {
        when(inertChannel.getIfAvailable()).thenReturn(null);

        ServiceUnavailableException thrown = assertThrows(ServiceUnavailableException.class,
                () -> service(inertChannel).handleStripeWebhook("{}", "t=1,v1=x"));

        assertTrue(thrown.getMessage().contains("PAYMENTS_STRIPE_API_KEY"));
    }

    @Test
    void handleStripeWebhook_metadataResolution_dispatchesVerifiedEvent() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByEventId("evt_9")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_9", "payment_intent.succeeded",
                        intentId, "pi_remote_9"));
        intent.markProcessing();
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        verify(webhookEventRepository).saveAndFlush(argThat(ev -> "stripe".equals(ev.getProvider())
                && "evt_9".equals(ev.getEventId())));
        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
    }

    @Test
    void handleStripeWebhook_pspLinkFallback_resolvesIntentWithoutMetadata() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByEventId("evt_10")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_10", "payment_intent.succeeded",
                        null, "pi_remote_10"));
        intent.assignPspIntentId("pi_remote_10");
        intent.markProcessing();
        when(intentRepository.findByPspIntentId("pi_remote_10")).thenReturn(Optional.of(intent));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
    }

    @Test
    void handleStripeWebhook_duplicateEvent_isIdempotent() {
        UUID intentId = UUID.randomUUID(); // metadata id — never dispatched on a duplicate
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_11", "payment_intent.succeeded",
                        intentId, "pi_11"));
        when(webhookEventRepository.findByEventId("evt_11")).thenReturn(Optional.of(mock(PaymentWebhookEvent.class)));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertFalse(created, "a replayed Stripe notification must not re-dispatch");
        verify(webhookEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void handleStripeWebhook_concurrentDuplicateInsert_answersAlreadyProcessed() {
        // CodeRabbit #241: two concurrent deliveries of the same event both
        // pass the findByEventId lookup. The recorder's flushed insert is the
        // serialization point — the loser (unique event_id violation crossing
        // the recorder's transactional boundary) is answered already-processed
        // (false / HTTP 200), never a 5xx, and never dispatches.
        UUID intentId = UUID.randomUUID();
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_12", "payment_intent.succeeded",
                        intentId, "pi_12"));
        // Pre-check: absent; post-DIVE re-check: the winner's row exists.
        when(webhookEventRepository.findByEventId("evt_12"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(mock(PaymentWebhookEvent.class)));
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint"));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertFalse(created, "the losing concurrent delivery is the already-processed replay");
        verify(intentRepository, never()).findById(any());
    }

    @Test
    void handleStripeWebhook_unresolvedSucceededEvent_isRejectedBeforeRecording() {
        // CodeRabbit #241 (swallowed event): a payment_intent.succeeded event
        // whose intent cannot be resolved would previously be recorded and
        // acknowledged — then deduplicated forever while the intent was never
        // confirmed. It must be rejected BEFORE recording so Stripe retries
        // it (non-2xx), by which time the intent resolves via metadata or the
        // V33 psp_intent_id link.
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_13", "payment_intent.succeeded",
                        null, "pi_unlinked_13"));
        when(webhookEventRepository.findByEventId("evt_13")).thenReturn(Optional.empty());
        when(intentRepository.findByPspIntentId("pi_unlinked_13")).thenReturn(Optional.empty());

        com.marketplace.shared.api.ConflictException thrown = assertThrows(
                com.marketplace.shared.api.ConflictException.class,
                () -> service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig"));

        assertTrue(thrown.getMessage().contains("evt_13"));
        // Rejected BEFORE the dedup row exists — the retry is not blocked.
        verify(webhookEventRepository, never()).saveAndFlush(any());
        verify(webhookEventRepository, never()).deleteByEventId(any());
    }

    @Test
    void assignPspIntentId_repeatedSameLink_isIdempotent_conflictRejected() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 100L, null);
        intent.assignPspIntentId("pi_a");
        intent.assignPspIntentId("pi_a");
        assertEquals("pi_a", intent.getPspIntentId());
        assertThrows(IllegalStateException.class, () -> intent.assignPspIntentId("pi_b"));
    }
}
