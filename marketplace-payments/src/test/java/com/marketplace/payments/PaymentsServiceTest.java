package com.marketplace.payments;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.instancio.Instancio.*;
import static org.instancio.Select.field;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentsServiceTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWebhookEventRepository webhookEventRepository = mock(PaymentWebhookEventRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> pspChannel = mock(ObjectProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private final PaymentsService service = new PaymentsService(
            intentRepository,
            paymentRepository,
            webhookEventRepository,
            eventPublisher,
            currentUserProvider,
            bookingParticipantProvider,
            webhookSecurity,
            new WebhookEventRecorder(webhookEventRepository),
            pspChannel
    );

    @Test
    void createIntent_savesNewIntentUsingBookingPrice() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        String idempotencyKey = "key-123";
        BookingInfo bookingInfo = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();

        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent intent = service.createIntent(bookingId, consumerId, idempotencyKey);

        assertEquals(PaymentIntentStatus.CREATED, intent.getStatus());
        assertEquals(bookingId, intent.getBookingId());
        assertEquals(5000L, intent.getAmountCents());
    }

    @Test
    void createIntent_carriesBookingCurrency_roadmapB4() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        BookingInfo bookingInfo = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "USD")
                .create();

        when(intentRepository.findByIdempotencyKey(null)).thenReturn(Optional.empty());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent intent = service.createIntent(bookingId, consumerId, null);

        assertEquals("USD", intent.getCurrency());
    }

    @Test
    void createIntent_idempotencyReturnsExisting() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        String idempotencyKey = "key-123";
        PaymentIntent existing = of(PaymentIntent.class)
                .set(field(PaymentIntent::getBookingId), bookingId)
                .set(field(PaymentIntent::getConsumerId), consumerId)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getIdempotencyKey), idempotencyKey)
                .create();

        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        PaymentIntent result = service.createIntent(bookingId, consumerId, idempotencyKey);

        assertEquals(existing.getId(), result.getId());
        verify(intentRepository, never()).save(any());
        verifyNoInteractions(bookingParticipantProvider);
    }

    @Test
    void createIntent_rejectsWhenUserIsNotBookingParticipant() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        BookingInfo bookingInfo = of(BookingInfo.class)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(AccessDeniedException.class, () -> service.createIntent(bookingId, consumerId, null));
    }

    @Test
    void createIntent_rejectsWhenBookingNotConfirmed() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        BookingInfo bookingInfo = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "PENDING")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(IllegalStateException.class, () -> service.createIntent(bookingId, consumerId, null));
    }

    @Test
    void processIntent_propagatesCircuitBreakerOpenWithoutFallback() {
        UUID id = create(UUID.class);
        CallNotPermittedException circuitOpen = CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("paymentProcessing")
        );
        when(intentRepository.findById(id)).thenThrow(circuitOpen);

        assertThrows(CallNotPermittedException.class, () -> service.processIntent(id, authentication));
    }

    @Test
    void cancelIntent_succeedsForCreatedIntent() {
        UUID id = create(UUID.class);
        UUID consumerId = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getConsumerId), consumerId)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        PaymentIntent cancelled = service.cancelIntent(id, authentication);

        assertEquals(PaymentIntentStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void getIntent_throwsWhenNotFound() {
        UUID id = create(UUID.class);
        when(intentRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getIntent(id));
    }

    @Test
    void getIntent_returnsIntent() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), id)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));

        PaymentIntent result = service.getIntent(id);

        assertEquals(id, result.getId());
    }

    @Test
    void getIntentForUser_allowsConsumer() {
        UUID id = create(UUID.class);
        UUID consumerId = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), id)
                .set(field(PaymentIntent::getConsumerId), consumerId)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        PaymentIntent result = service.getIntentForUser(id, authentication);

        assertEquals(id, result.getId());
    }

    @Test
    void getIntentForUser_rejectsNonParticipant() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), id)
                .set(field(PaymentIntent::getConsumerId), create(UUID.class))
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(create(UUID.class));

        assertThrows(AccessDeniedException.class, () -> service.getIntentForUser(id, authentication));
    }

    @Test
    void getIntentForUser_allowsAdmin() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), id)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);

        PaymentIntent result = service.getIntentForUser(id, authentication);

        assertEquals(id, result.getId());
    }

    @Test
    void confirmIntent_marksSucceededAndPublishesEvent() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), id)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.PROCESSING)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent result = service.confirmIntent(id, "ext-1");

        assertEquals(PaymentIntentStatus.SUCCEEDED, result.getStatus());
        verify(eventPublisher).publishEvent(any(PaymentStateChangedEvent.class));
    }

    @Test
    void refundPayment_marksRefunded() {
        UUID paymentId = create(UUID.class);
        UUID intentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getId), paymentId)
                .set(field(Payment::getPaymentIntentId), intentId)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .set(field(Payment::getAmountCents), 5000L)
                .set(field(Payment::getRefundedAmountCents), 0L)
                .create();
        payment.markCompleted("ext-1");
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.SUCCEEDED)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getRefundedAmountCents), 0L)
                .create();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.refundPayment(paymentId);

        assertEquals(PaymentStatus.REFUNDED, result.getStatus());
        assertEquals(result.getAmountCents(), result.getRefundedAmountCents());
    }

    @Test
    void refundPayment_withAmount_partialRefund() {
        UUID paymentId = create(UUID.class);
        UUID intentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getId), paymentId)
                .set(field(Payment::getPaymentIntentId), intentId)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .set(field(Payment::getAmountCents), 10000L)
                .set(field(Payment::getRefundedAmountCents), 0L)
                .create();
        payment.markCompleted("ext-1");
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.SUCCEEDED)
                .set(field(PaymentIntent::getAmountCents), 10000L)
                .set(field(PaymentIntent::getRefundedAmountCents), 0L)
                .create();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.refundPayment(paymentId, 3000L);

        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(3000L, result.getRefundedAmountCents());
    }

    @Test
    void refundPayment_withNullAmount_fullRefund() {
        UUID paymentId = create(UUID.class);
        UUID intentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getId), paymentId)
                .set(field(Payment::getPaymentIntentId), intentId)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .set(field(Payment::getAmountCents), 10000L)
                .set(field(Payment::getRefundedAmountCents), 0L)
                .create();
        payment.markCompleted("ext-1");
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.SUCCEEDED)
                .set(field(PaymentIntent::getAmountCents), 10000L)
                .set(field(PaymentIntent::getRefundedAmountCents), 0L)
                .create();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.refundPayment(paymentId, null);

        assertEquals(PaymentStatus.REFUNDED, result.getStatus());
        assertEquals(10000L, result.getRefundedAmountCents());
    }

    @Test
    void refundPayment_withExactAmount_fullRefund() {
        UUID paymentId = create(UUID.class);
        UUID intentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getId), paymentId)
                .set(field(Payment::getPaymentIntentId), intentId)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .set(field(Payment::getAmountCents), 5000L)
                .set(field(Payment::getRefundedAmountCents), 0L)
                .create();
        payment.markCompleted("ext-1");
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.SUCCEEDED)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getRefundedAmountCents), 0L)
                .create();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.refundPayment(paymentId, 5000L);

        assertEquals(PaymentStatus.REFUNDED, result.getStatus());
        assertEquals(5000L, result.getRefundedAmountCents());
    }

    @Test
    void refundPayment_multiplePartials_accumulates() {
        UUID paymentId = create(UUID.class);
        UUID intentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getId), paymentId)
                .set(field(Payment::getPaymentIntentId), intentId)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .set(field(Payment::getAmountCents), 10000L)
                .set(field(Payment::getRefundedAmountCents), 3000L)
                .create();
        payment.markCompleted("ext-1");
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.PARTIALLY_REFUNDED)
                .set(field(PaymentIntent::getAmountCents), 10000L)
                .set(field(PaymentIntent::getRefundedAmountCents), 3000L)
                .create();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.refundPayment(paymentId, 2000L);

        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(5000L, result.getRefundedAmountCents());
    }

    @Test
    void refundPayment_accumulationToFull_refunds() {
        UUID paymentId = create(UUID.class);
        UUID intentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getId), paymentId)
                .set(field(Payment::getPaymentIntentId), intentId)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .set(field(Payment::getAmountCents), 10000L)
                .set(field(Payment::getRefundedAmountCents), 8000L)
                .create();
        payment.markCompleted("ext-1");
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.PARTIALLY_REFUNDED)
                .set(field(PaymentIntent::getAmountCents), 10000L)
                .set(field(PaymentIntent::getRefundedAmountCents), 8000L)
                .create();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.refundPayment(paymentId, 2000L);

        assertEquals(PaymentStatus.REFUNDED, result.getStatus());
        assertEquals(10000L, result.getRefundedAmountCents());
    }

    @Test
    void refundPayment_hasMinAnnotationOnAmountCents() throws Exception {
        var method = PaymentsService.class.getMethod("refundPayment", UUID.class, Long.class);
        var annotation = method.getParameters()[1].getAnnotation(Min.class);
        assertNotNull(annotation, "@Min annotation required on amountCents parameter");
        assertEquals(1, annotation.value());
    }

    @Test
    void refundPayment_overflow_throwsConflictException() {
        UUID paymentId = create(UUID.class);
        UUID intentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getId), paymentId)
                .set(field(Payment::getPaymentIntentId), intentId)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .set(field(Payment::getAmountCents), 10000L)
                .set(field(Payment::getRefundedAmountCents), 8000L)
                .create();
        payment.markCompleted("ext-1");
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.PARTIALLY_REFUNDED)
                .set(field(PaymentIntent::getAmountCents), 10000L)
                .set(field(PaymentIntent::getRefundedAmountCents), 8000L)
                .create();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        assertThrows(ConflictException.class, () -> service.refundPayment(paymentId, 5000L));
    }

    @Test
    void refundPayment_intentOverflow_throwsConflictException() {
        UUID paymentId = create(UUID.class);
        UUID intentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getId), paymentId)
                .set(field(Payment::getPaymentIntentId), intentId)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .set(field(Payment::getAmountCents), 10000L)
                .set(field(Payment::getRefundedAmountCents), 0L)
                .create();
        payment.markCompleted("ext-1");
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.PARTIALLY_REFUNDED)
                .set(field(PaymentIntent::getAmountCents), 10000L)
                .set(field(PaymentIntent::getRefundedAmountCents), 9000L)
                .create();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(ConflictException.class, () -> service.refundPayment(paymentId, 2000L));
    }

    @Test
    void webhookEvent_processesSuccessfully() {
        String eventId = "evt_new";
        when(webhookEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(PaymentWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean created = service.processWebhookEvent("stripe", eventId, "payment_intent.succeeded", "sig");

        assertTrue(created);
        verify(webhookSecurity).validateSignature("evt_newpayment_intent.succeeded", "sig");
    }

    @Test
    void webhookEvent_succeededDispatchesConfirmIntent() {
        String eventId = "evt_dispatch";
        UUID intentId = create(UUID.class);
        String externalId = "pi_test_123";
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getId), intentId)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.PROCESSING)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .create();

        when(webhookEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(PaymentWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean created = service.processWebhookEvent("stripe", eventId, "payment_intent.succeeded", "sig", intentId, externalId);

        assertTrue(created);
        verify(intentRepository).findById(intentId);
        verify(eventPublisher).publishEvent(any(PaymentStateChangedEvent.class));
    }

    @Test
    void webhookEvent_succeededWithoutIntentIdLogsWarning() {
        String eventId = "evt_no_intent";
        when(webhookEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(PaymentWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean created = service.processWebhookEvent("stripe", eventId, "payment_intent.succeeded", "sig");

        assertTrue(created);
        verifyNoInteractions(intentRepository);
    }

    @Test
    void webhookEvent_passesNullSignatureToValidator() {
        String eventId = "evt_no_sig";
        when(webhookEventRepository.findByEventId(eventId)).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(PaymentWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean created = service.processWebhookEvent("stripe", eventId, "payment.succeeded", null);

        assertTrue(created);
        verify(webhookSecurity).validateSignature("evt_no_sigpayment.succeeded", null);
    }
}
