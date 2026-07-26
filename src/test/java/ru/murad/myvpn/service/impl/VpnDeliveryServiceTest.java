package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.dto.VpnDeliveryMessage;
import ru.murad.myvpn.exception.TelegramDeliveryPermanentException;
import ru.murad.myvpn.exception.TelegramDeliveryTransientException;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VpnDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
    private VpnDeliveryTransactionService transactions; private VpnConfigurationDeliveryGateway gateway;
    private SubscriptionRepository subscriptions; private VpnAccessRepository accesses; private PaymentOrderRepository orders; private ClaimedVpnDelivery claim; private VpnAccess access;
    private VpnDeliveryServiceImpl service;
    @BeforeEach void setUp() {
        transactions = mock(VpnDeliveryTransactionService.class); gateway = mock(VpnConfigurationDeliveryGateway.class);
        subscriptions = mock(SubscriptionRepository.class); accesses = mock(VpnAccessRepository.class); orders = mock(PaymentOrderRepository.class);
        TelegramUser user = TelegramUser.builder().id(UUID.randomUUID()).telegramId(101L).chatId(101L).role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build();
        VpnTariff tariff = VpnTariff.builder().id(UUID.randomUUID()).code("T").name("Tariff").durationDays(30).price(new BigDecimal("1.00")).currency("RUB").active(true).createdAt(NOW).updatedAt(NOW).build();
        Subscription sub = Subscription.builder().id(UUID.randomUUID()).user(user).tariff(tariff).status(SubscriptionStatus.ACTIVE).startsAt(NOW).expiresAt(NOW.plus(Duration.ofDays(30))).activatedByTelegramId(101).activatedAt(NOW).createdAt(NOW).updatedAt(NOW).build();
        access = VpnAccess.builder().id(UUID.randomUUID()).subscription(sub).providerName("FAKE").externalAccessId(UUID.randomUUID().toString()).configurationData("fake-vpn://secret").status(VpnAccessStatus.ACTIVE).issuedAt(NOW).createdAt(NOW).updatedAt(NOW).build();
        PaymentOrder order = PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, NOW, Duration.ofHours(1)); order.attachSubscription(sub, NOW);
        String fp = sha(access.getConfigurationData()); claim = new ClaimedVpnDelivery(UUID.randomUUID(), user.getId(), user.getTelegramId(), sub.getId(), access.getId(), order.getId(), UUID.randomUUID(), 1, 0, 0, sub.getExpiresAt(), access.getProviderName(), access.getExternalAccessId(), fp, VpnDeliveryType.ACTIVATION_PROVISION, sub.getExpiresAt(), tariff.getName());
        when(subscriptions.findByIdForDelivery(sub.getId())).thenReturn(Optional.of(sub)); when(accesses.findByIdForDelivery(access.getId())).thenReturn(Optional.of(access)); when(orders.findByIdForDelivery(order.getId())).thenReturn(Optional.of(order));
        service = new VpnDeliveryServiceImpl(transactions, gateway, subscriptions, accesses, orders, new VpnDeliveryProperties(true, 10, Duration.ofSeconds(5), Duration.ofMinutes(2), 5, Duration.ofSeconds(5), Duration.ofMinutes(1)), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @Test void successfulGatewayCallIsOutsideTransactionAndCompletes() {
        when(transactions.claim(NOW, 10)).thenReturn(List.of(claim)); when(gateway.deliver(any())).thenAnswer(invocation -> { assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return 77L; });
        when(transactions.delivered(claim, 77L, NOW)).thenReturn(true);
        assertThat(service.processPendingDeliveries(10)).isEqualTo(new VpnDeliveryWorkerResult(1, 0, 1, 0, 0, 0, 0));
        verify(transactions).delivered(claim, 77L, NOW); verify(transactions, never()).retry(any(), any(), any(), any());
    }
    @Test void typedTransientUsesMaxOfBackoffAndRetryAfterAndWhitelistCode() {
        when(transactions.claim(NOW, 10)).thenReturn(List.of(claim)); when(transactions.retry(any(), any(), any(), any())).thenReturn(true); doThrow(new TelegramDeliveryTransientException(Duration.ofSeconds(30))).when(gateway).deliver(any());
        service.processPendingDeliveries(10);
        verify(transactions).retry(eq(claim), eq(VpnDeliveryFailureCode.TELEGRAM_RATE_LIMITED), eq(NOW), eq(NOW.plusSeconds(30)));
    }
    @Test void shortRetryAfterDoesNotReduceCalculatedBackoffAndPermanentDoesNotRetry() {
        when(transactions.claim(NOW, 10)).thenReturn(List.of(claim)); when(transactions.retry(any(), any(), any(), any())).thenReturn(true); doThrow(new TelegramDeliveryTransientException(Duration.ofSeconds(2))).when(gateway).deliver(any());
        service.processPendingDeliveries(10); verify(transactions).retry(eq(claim), eq(VpnDeliveryFailureCode.TELEGRAM_RATE_LIMITED), eq(NOW), eq(NOW.plusSeconds(5)));
        reset(transactions, gateway); when(transactions.claim(NOW, 10)).thenReturn(List.of(claim)); when(transactions.manualReview(any(), any(), any())).thenReturn(true); doThrow(new TelegramDeliveryPermanentException(VpnDeliveryFailureCode.TELEGRAM_BOT_BLOCKED)).when(gateway).deliver(any());
        service.processPendingDeliveries(10); verify(transactions).manualReview(claim, VpnDeliveryFailureCode.TELEGRAM_BOT_BLOCKED, NOW); verify(transactions, never()).retry(any(), any(), any(), any());
    }
    @Test void unexpectedGatewayRuntimeGetsSafeRetryButMessageValidationDoesNotCallGateway() {
        when(transactions.claim(NOW, 10)).thenReturn(List.of(claim)); when(transactions.retry(any(), any(), any(), any())).thenReturn(true); doThrow(new IllegalStateException("raw secret")).when(gateway).deliver(any()); service.processPendingDeliveries(10);
        verify(transactions).retry(eq(claim), eq(VpnDeliveryFailureCode.TELEGRAM_GATEWAY_UNEXPECTED), eq(NOW), eq(NOW.plusSeconds(5)));
    }
    @Test void transactionCompletionFailureIsNotMisclassifiedAsGatewayRetry() {
        when(transactions.claim(NOW, 10)).thenReturn(List.of(claim)); when(gateway.deliver(any())).thenReturn(1L);
        doThrow(new IllegalStateException("transaction failure")).when(transactions).delivered(claim, 1L, NOW);
        assertThat(service.processPendingDeliveries(10)).isEqualTo(new VpnDeliveryWorkerResult(1, 0, 0, 0, 0, 0, 1));
        verify(transactions, never()).retry(any(), any(), any(), any());
    }
    @Test void infrastructureFailureForOneClaimDoesNotStopTheBatchOrCreateGatewayFailure() {
        ClaimedVpnDelivery second = new ClaimedVpnDelivery(UUID.randomUUID(), claim.userId(), claim.telegramId(), claim.subscriptionId(),
                claim.vpnAccessId(), claim.sourcePaymentOrderId(), UUID.randomUUID(), claim.generation(), claim.subscriptionVersion(), claim.vpnAccessVersion(),
                claim.subscriptionExpiresAt(), claim.vpnProviderName(), claim.vpnExternalAccessId(), claim.configurationFingerprint(),
                claim.type(), claim.expiresAt(), claim.tariffName());
        when(transactions.claim(NOW, 10)).thenReturn(List.of(claim, second));
        when(gateway.deliver(any())).thenReturn(1L, 2L);
        doThrow(new IllegalStateException("persistence failure")).when(transactions).delivered(claim, 1L, NOW);
        when(transactions.delivered(second, 2L, NOW)).thenReturn(true);
        assertThat(service.processPendingDeliveries(10)).isEqualTo(new VpnDeliveryWorkerResult(2, 0, 1, 0, 0, 0, 1));
        verify(transactions, never()).retry(eq(claim), any(), any(), any());
        verify(transactions).delivered(second, 2L, NOW);
    }
    @Test void tooLongAutomaticMessageIsTerminalWithoutGatewayCallOrUriTruncation() {
        String longConfiguration = "x".repeat(5_000); ReflectionTestUtils.setField(access, "configurationData", longConfiguration);
        claim = new ClaimedVpnDelivery(claim.deliveryId(), claim.userId(), claim.telegramId(), claim.subscriptionId(), claim.vpnAccessId(),
                claim.sourcePaymentOrderId(), claim.token(), claim.generation(), claim.subscriptionVersion(), claim.vpnAccessVersion(), claim.subscriptionExpiresAt(),
                claim.vpnProviderName(), claim.vpnExternalAccessId(), sha(longConfiguration), claim.type(), claim.expiresAt(), claim.tariffName());
        when(transactions.claim(NOW, 10)).thenReturn(List.of(claim)); when(transactions.manualReview(any(), any(), any())).thenReturn(true);
        assertThat(service.processPendingDeliveries(10)).isEqualTo(new VpnDeliveryWorkerResult(1, 0, 0, 0, 1, 0, 0));
        verify(transactions).manualReview(claim, VpnDeliveryFailureCode.TELEGRAM_MESSAGE_TOO_LONG, NOW); verifyNoInteractions(gateway);
    }
    private String sha(String value) { try { byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format("%02x", b)); return result.toString(); } catch (Exception e) { throw new AssertionError(e); } }
}
