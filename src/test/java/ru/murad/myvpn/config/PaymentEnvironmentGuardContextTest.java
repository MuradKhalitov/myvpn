package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import ru.murad.myvpn.client.FakeVpnProvider;
import ru.murad.myvpn.scheduler.PaymentActivationScheduler;
import ru.murad.myvpn.service.PaymentActivationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentEnvironmentGuardContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GuardConfiguration.class)
            .withPropertyValues(
                    "payment.provider=fake",
                    "payment.pending-ttl=1h",
                    "payment.return-url=https://example.invalid/payment-return",
                    "payment.allow-fake=true",
                    "payment.activation.fixed-delay=1h",
                    "payment.activation.batch-size=20",
                    "payment.activation.lease-duration=1m",
                    "payment.activation.retry-delay=1m",
                    "payment.activation.max-attempts=3");

    @Test
    void disabledActivationAllowsFakeAndSchedulerDoesNoWork() {
        contextRunner.withPropertyValues(
                        "payment.activation.enabled=false",
                        "vpn.provider.type=fake",
                        "vpn.provider.allow-fake=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FakeVpnProvider.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isNotEmpty();
                    context.getBean(PaymentActivationScheduler.class).process();
                    verifyNoInteractions(context.getBean(PaymentActivationService.class));
                });
    }

    @Test
    void enabledFakeWithoutAllowanceFailsContextStartup() {
        contextRunner.withPropertyValues(
                        "payment.activation.enabled=true",
                        "vpn.provider.type=fake",
                        "vpn.provider.allow-fake=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertStartupFailure(context, "Fake VPN provider");
                });
    }

    @Test
    void enabledFakeWithExplicitAllowanceStarts() {
        contextRunner.withPropertyValues(
                        "payment.activation.enabled=true",
                        "vpn.provider.type=fake",
                        "vpn.provider.allow-fake=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void enabledThreeXUiWithMandatorySettingsStartsWithoutFakeProvider() {
        contextRunner.withPropertyValues(
                        "payment.activation.enabled=true",
                        "vpn.provider.type=3x-ui",
                        "vpn.provider.allow-fake=false",
                        "vpn.three-x-ui.base-url=https://3x-ui.invalid",
                        "vpn.three-x-ui.web-base-path=/panel",
                        "vpn.three-x-ui.username=user",
                        "vpn.three-x-ui.password=password",
                        "vpn.three-x-ui.public-host=vpn.invalid",
                        "vpn.three-x-ui.inbound-id=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FakeVpnProvider.class);
                });
    }

    @Test
    void enabledThreeXUiWithoutMandatorySettingsFailsContextStartup() {
        contextRunner.withPropertyValues(
                        "payment.activation.enabled=true",
                        "vpn.provider.type=3x-ui",
                        "vpn.provider.allow-fake=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertStartupFailure(context, "vpn.three-x-ui.base-url");
                });
    }

    @Test
    void missingProviderDoesNotSilentlySelectFake() {
        contextRunner.withPropertyValues(
                        "payment.activation.enabled=true",
                        "vpn.provider.allow-fake=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertStartupFailure(context, "Fake VPN provider");
                });
    }

    @Test
    void invalidProviderTypeFailsContextStartup() {
        contextRunner.withPropertyValues(
                        "payment.activation.enabled=true",
                        "vpn.provider.type=unknown",
                        "vpn.provider.allow-fake=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertStartupFailure(context, "Unsupported VPN provider type");
                });
    }

    private static void assertStartupFailure(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            String expectedMessage) {
        Throwable current = context.getStartupFailure();
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expectedMessage)) return;
            current = current.getCause();
        }
        throw new AssertionError("Startup failure did not contain: " + expectedMessage,
                context.getStartupFailure());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentProperties.class)
    @Import({PaymentEnvironmentGuard.class, PaymentActivationScheduler.class, FakeVpnProvider.class, SchedulingConfig.class})
    static class GuardConfiguration {
        @Bean
        PaymentActivationService paymentActivationService() {
            return Mockito.mock(PaymentActivationService.class);
        }
    }
}
