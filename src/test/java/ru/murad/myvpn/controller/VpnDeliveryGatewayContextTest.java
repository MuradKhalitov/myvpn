package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.scheduler.VpnDeliveryScheduler;
import ru.murad.myvpn.service.VpnConfigurationDeliveryGateway;
import ru.murad.myvpn.service.VpnDeliveryService;
import ru.murad.myvpn.service.VpnDeliveryTransactionService;
import ru.murad.myvpn.service.impl.VpnDeliveryServiceImpl;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VpnDeliveryGatewayContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DeliveryConfiguration.class)
            .withPropertyValues(
                    "telegram.bot-token=test-token",
                    "vpn.delivery.batch-size=10",
                    "vpn.delivery.fixed-delay=5s",
                    "vpn.delivery.lease-duration=2m",
                    "vpn.delivery.max-attempts=5",
                    "vpn.delivery.retry-initial-delay=5s",
                    "vpn.delivery.retry-max-delay=5m");

    @Test
    void stagingCreatesExactlyOneTelegramGatewayServiceAndScheduler() {
        context("staging", true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(VpnConfigurationDeliveryGateway.class);
            assertThat(context).hasSingleBean(TelegramVpnConfigurationDeliveryGateway.class);
            assertThat(context).hasSingleBean(VpnDeliveryService.class);
            assertThat(context).hasSingleBean(VpnDeliveryScheduler.class);
        });
    }

    @Test
    void productionCreatesExactlyOneTelegramGatewayServiceAndScheduler() {
        context("prod", true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(VpnConfigurationDeliveryGateway.class);
            assertThat(context).hasSingleBean(TelegramVpnConfigurationDeliveryGateway.class);
            assertThat(context).hasSingleBean(VpnDeliveryService.class);
            assertThat(context).hasSingleBean(VpnDeliveryScheduler.class);
        });
    }

    @Test
    void disabledDeliveryStartsWithoutGatewayServiceOrScheduler() {
        context("staging", false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(VpnConfigurationDeliveryGateway.class);
            assertThat(context).doesNotHaveBean(VpnDeliveryServiceImpl.class);
            assertThat(context).doesNotHaveBean(VpnDeliveryScheduler.class);
        });
    }

    @Test
    void enabledDeliveryFailsFastWhenTelegramTokenIsMissing() {
        context("staging", true)
                .withPropertyValues("telegram.bot-token=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertFailureContains(context.getStartupFailure(),
                            "Telegram bot token is required");
                });
    }

    private void assertFailureContains(Throwable failure, String expectedMessage) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(expectedMessage)) {
                return;
            }
        }
        throw new AssertionError("Startup failure did not contain expected message", failure);
    }

    private ApplicationContextRunner context(String profile, boolean deliveryEnabled) {
        return contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues("vpn.delivery.enabled=" + deliveryEnabled);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({TelegramProperties.class, VpnDeliveryProperties.class})
    @Import({TelegramVpnConfigurationDeliveryGateway.class, VpnDeliveryServiceImpl.class,
            VpnDeliveryScheduler.class})
    static class DeliveryConfiguration {

        @Bean
        VpnDeliveryTransactionService vpnDeliveryTransactionService() {
            return mock(VpnDeliveryTransactionService.class);
        }

        @Bean
        SubscriptionRepository subscriptionRepository() {
            return mock(SubscriptionRepository.class);
        }

        @Bean
        VpnAccessRepository vpnAccessRepository() {
            return mock(VpnAccessRepository.class);
        }

        @Bean
        PaymentOrderRepository paymentOrderRepository() {
            return mock(PaymentOrderRepository.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
