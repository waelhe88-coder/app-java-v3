package com.marketplace.payments;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { PaymentsService.class, PaymentsServiceSecurityTest.TestConfig.class })
@EnableMethodSecurity(proxyTargetClass = true)
class PaymentsServiceSecurityTest {

    /**
     * The webhook dedup recorder (CodeRabbit #241 hardening) is a real
     * collaborator of the service: it gets the SAME mocked repository so the
     * security slice stays a slice.
     */
    @org.springframework.context.annotation.Configuration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        WebhookEventRecorder webhookEventRecorder(PaymentWebhookEventRepository repository) {
            return new WebhookEventRecorder(repository);
        }
    }

    @Autowired
    private PaymentsService paymentsService;

    @MockitoBean
    private PaymentIntentRepository paymentIntentRepository;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentWebhookEventRepository webhookEventRepository;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private BookingParticipantProvider bookingParticipantProvider;

    @MockitoBean
    private PaymentWebhookSecurity paymentWebhookSecurity;

    @Test
    @WithMockUser(roles = "USER")
    void createIntent_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.createIntent(UUID.randomUUID(), UUID.randomUUID(), "key"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void processIntent_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.processIntent(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void confirmIntent_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.confirmIntent(UUID.randomUUID(), "ext-123"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cancelIntent_whenNotConsumer_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.cancelIntent(UUID.randomUUID(), null));
    }

    @Test
    @WithMockUser(roles = "USER")
    void refundPayment_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.refundPayment(UUID.randomUUID()));
    }

    @Test
    @WithMockUser(roles = "USER")
    void refundPaymentAmount_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> paymentsService.refundPayment(UUID.randomUUID(), 500L));
    }

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void createIntent_whenConsumer_thenInvokes() {
        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(new BookingInfo(providerId, consumerId, "CONFIRMED", 1000L, "SAR", at, at));
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentIntent result = paymentsService.createIntent(bookingId, consumerId, null);

        assertThat(result.getStatus()).isEqualTo(PaymentIntentStatus.CREATED);
        assertThat(result.getConsumerId()).isEqualTo(consumerId);
        verify(paymentIntentRepository).save(any(PaymentIntent.class));
    }

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void processIntent_whenConsumer_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID consumerId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 1000L, null);
        when(paymentIntentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(consumerId);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentsService.ProcessIntentResult result = paymentsService.processIntent(intent.getId(), authentication);

        assertThat(result.intent().getStatus()).isEqualTo(PaymentIntentStatus.PROCESSING);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void confirmIntent_whenAdmin_thenInvokes() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 1000L, null);
        intent.markProcessing();
        when(paymentIntentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.empty());

        PaymentIntent result = paymentsService.confirmIntent(intent.getId(), "ext-1");

        assertThat(result.getStatus()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
    }

    @Test
    @WithMockUser(roles = "CONSUMER", username = "consumer")
    void cancelIntent_whenConsumer_thenInvokes() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID consumerId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 1000L, null);
        when(paymentIntentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(consumerId);

        PaymentIntent result = paymentsService.cancelIntent(intent.getId(), authentication);

        assertThat(result.getStatus()).isEqualTo(PaymentIntentStatus.CANCELLED);
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void refundPayment_whenAdmin_thenInvokes() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 1000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intent.getId(), 1000L);
        payment.markCompleted("ext-1");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentIntentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = paymentsService.refundPayment(payment.getId());

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(result.getRefundedAmountCents()).isEqualTo(1000L);
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void refundPaymentAmount_whenAdmin_thenInvokes() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 1000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intent.getId(), 1000L);
        payment.markCompleted("ext-1");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentIntentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = paymentsService.refundPayment(payment.getId(), 400L);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(result.getRefundedAmountCents()).isEqualTo(400L);
    }
}
