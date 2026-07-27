package ru.murad.myvpn.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@ConditionalOnProperty(name = "payment.provider", havingValue = "yookassa")
public class YooKassaClientConfiguration {

    @Bean
    WebClient yooKassaWebClient(YooKassaProperties properties) {
        validate(properties);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.readTimeout());
        return WebClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private void validate(YooKassaProperties properties) {
        if (properties.baseUrl() == null || !"https".equalsIgnoreCase(properties.baseUrl().getScheme())
                || properties.baseUrl().getHost() == null || properties.baseUrl().getHost().isBlank()
                || properties.shopId() == null || properties.shopId().isBlank()
                || properties.secretKey() == null || properties.secretKey().isBlank()
                || properties.returnUrl() == null || !"https".equalsIgnoreCase(properties.returnUrl().getScheme())
                || properties.returnUrl().getHost() == null || properties.returnUrl().getHost().isBlank()
                || properties.connectTimeout() == null || properties.connectTimeout().isZero() || properties.connectTimeout().isNegative()
                || properties.readTimeout() == null || properties.readTimeout().isZero() || properties.readTimeout().isNegative()) {
            throw new IllegalStateException("YooKassa configuration is incomplete or invalid");
        }
    }
}
