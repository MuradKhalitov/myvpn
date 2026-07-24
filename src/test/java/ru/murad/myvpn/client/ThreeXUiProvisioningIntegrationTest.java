package ru.murad.myvpn.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.exception.ThreeXUiUncertainException;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.impl.SubscriptionServiceImpl;
import ru.murad.myvpn.service.PendingProvisionCandidate;
import ru.murad.myvpn.service.SubscriptionService;
import ru.murad.myvpn.service.SubscriptionTransactionService;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "vpn.lifecycle.expiration-check-delay=3600000",
        "vpn.lifecycle.pending-recovery-delay=3600000"
})
@ActiveProfiles("test")
@Testcontainers
class ThreeXUiProvisioningIntegrationTest {

    private static final long ADMIN_ID = 8101L;
    private static final long USER_ID = 8102L;
    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
    private static final String WEB_PATH = "/integration-path";
    private static final int INBOUND_ID = 42;

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer("postgres:16.3-alpine");

    @DynamicPropertySource
    static void configurePostgresql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private TelegramUserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private VpnAccessRepository accessRepository;
    @Autowired private SubscriptionMapper subscriptionMapper;
    @Autowired private SubscriptionTransactionService transactionService;

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        accessRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.saveAndFlush(TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(USER_ID)
                .chatId(USER_ID)
                .role(UserRole.USER)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void exhaustedBudgetAfterMutationMustPersistReconciliationRequired() {
        stubUncertainCreate();
        ThreeXUiProperties properties = properties();
        WebClient webClient = WebClient.builder().build();
        ObjectMapper objectMapper = new ObjectMapper();
        ThreeXUiUrlFactory urlFactory = new ThreeXUiUrlFactory(properties, true);
        ThreeXUiAuthClient authClient =
                new ThreeXUiAuthClient(webClient, urlFactory, properties, objectMapper);
        ThreeXUiSessionManager sessionManager = new ThreeXUiSessionManager(authClient);
        ThreeXUiInboundClient inboundClient = new ThreeXUiInboundClient(
                webClient, objectMapper, urlFactory, sessionManager, properties);
        ThreeXUiVpnProvider provider = new ThreeXUiVpnProvider(
                inboundClient,
                new ThreeXUiVlessConfigurationFactory(),
                new ThreeXUiConfigurationMapper(
                        objectMapper, properties, () -> "/fixedSpiderPath"),
                properties);
        SubscriptionService service = new SubscriptionServiceImpl(
                administratorId -> { },
                subscriptionRepository,
                accessRepository,
                provider,
                subscriptionMapper,
                transactionService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.activate(
                new ActivateSubscriptionRequest(ADMIN_ID, USER_ID, "MONTH_1")))
                .isInstanceOf(ThreeXUiUncertainException.class);

        var subscriptions = subscriptionRepository.findAll();
        assertThat(subscriptions).singleElement().satisfies(subscription -> {
            assertThat(subscription.getStatus())
                    .isEqualTo(SubscriptionStatus.RECONCILIATION_REQUIRED);
            assertThat(subscription.getId()).isNotNull();
            assertThat(subscription.getProvisioningAttemptCount()).isZero();
        });
        UUID subscriptionId = subscriptions.get(0).getId();
        assertThat(accessRepository.findBySubscriptionId(subscriptionId)).isEmpty();
        assertThat(server.getAllServeEvents()).hasSize(8);
        server.verify(1, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
        server.verify(5, getRequestedFor(urlEqualTo(inboundPath())));
        server.verify(2, postRequestedFor(urlEqualTo(addClientPath())));
        assertThat(server.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().equals(addClientPath()))
                .map(event -> event.getRequest().getBodyAsString()))
                .allMatch(body -> body.contains(subscriptionId.toString()));

        var recovery = transactionService.claimPendingProvisioning(
                NOW.minus(10, ChronoUnit.MINUTES),
                NOW.plus(6, ChronoUnit.MINUTES),
                "integration-worker");
        assertThat(recovery).singleElement()
                .extracting(PendingProvisionCandidate::subscriptionId)
                .isEqualTo(subscriptionId);
    }

    private void stubUncertainCreate() {
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .willReturn(json("{\"success\":true,\"msg\":\"\",\"obj\":null}")
                        .withHeader("Set-Cookie", "3x-ui=test-session; Path=/")));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("uncertain")
                .whenScenarioStateIs("Started")
                .willReturn(json(inboundResponse()))
                .willSetStateTo("pre-one"));
        server.stubFor(post(urlEqualTo(addClientPath()))
                .inScenario("uncertain")
                .whenScenarioStateIs("pre-one")
                .willReturn(json("{\"success\":true,\"msg\":\"\",\"obj\":null}"))
                .willSetStateTo("mutation-one"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("uncertain").whenScenarioStateIs("mutation-one")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("confirm-one"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("uncertain").whenScenarioStateIs("confirm-one")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovery-one"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("uncertain").whenScenarioStateIs("recovery-one")
                .willReturn(json(inboundResponse()))
                .willSetStateTo("pre-two"));
        server.stubFor(post(urlEqualTo(addClientPath()))
                .inScenario("uncertain").whenScenarioStateIs("pre-two")
                .willReturn(json("{\"success\":true,\"msg\":\"\",\"obj\":null}"))
                .willSetStateTo("mutation-two"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("uncertain").whenScenarioStateIs("mutation-two")
                .willReturn(aResponse().withStatus(503)));
    }

    private ThreeXUiProperties properties() {
        return new ThreeXUiProperties(
                URI.create(server.baseUrl()), WEB_PATH,
                "test-user", "test-password", INBOUND_ID,
                "vpn.example.test", null,
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                3, 8, Duration.ZERO, Duration.ZERO);
    }

    private String inboundPath() {
        return WEB_PATH + "/panel/api/inbounds/get/" + INBOUND_ID;
    }

    private String addClientPath() {
        return WEB_PATH + "/panel/api/inbounds/addClient";
    }

    private String inboundResponse() {
        return "{\"success\":true,\"msg\":\"\",\"obj\":{\"id\":42,\"port\":443,"
                + "\"protocol\":\"vless\",\"settings\":\"{\\\"clients\\\":[]}\","
                + "\"streamSettings\":\"{}\"}}";
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
            String body
    ) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
