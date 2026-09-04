package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.repository.AccountIdentityRepository;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ThreeXUiVpnProviderContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProviderConfiguration.class)
            .withPropertyValues("vpn.provider.type=3x-ui");

    @Test
    void springContextCreatesThreeXUiVpnProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ThreeXUiVpnProvider.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ThreeXUiVpnProvider.class)
    static class ProviderConfiguration {

        @Bean
        ThreeXUiInboundClient inboundClient() {
            return mock(ThreeXUiInboundClient.class);
        }

        @Bean
        VpnConfigurationFactory configurationFactory() {
            return mock(VpnConfigurationFactory.class);
        }

        @Bean
        ThreeXUiConfigurationMapper configurationMapper() {
            return mock(ThreeXUiConfigurationMapper.class);
        }

        @Bean
        ThreeXUiProperties threeXUiProperties() {
            return new ThreeXUiProperties(
                    URI.create("https://three-x-ui.test"), "/panel", "user", "password",
                    1, "vpn.example.test", null,
                    Duration.ofSeconds(1), Duration.ofSeconds(1),
                    1, 4, Duration.ZERO, Duration.ZERO);
        }

        @Bean
        AccountIdentityRepository accountIdentityRepository() {
            return mock(AccountIdentityRepository.class);
        }
    }
}
