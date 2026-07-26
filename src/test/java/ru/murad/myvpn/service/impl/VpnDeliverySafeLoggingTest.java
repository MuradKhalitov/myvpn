package ru.murad.myvpn.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.model.VpnDeliveryType;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.service.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VpnDeliverySafeLoggingTest {
    @Test void infrastructureLogContainsFramesAndTypesButNeverExceptionMessagesOrSecrets() {
        VpnDeliveryTransactionService transactions = mock(VpnDeliveryTransactionService.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class); VpnAccessRepository accesses = mock(VpnAccessRepository.class);
        ClaimedVpnDelivery claim = new ClaimedVpnDelivery(UUID.randomUUID(), UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 0, 0, Instant.parse("2026-08-01T00:00:00Z"), "FAKE", "redacted", "a".repeat(64),
                VpnDeliveryType.ACTIVATION_PROVISION, Instant.parse("2026-08-01T00:00:00Z"), "Tariff");
        when(transactions.claim(any(), anyInt())).thenReturn(List.of(claim));
        RuntimeException nested = new RuntimeException("NESTED_SECRET vless://nested TelegramChat=321");
        when(subscriptions.findByIdForDelivery(claim.subscriptionId())).thenThrow(new RuntimeException("SECRET_TOKEN vless://user@example TelegramChat=123", nested));
        VpnDeliveryServiceImpl service = new VpnDeliveryServiceImpl(transactions, mock(VpnConfigurationDeliveryGateway.class), subscriptions, accesses, mock(PaymentOrderRepository.class),
                new VpnDeliveryProperties(true, 10, Duration.ofSeconds(5), Duration.ofMinutes(2), 5, Duration.ofSeconds(5), Duration.ofMinutes(5)),
                Clock.fixed(Instant.parse("2026-07-26T12:00:00Z"), ZoneOffset.UTC));
        Logger logger = (Logger) LoggerFactory.getLogger(VpnDeliveryServiceImpl.class); ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try { service.processPendingDeliveries(10); } finally { logger.detachAppender(appender); }
        String logged = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).map(event -> event.getFormattedMessage()).reduce("", String::concat);
        assertThat(logged).contains("RuntimeException", "VpnDeliverySafeLoggingTest");
        assertThat(logged).doesNotContain("SECRET_TOKEN", "NESTED_SECRET", "vless://", "TelegramChat", "user@example", "nested");
    }
}
