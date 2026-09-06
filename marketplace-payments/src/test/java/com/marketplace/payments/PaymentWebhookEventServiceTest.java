package com.marketplace.payments;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Instancio.*;
import static org.mockito.Mockito.*;

class PaymentWebhookEventServiceTest {

    @Test
    void duplicateWebhookEventIsIgnored() {
        PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
        PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PspChannel> pspChannel = mock(ObjectProvider.class);

        PaymentsService service = new PaymentsService(intentRepository, paymentRepository, webhookRepository, publisher, currentUserProvider, bookingParticipantProvider, webhookSecurity, new WebhookEventRecorder(webhookRepository), pspChannel);
        when(webhookRepository.findByEventId("evt_1")).thenReturn(Optional.of(create(PaymentWebhookEvent.class)));

        boolean created = service.processWebhookEvent("mock", "evt_1", "payment_intent.succeeded", "sig");

        assertThat(created).isFalse();
        verify(webhookRepository, never()).save(any());
    }

    @Test
    void concurrentDuplicateInsertIsAnsweredAlreadyProcessedNever5xx() {
        // CodeRabbit #241: two concurrent deliveries of the same event both
        // pass the findByEventId lookup; the unique event_id index must make
        // the recorder's insert the serialization point — the loser is
        // answered false (already processed, HTTP 200), never a 5xx.
        PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
        PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PspChannel> pspChannel = mock(ObjectProvider.class);

        PaymentsService service = new PaymentsService(intentRepository, paymentRepository, webhookRepository, publisher, currentUserProvider, bookingParticipantProvider, webhookSecurity, new WebhookEventRecorder(webhookRepository), pspChannel);
        // Pre-check: absent; post-DIVE re-check: the concurrent winner's row
        // now exists — that (and only that) is the already-processed case.
        when(webhookRepository.findByEventId("evt_2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(create(PaymentWebhookEvent.class)));
        when(webhookRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint"));

        boolean created = service.processWebhookEvent("mock", "evt_2", "payment_intent.succeeded", "sig");

        assertThat(created).as("the concurrent loser is the already-processed delivery").isFalse();
        // The dispatch must NEVER run for the losing delivery.
        verify(intentRepository, never()).findById(any());
    }

    @Test
    void nonDuplicateIntegrityFailureSurfacesInsteadOfBeingAcknowledged() {
        // CodeRabbit #242 round 2: an integrity violation that is NOT the
        // concurrent-duplicate race (e.g. oversized provider/eventId values
        // failing on column limits) must propagate — answering 200 would
        // acknowledge an event that was never processed.
        PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
        PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PspChannel> pspChannel = mock(ObjectProvider.class);

        PaymentsService service = new PaymentsService(intentRepository, paymentRepository, webhookRepository, publisher, currentUserProvider, bookingParticipantProvider, webhookSecurity, new WebhookEventRecorder(webhookRepository), pspChannel);
        // Absent before AND after the failed insert — no concurrent winner.
        when(webhookRepository.findByEventId("evt_4")).thenReturn(Optional.empty());
        when(webhookRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "value too long for type character varying(100)"));

        assertThatThrownBy(() -> service.processWebhookEvent("mock", "evt_4", "payment_intent.succeeded", "sig"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void failedDispatchRemovesTheEventRowForProviderRetry() {
        // CodeRabbit #241 (recorded-and-lost): the event row is committed
        // before dispatch; a dispatch that fails must remove it — otherwise
        // the provider's retry would hit the dedup gate for an event that
        // was never processed.
        PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
        PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PspChannel> pspChannel = mock(ObjectProvider.class);

        PaymentsService service = new PaymentsService(intentRepository, paymentRepository, webhookRepository, publisher, currentUserProvider, bookingParticipantProvider, webhookSecurity, new WebhookEventRecorder(webhookRepository), pspChannel);
        when(webhookRepository.findByEventId("evt_3")).thenReturn(Optional.empty());
        when(webhookRepository.saveAndFlush(any(PaymentWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        // Dispatch fails: confirmIntent cannot find the local intent (an
        // unstubbed Optional-returning mock answers empty).

        UUID intentId = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.processWebhookEvent("mock", "evt_3", "payment_intent.succeeded", "sig", intentId, null))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
        // The compensating delete ran — the retry re-processes the event.
        verify(webhookRepository).deleteByEventId("evt_3");
    }

    @Test
    void failedDispatchKeepsTheOriginalFailureWhenCleanupAlsoFails() {
        // CodeRabbit #242 round 2: when the dispatch AND the compensating
        // delete both fail, the ORIGINAL dispatch failure must stay visible
        // (a cleanup exception must never mask it).
        PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
        PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<PspChannel> pspChannel = mock(ObjectProvider.class);

        PaymentsService service = new PaymentsService(intentRepository, paymentRepository, webhookRepository, publisher, currentUserProvider, bookingParticipantProvider, webhookSecurity, new WebhookEventRecorder(webhookRepository), pspChannel);
        when(webhookRepository.findByEventId("evt_5")).thenReturn(Optional.empty());
        when(webhookRepository.saveAndFlush(any(PaymentWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("delete failed too"))
                .when(webhookRepository).deleteByEventId("evt_5");

        UUID intentId = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.processWebhookEvent("mock", "evt_5", "payment_intent.succeeded", "sig", intentId, null))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class)
                .as("the original dispatch failure, not the cleanup failure");
    }
}
