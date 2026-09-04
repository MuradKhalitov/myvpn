package ru.murad.myvpn.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@ConditionalOnProperty(name = "sms.ru.enabled", havingValue = "true")
public class SmsRuConfiguration {
    @Bean WebClient smsRuWebClient(SmsRuProperties properties) {
        if (properties.apiId() == null || properties.apiId().isBlank() || properties.baseUrl() == null
                || properties.connectTimeout() == null || properties.readTimeout() == null) {
            throw new IllegalStateException("SMS.RU configuration is invalid");
        }
        HttpClient client = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.toIntExact(properties.connectTimeout().toMillis())).responseTimeout(properties.readTimeout());
        return WebClient.builder().baseUrl(properties.baseUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(client)).build();
    }
}
