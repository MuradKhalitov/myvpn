package ru.murad.myvpn.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import ru.murad.myvpn.config.SmsRuProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "sms.ru.enabled", havingValue = "true")
public class SmsRuCallVerificationProvider implements PhoneVerificationProvider {
    private final WebClient webClient; private final SmsRuProperties properties; private final ObjectMapper mapper; private final Clock clock;
    public SmsRuCallVerificationProvider(WebClient smsRuWebClient, SmsRuProperties properties, ObjectMapper mapper, Clock clock) {
        this.webClient = smsRuWebClient; this.properties = properties; this.mapper = mapper; this.clock = clock;
    }
    @Override public PhoneVerificationStart start(String phone) {
        JsonNode node = post("/callcheck/add", "phone", phone);
        if (!"OK".equals(text(node, "status")) || node.path("status_code").asInt() != 100
                || text(node, "check_id") == null || text(node, "call_phone") == null || text(node, "call_phone_pretty") == null) throw failure();
        return new PhoneVerificationStart(text(node, "check_id"), text(node, "call_phone"), text(node, "call_phone_pretty"), clock.instant().plus(Duration.ofMinutes(5)));
    }
    @Override public PhoneVerificationState getStatus(String checkId) {
        JsonNode node = post("/callcheck/status", "check_id", checkId);
        if (!"OK".equals(text(node, "status")) || node.path("status_code").asInt() != 100) throw failure();
        return switch (text(node, "check_status")) { case "400" -> PhoneVerificationState.PENDING; case "401" -> PhoneVerificationState.VERIFIED; case "402" -> PhoneVerificationState.EXPIRED; default -> throw failure(); };
    }
    private JsonNode post(String path, String key, String value) {
        try {
            String body = webClient.post().uri(path).body(BodyInserters.fromFormData("api_id", properties.apiId()).with(key, value).with("json", "1"))
                    .retrieve().bodyToMono(String.class).block();
            return mapper.readTree(body);
        } catch (Exception e) { throw new IllegalStateException("SMS.RU verification is unavailable"); }
    }
    private String text(JsonNode node, String field) { String value = node.path(field).asText(); return value == null || value.isBlank() ? null : value; }
    private IllegalStateException failure() { return new IllegalStateException("SMS.RU verification request failed"); }
}
