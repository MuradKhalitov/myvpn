package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.murad.myvpn.client.threexui.ThreeXUiApiResponse;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiAuthenticationException;
import ru.murad.myvpn.exception.VpnProviderFailureCode;

import java.util.List;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class ThreeXUiAuthClient {

    private final WebClient webClient;
    private final ThreeXUiUrlFactory urlFactory;
    private final ThreeXUiProperties properties;
    private final ObjectMapper objectMapper;

    public ThreeXUiAuthClient(
            WebClient threeXUiWebClient,
            ThreeXUiUrlFactory urlFactory,
            ThreeXUiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.webClient = threeXUiWebClient;
        this.urlFactory = urlFactory;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String login(ThreeXUiRequestBudget budget) {
        try {
            budget.acquire();
            return webClient.post()
                    .uri(urlFactory.login())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("username", properties.username())
                            .with("password", properties.password()))
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status == 429 || status == 502
                                || status == 503 || status == 504) {
                            VpnProviderFailureCode code = status == 429
                                    ? VpnProviderFailureCode.RATE_LIMITED
                                    : VpnProviderFailureCode.PROVIDER_UNAVAILABLE;
                            return response.releaseBody().then(Mono.error(
                                    new ru.murad.myvpn.exception
                                            .ThreeXUiRetryableException(
                                            code, null,
                                            "Temporary 3x-ui authentication failure")));
                        }
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.releaseBody()
                                    .then(Mono.error(new ThreeXUiAuthenticationException()));
                        }
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> authenticatedCookie(body,
                                        response.cookies().get("3x-ui")));
                    })
                    .block();
        } catch (WebClientRequestException exception) {
            if (exception.getCause() instanceof javax.net.ssl.SSLException) {
                throw new ru.murad.myvpn.exception.ThreeXUiException(
                        "3x-ui TLS validation failed");
            }
            throw new ru.murad.myvpn.exception.ThreeXUiRetryableException(
                    isTimeout(exception) ? VpnProviderFailureCode.REQUEST_TIMEOUT
                            : VpnProviderFailureCode.PROVIDER_UNAVAILABLE,
                    null, "Temporary network failure during 3x-ui authentication");
        }
    }

    private String authenticatedCookie(String body, List<ResponseCookie> sessionCookies) {
        try {
            ThreeXUiApiResponse<?> response =
                    objectMapper.readValue(body, ThreeXUiApiResponse.class);
            if (!response.success() || sessionCookies == null || sessionCookies.isEmpty()) {
                throw new ThreeXUiAuthenticationException();
            }
            ResponseCookie cookie = sessionCookies.get(sessionCookies.size() - 1);
            return cookie.getName() + "=" + cookie.getValue();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ThreeXUiAuthenticationException();
        }
    }

    private boolean isTimeout(WebClientRequestException exception) {
        for (Throwable cursor = exception; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof java.util.concurrent.TimeoutException
                    || cursor instanceof io.netty.handler.timeout.TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
