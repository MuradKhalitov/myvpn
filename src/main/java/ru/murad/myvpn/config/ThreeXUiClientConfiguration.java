package ru.murad.myvpn.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import ru.murad.myvpn.exception.ThreeXUiException;

@Configuration
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class ThreeXUiClientConfiguration {

    @Bean
    WebClient threeXUiWebClient(ThreeXUiProperties properties) {
        validate(properties);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.readTimeout());
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private void validate(ThreeXUiProperties properties) {
        if (properties.username() == null || properties.username().isBlank()
                || properties.password() == null || properties.password().isBlank()
                || properties.inboundId() <= 0
                || properties.connectTimeout() == null
                || properties.connectTimeout().isNegative()
                || properties.readTimeout() == null
                || properties.readTimeout().isNegative()
                || properties.maxMutationAttempts() < 1
                || properties.maxMutationAttempts() > 3
                || properties.maxRequestsPerOperation() < 4
                || properties.retryInitialDelay() == null
                || properties.retryInitialDelay().isNegative()
                || properties.retryMaxDelay() == null
                || properties.retryMaxDelay().isNegative()) {
            throw new ThreeXUiException("Invalid 3x-ui configuration");
        }
    }
}
