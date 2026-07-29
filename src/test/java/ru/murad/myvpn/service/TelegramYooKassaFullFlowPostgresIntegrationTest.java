package ru.murad.myvpn.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.murad.myvpn.dto.CheckoutDestination;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnDeliveryStatus;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnDeliveryRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.service.impl.VpnDeliveryServiceImpl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "payment.provider=telegram-yookassa",
        "payment.telegram.provider-token=test-provider-token",
        "payment.telegram.currency=RUB",
        "payment.telegram.pre-checkout-timeout=9s",
        "payment.telegram.receipt-enabled=false",
        "payment.activation.enabled=false",
        "vpn.delivery.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class TelegramYooKassaFullFlowPostgresIntegrationTest {

    private static final long TELEGRAM_ID = 81001L;
    private static final long CHAT_ID = 82001L;
    private static final long AMOUNT_MINOR = 9_000L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            PaymentActivationDeliveryPostgresIntegrationTest.POSTGRES;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean TelegramPaymentGateway telegramPaymentGateway;
    @MockBean VpnConfigurationDeliveryGateway vpnDeliveryGateway;

    @Autowired PaymentCheckoutService checkoutService;
    @Autowired TelegramPaymentEventService paymentEventService;
    @Autowired PaymentActivationService activationService;
    VpnDeliveryService deliveryService;
    @Autowired VpnDeliveryTransactionService deliveryTransactions;
    @Autowired VpnDeliveryProperties deliveryProperties;
    @Autowired Clock clock;
    @Autowired PaymentOrderRepository orders;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired VpnAccessRepository accesses;
    @Autowired VpnDeliveryRepository deliveries;
    @Autowired TelegramUserRepository users;
    @Autowired VpnTariffRepository tariffs;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void clean() {
        deliveries.deleteAll();
        orders.deleteAll();
        accesses.deleteAll();
        subscriptions.deleteAll();
        users.deleteAll();
        tariffs.deleteAll();
        reset(telegramPaymentGateway, vpnDeliveryGateway);
        deliveryService = new VpnDeliveryServiceImpl(
                deliveryTransactions, vpnDeliveryGateway, subscriptions,
                accesses, orders, deliveryProperties, clock);
    }

    @Test
    void telegramInvoiceToActivationDeliveryAndExtensionIsEndToEndIdempotent() {
        Instant fixtureNow = Instant.now();
        TelegramUser user = users.saveAndFlush(TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(TELEGRAM_ID)
                .chatId(CHAT_ID)
                .role(UserRole.USER)
                .createdAt(fixtureNow)
                .updatedAt(fixtureNow)
                .build());
        tariffs.saveAndFlush(VpnTariff.builder()
                .id(UUID.randomUUID())
                .code("MONTH_E2E")
                .name("MyVPN 30 days")
                .description("Test VPN service")
                .durationDays(30)
                .price(new BigDecimal("90.00"))
                .currency("RUB")
                .active(true)
                .createdAt(fixtureNow)
                .updatedAt(fixtureNow)
                .build());
        when(telegramPaymentGateway.sendInvoice(any()))
                .thenReturn(501, 502);
        when(vpnDeliveryGateway.deliver(any()))
                .thenReturn(601L, 602L);

        PaymentCheckoutResult firstCheckout =
                checkoutService.startCheckout(TELEGRAM_ID, "MONTH_E2E");
        assertThat(firstCheckout.destination())
                .isEqualTo(new CheckoutDestination.TelegramInvoiceSent(501));
        assertThat(orders.count()).isOne();
        PaymentOrder firstOrder = onlyOrder();
        UUID firstOrderId = firstOrder.getId();
        assertThat(firstOrder.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(firstOrder.getTelegramInvoicePayload())
                .matches("[A-Za-z0-9_-]{43}");
        assertThat(firstOrder.getTelegramInvoiceMessageId()).isEqualTo(501);
        assertThat(firstOrder.getConfirmationUrl()).isNull();

        acceptPreCheckout(firstOrder);
        entityManager.clear();
        firstOrder = orders.findById(firstOrder.getId()).orElseThrow();
        assertThat(firstOrder.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(subscriptions.count()).isZero();
        assertThat(accesses.count()).isZero();
        verify(telegramPaymentGateway).answerPreCheckoutQuery(
                argThat(queryId -> queryId.startsWith("pre-")), 
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.isNull());

        TelegramSuccessfulPaymentCommand firstPayment =
                successful(firstOrder, "tg-charge-1", "provider-charge-1");
        paymentEventService.handleSuccessfulPayment(firstPayment);
        entityManager.clear();
        firstOrder = orders.findById(firstOrder.getId()).orElseThrow();
        assertThat(firstOrder.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(firstOrder.getTelegramPaymentChargeId()).isEqualTo("tg-charge-1");
        assertThat(firstOrder.getProviderPaymentChargeId()).isEqualTo("provider-charge-1");
        assertThat(firstOrder.getActivationCompletedAt()).isNull();
        assertThat(subscriptions.count()).isZero();

        PaymentActivationWorkerResult firstActivation =
                activationService.processPendingActivations(10);
        assertThat(firstActivation.succeeded()).isOne();
        entityManager.clear();
        firstOrder = orders.findById(firstOrder.getId()).orElseThrow();
        assertThat(firstOrder.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.ACTIVATED);
        assertThat(firstOrder.getActivationCompletedAt()).isNotNull();
        assertActivatedCounts(user);
        assertThat(deliveries.findAll()).singleElement()
                .satisfies(delivery ->
                        assertThat(delivery.getStatus()).isEqualTo(VpnDeliveryStatus.PENDING));

        VpnDeliveryWorkerResult firstDelivery =
                deliveryService.processPendingDeliveries(10);
        assertThat(firstDelivery.delivered()).isOne();
        entityManager.clear();
        assertThat(deliveries.findAll()).singleElement()
                .satisfies(delivery ->
                        assertThat(delivery.getStatus()).isEqualTo(VpnDeliveryStatus.DELIVERED));
        verify(vpnDeliveryGateway, times(1)).deliver(any());

        Instant expiryAfterFirstActivation = onlySubscription().getExpiresAt();
        paymentEventService.handleSuccessfulPayment(firstPayment);
        assertThat(activationService.processPendingActivations(10).claimed()).isZero();
        assertThat(deliveryService.processPendingDeliveries(10).claimed()).isZero();
        entityManager.clear();
        assertCounts(1, 1, 1, 1);
        assertThat(onlySubscription().getExpiresAt()).isEqualTo(expiryAfterFirstActivation);
        verify(vpnDeliveryGateway, times(1)).deliver(any());

        PaymentCheckoutResult secondCheckout =
                checkoutService.startCheckout(TELEGRAM_ID, "MONTH_E2E");
        assertThat(secondCheckout.destination())
                .isEqualTo(new CheckoutDestination.TelegramInvoiceSent(502));
        entityManager.clear();
        PaymentOrder secondOrder = orders.findAll().stream()
                .filter(order -> !order.getId().equals(firstOrderId))
                .findFirst().orElseThrow();
        assertThat(secondOrder.getTelegramInvoicePayload())
                .isNotEqualTo(firstOrder.getTelegramInvoicePayload());
        acceptPreCheckout(secondOrder);
        TelegramSuccessfulPaymentCommand secondPayment =
                successful(secondOrder, "tg-charge-2", "provider-charge-2");
        paymentEventService.handleSuccessfulPayment(secondPayment);
        assertThat(activationService.processPendingActivations(10).succeeded()).isOne();
        assertThat(deliveryService.processPendingDeliveries(10).delivered()).isOne();

        entityManager.clear();
        assertCounts(2, 1, 1, 2);
        Instant expiryAfterExtension = onlySubscription().getExpiresAt();
        assertThat(expiryAfterExtension)
                .isEqualTo(expiryAfterFirstActivation.plus(Duration.ofDays(
                        secondOrder.getDurationDaysSnapshot())));
        assertThat(deliveries.findAll())
                .allMatch(delivery -> delivery.getStatus() == VpnDeliveryStatus.DELIVERED);
        verify(vpnDeliveryGateway, times(2)).deliver(any());

        paymentEventService.handleSuccessfulPayment(secondPayment);
        assertThat(activationService.processPendingActivations(10).claimed()).isZero();
        assertThat(deliveryService.processPendingDeliveries(10).claimed()).isZero();
        entityManager.clear();
        assertCounts(2, 1, 1, 2);
        assertThat(onlySubscription().getExpiresAt()).isEqualTo(expiryAfterExtension);
        assertThat(orders.findById(secondOrder.getId()).orElseThrow()
                .getTelegramPaymentChargeId()).isEqualTo("tg-charge-2");
        assertThat(orders.findById(secondOrder.getId()).orElseThrow()
                .getProviderPaymentChargeId()).isEqualTo("provider-charge-2");
        verify(vpnDeliveryGateway, times(2)).deliver(any());
    }

    private void acceptPreCheckout(PaymentOrder order) {
        paymentEventService.handlePreCheckout(new TelegramPreCheckoutCommand(
                "pre-" + order.getId(),
                TELEGRAM_ID,
                order.getTelegramInvoicePayload(),
                "RUB",
                AMOUNT_MINOR));
    }

    private TelegramSuccessfulPaymentCommand successful(
            PaymentOrder order, String telegramCharge, String providerCharge
    ) {
        return new TelegramSuccessfulPaymentCommand(
                TELEGRAM_ID,
                order.getTelegramInvoicePayload(),
                "RUB",
                AMOUNT_MINOR,
                telegramCharge,
                providerCharge,
                Instant.now());
    }

    private PaymentOrder onlyOrder() {
        return orders.findAll().stream().findFirst().orElseThrow();
    }

    private Subscription onlySubscription() {
        return subscriptions.findAll().stream().findFirst().orElseThrow();
    }

    private void assertActivatedCounts(TelegramUser user) {
        assertCounts(1, 1, 1, 1);
        assertThat(onlySubscription().getUser().getId()).isEqualTo(user.getId());
        assertThat(onlySubscription().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(accesses.findAll()).singleElement()
                .satisfies(access ->
                        assertThat(access.getStatus()).isEqualTo(VpnAccessStatus.ACTIVE));
    }

    private void assertCounts(
            long orderCount, long subscriptionCount,
            long accessCount, long deliveryCount
    ) {
        assertThat(orders.count()).isEqualTo(orderCount);
        assertThat(subscriptions.count()).isEqualTo(subscriptionCount);
        assertThat(accesses.count()).isEqualTo(accessCount);
        assertThat(deliveries.count()).isEqualTo(deliveryCount);
    }
}
