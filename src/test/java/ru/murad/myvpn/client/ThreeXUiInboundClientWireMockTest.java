package ru.murad.myvpn.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import ru.murad.myvpn.client.threexui.ThreeXUiClientRequest;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundSettings;
import ru.murad.myvpn.client.threexui.ThreeXUiVlessClient;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreeXUiInboundClientWireMockTest {

    private static final String WEB_PATH = "/test-path";
    private static final String COOKIE = "3x-ui=test-cookie-value";

    private WireMockServer server;
    private ThreeXUiInboundClient client;
    private ThreeXUiSessionManager sessionManager;
    private ThreeXUiRequestBudget budget;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
        ThreeXUiProperties properties = properties();
        ThreeXUiUrlFactory urlFactory = new ThreeXUiUrlFactory(properties, true);
        WebClient webClient = WebClient.builder().build();
        ThreeXUiAuthClient authClient =
                new ThreeXUiAuthClient(webClient, urlFactory, properties,
                        new ObjectMapper());
        sessionManager = new ThreeXUiSessionManager(authClient);
        client = new ThreeXUiInboundClient(
                webClient, new ObjectMapper(), urlFactory, sessionManager, properties);
        budget = new ThreeXUiRequestBudget(32);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void shouldLoginOnceAndReuseSessionCookie() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .withHeader("Cookie", equalTo(COOKIE))
                .willReturn(json(inboundResponse("vless", "[]"))));

        client.getInbound(budget);
        client.getInbound(budget);

        server.verify(1, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
        server.verify(2, getRequestedFor(urlEqualTo(inboundPath()))
                .withHeader("Cookie", equalTo(COOKIE)));
    }

    @Test
    void shouldSendLoginAsFormData() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .willReturn(json(inboundResponse("vless", "[]"))));

        client.getInbound(budget);

        server.verify(postRequestedFor(urlEqualTo(WEB_PATH + "/login"))
                .withHeader("Content-Type",
                        equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("username=test-user&password=test-password")));
    }

    @Test
    void shouldLoginAgainOnceAfterExpiredSession() {
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .inScenario("session")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Set-Cookie", "3x-ui=first; Path=/")
                        .withBody("{\"success\":true,\"msg\":\"\",\"obj\":null}"))
                .willSetStateTo("first-issued"));
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .inScenario("session")
                .whenScenarioStateIs("first-rejected")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Set-Cookie", "3x-ui=second; Path=/")
                        .withBody("{\"success\":true,\"msg\":\"\",\"obj\":null}")));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("session")
                .whenScenarioStateIs("first-issued")
                .withHeader("Cookie", equalTo("3x-ui=first"))
                .willReturn(aResponse().withStatus(404))
                .willSetStateTo("first-rejected"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("session")
                .whenScenarioStateIs("first-rejected")
                .withHeader("Cookie", equalTo("3x-ui=second"))
                .willReturn(json(inboundResponse("vless", "[]"))));

        client.getInbound(budget);

        server.verify(2, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
    }

    @Test
    void shouldTreatSecond404AsResourceNotFoundWithoutAnotherLogin() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.getInbound(budget))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiNotFoundException.class);

        server.verify(2, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
        server.verify(2, getRequestedFor(urlEqualTo(inboundPath())));
    }

    @Test
    void exhaustedBudgetAfterReloginMustNotSendReplay() {
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .inScenario("budget-auth")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Set-Cookie", "3x-ui=first; Path=/")
                        .withBody("{\"success\":true,\"msg\":\"\",\"obj\":null}"))
                .willSetStateTo("first-issued"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("budget-auth")
                .whenScenarioStateIs("first-issued")
                .withHeader("Cookie", equalTo("3x-ui=first"))
                .willReturn(aResponse().withStatus(404))
                .willSetStateTo("first-rejected"));
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .inScenario("budget-auth")
                .whenScenarioStateIs("first-rejected")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Set-Cookie",
                                "3x-ui=secret-cookie-marker; Path=/")
                        .withBody("{\"success\":true,\"msg\":\"\",\"obj\":null}"))
                .willSetStateTo("second-issued"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("budget-auth")
                .whenScenarioStateIs("second-issued")
                .willReturn(json(inboundResponse("vless", "[]"))));

        sessionManager.getSessionCookie(new ThreeXUiRequestBudget(1));
        server.resetRequests();

        assertThatThrownBy(() ->
                client.getInbound(new ThreeXUiRequestBudget(2)))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiUncertainException.class)
                .hasMessageNotContaining("secret-cookie-marker");

        server.verify(1, getRequestedFor(urlEqualTo(inboundPath())));
        server.verify(1, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
        assertThat(server.getAllServeEvents()).hasSize(2);
    }

    @Test
    void shouldRejectSuccessfulHttpWithFailedLoginEnvelopeAndCookie() {
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Set-Cookie", "3x-ui=must-not-be-used; Path=/")
                        .withBody("{\"success\":false,\"msg\":\"sensitive\",\"obj\":null}")));

        assertThatThrownBy(() -> client.getInbound(budget))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiAuthenticationException.class)
                .hasMessageNotContaining("sensitive");
        server.verify(1, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
    }

    @Test
    void shouldSelectNamedSessionCookieWhenItIsNotFirst() {
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Set-Cookie", "helper=ignored; Path=/")
                        .withHeader("Set-Cookie", "3x-ui=selected; Path=/; HttpOnly")
                        .withBody("{\"success\":true,\"msg\":\"\",\"obj\":null}")));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .withHeader("Cookie", equalTo("3x-ui=selected"))
                .willReturn(json(inboundResponse("vless", "[]"))));

        client.getInbound(budget);
    }

    @Test
    void shouldRejectSuccessfulLoginWithoutSessionCookie() {
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"success\":true,\"msg\":\"\",\"obj\":null}")));

        assertThatThrownBy(() -> client.getInbound(budget))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiAuthenticationException.class);
    }

    @Test
    void shouldParseVlessInboundSettingsAndFindClientData() {
        stubLogin(COOKIE);
        String settings = """
                {"clients":[{"id":"test-client","email":"test-email","enable":true,
                "expiryTime":12345,"totalGB":0,"limitIp":0,"flow":"",
                "tgId":0,"subId":""}]}""";
        server.stubFor(get(urlEqualTo(inboundPath()))
                .willReturn(json(inboundResponse("vless", settings))));

        var inbound = client.getInbound(budget);
        var parsed = client.parseSettings(inbound);

        assertThat(parsed.clients()).singleElement()
                .satisfies(value -> {
                    assertThat(value.id()).isEqualTo("test-client");
                    assertThat(value.expiryTime()).isEqualTo(12345L);
                });
    }

    @Test
    void shouldRejectUnsupportedProtocol() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .willReturn(json(inboundResponse("trojan", "[]"))));

        assertThatThrownBy(() -> client.getInbound(budget))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Configured 3x-ui inbound is not VLESS");
    }

    @Test
    void shouldRejectMalformedSettingsWithoutExposingThem() {
        assertThatThrownBy(() -> client.parseSettings(
                new ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse(
                        42, 443, "vless", "{secret-invalid", "{sensitive}")))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Invalid 3x-ui inbound settings format")
                .hasMessageNotContaining("secret-invalid")
                .hasMessageNotContaining("sensitive");
    }

    @Test
    void shouldSendSettingsAsSerializedJsonString() {
        stubLogin(COOKIE);
        server.stubFor(post(urlEqualTo(WEB_PATH + "/panel/api/inbounds/addClient"))
                .willReturn(json("{\"success\":true,\"msg\":\"\",\"obj\":null}")));
        ThreeXUiVlessClient vlessClient =
                ThreeXUiVlessClient.create("test-client", "test-email", 12345);
        String settings = client.serializeSettings(
                new ThreeXUiInboundSettings(List.of(vlessClient)));

        client.addClient(new ThreeXUiClientRequest(42, settings), budget);

        server.verify(postRequestedFor(
                        urlEqualTo(WEB_PATH + "/panel/api/inbounds/addClient"))
                .withRequestBody(equalTo("""
                        {"id":42,"settings":"{\\"clients\\":[{\\"id\\":\\"test-client\\",\\"flow\\":\\"\\",\\"email\\":\\"test-email\\",\\"limitIp\\":0,\\"totalGB\\":0,\\"expiryTime\\":12345,\\"enable\\":true,\\"tgId\\":0,\\"subId\\":\\"\\"}]}"}""")));
    }

    @Test
    void updateMustSendOnlyTargetClientAndPreserveUnknownFields() throws Exception {
        String serviceId = "20000000-0000-0000-0000-000000000001";
        String targetId = "20000000-0000-0000-0000-000000000002";
        long oldExpiry = 1000L;
        long newExpiry = 2000L;
        String beforeSettings = """
                {"clients":[
                  {"id":"%s","email":"service","enable":true,"expiryTime":0},
                  {"id":"%s","email":"target","enable":true,"expiryTime":%d,
                   "totalGB":0,"limitIp":0,"flow":"","tgId":0,"subId":"",
                   "customField":{"enabled":true}}
                ]}""".formatted(serviceId, targetId, oldExpiry);
        String afterSettings = beforeSettings.replace(
                "\"expiryTime\":" + oldExpiry,
                "\"expiryTime\":" + newExpiry);
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("update-target")
                .whenScenarioStateIs("Started")
                .willReturn(json(inboundResponse("vless", beforeSettings)))
                .willSetStateTo("read"));
        server.stubFor(post(urlEqualTo(
                        WEB_PATH + "/panel/api/inbounds/updateClient/" + targetId))
                .inScenario("update-target")
                .whenScenarioStateIs("read")
                .willReturn(json("{\"success\":true,\"msg\":\"\",\"obj\":null}"))
                .willSetStateTo("updated"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("update-target")
                .whenScenarioStateIs("updated")
                .willReturn(json(inboundResponse("vless", afterSettings))));

        provider(8).extend(new VpnExtensionRequest(
                targetId, java.time.Instant.ofEpochMilli(newExpiry)));

        var requestEvent = server.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl()
                        .endsWith("/updateClient/" + targetId))
                .findFirst()
                .orElseThrow();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode requestBody = mapper.readTree(
                requestEvent.getRequest().getBodyAsString());
        JsonNode settings = mapper.readTree(requestBody.path("settings").asText());

        assertThat(requestBody.path("id").asInt()).isEqualTo(42);
        assertThat(settings.path("clients").size()).isEqualTo(1);
        JsonNode sentClient = settings.path("clients").get(0);
        assertThat(sentClient.path("id").asText()).isEqualTo(targetId);
        assertThat(sentClient.path("id").asText()).isNotEqualTo(serviceId);
        assertThat(sentClient.path("expiryTime").asLong()).isEqualTo(newExpiry);
        assertThat(sentClient.path("email").asText()).isEqualTo("target");
        assertThat(sentClient.path("enable").asBoolean()).isTrue();
        assertThat(sentClient.path("customField").path("enabled").asBoolean()).isTrue();
    }

    @Test
    void failedUpdateEnvelopeMustNotExposeResponseOrClientData() {
        String clientId = UUID.fromString(
                "20000000-0000-0000-0000-000000000003").toString();
        String secretMarker = "SENSITIVE_UPDATE_MARKER";
        stubLogin(COOKIE);
        server.stubFor(post(urlEqualTo(
                        WEB_PATH + "/panel/api/inbounds/updateClient/" + clientId))
                .willReturn(json("{\"success\":false,\"msg\":\""
                        + secretMarker + "\",\"obj\":null}")));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> client.updateClient(
                        clientId,
                        new ThreeXUiClientRequest(42,
                                "{\"clients\":[{\"id\":\"" + clientId + "\"}]}"),
                        budget));

        java.io.StringWriter stack = new java.io.StringWriter();
        thrown.printStackTrace(new java.io.PrintWriter(stack));
        assertThat(thrown).isInstanceOf(ThreeXUiException.class)
                .hasMessage("3x-ui rejected operation: update client");
        assertThat(stack.toString())
                .doesNotContain(secretMarker)
                .doesNotContain(clientId);
    }

    @Test
    void shouldNotRetryBusinessFailure() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .willReturn(json("{\"success\":false,\"msg\":\"rejected\",\"obj\":null}")));

        assertThatThrownBy(() -> client.getInbound(budget))
                .isInstanceOf(ThreeXUiException.class);

        server.verify(1, getRequestedFor(urlEqualTo(inboundPath())));
    }

    @Test
    void shouldLeaveRetryBudgetToProvider() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.getInbound(budget))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiRetryableException.class);

        server.verify(1, getRequestedFor(urlEqualTo(inboundPath())));
    }

    @Test
    void providerShouldUseExactlyConfiguredAttemptsForPersistent503() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .willReturn(aResponse().withStatus(503)));
        ThreeXUiVpnProvider provider = new ThreeXUiVpnProvider(
                client,
                new UnsupportedVpnConfigurationFactory(),
                properties());

        assertThatThrownBy(() -> provider.provision(new VpnProvisionRequest(
                java.util.UUID.randomUUID(), 1L, java.time.Instant.now())))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiUncertainException.class);

        server.verify(3, getRequestedFor(urlEqualTo(inboundPath())));
        server.verify(1, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
    }

    @Test
    void successfulCreateMustUseFourRequests() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("create-success")
                .whenScenarioStateIs("Started")
                .willReturn(json(inboundResponse("vless", "[]")))
                .willSetStateTo("created"));
        server.stubFor(post(urlEqualTo(WEB_PATH + "/panel/api/inbounds/addClient"))
                .willReturn(json("{\"success\":true,\"msg\":\"\",\"obj\":null}")));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("create-success")
                .whenScenarioStateIs("created")
                .willReturn(json(inboundResponse("vless",
                        "[{\"id\":\"10000000-0000-0000-0000-000000000001\","
                                + "\"email\":\"test\",\"enable\":true,"
                                + "\"expiryTime\":12345}]"))));

        provider(8).provision(new VpnProvisionRequest(
                java.util.UUID.fromString(
                        "10000000-0000-0000-0000-000000000001"),
                1L,
                java.time.Instant.ofEpochMilli(12345)));

        server.verify(1, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
        server.verify(2, getRequestedFor(urlEqualTo(inboundPath())));
        server.verify(1, postRequestedFor(
                urlEqualTo(WEB_PATH + "/panel/api/inbounds/addClient")));
        assertThat(server.getAllServeEvents()).hasSize(4);
    }

    @Test
    void uncertainCreateMustNeverExceedSharedRequestBudget() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("budget")
                .whenScenarioStateIs("Started")
                .willReturn(json(inboundResponse("vless", "[]")))
                .willSetStateTo("pre-one"));
        server.stubFor(post(urlEqualTo(WEB_PATH + "/panel/api/inbounds/addClient"))
                .inScenario("budget")
                .whenScenarioStateIs("pre-one")
                .willReturn(json("{\"success\":true,\"msg\":\"\",\"obj\":null}"))
                .willSetStateTo("mutation-one"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("budget").whenScenarioStateIs("mutation-one")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("confirm-one"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("budget").whenScenarioStateIs("confirm-one")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovery-one"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("budget").whenScenarioStateIs("recovery-one")
                .willReturn(json(inboundResponse("vless", "[]")))
                .willSetStateTo("pre-two"));
        server.stubFor(post(urlEqualTo(WEB_PATH + "/panel/api/inbounds/addClient"))
                .inScenario("budget").whenScenarioStateIs("pre-two")
                .willReturn(json("{\"success\":true,\"msg\":\"\",\"obj\":null}"))
                .willSetStateTo("mutation-two"));
        server.stubFor(get(urlEqualTo(inboundPath()))
                .inScenario("budget").whenScenarioStateIs("mutation-two")
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider(8).provision(new VpnProvisionRequest(
                java.util.UUID.fromString(
                        "10000000-0000-0000-0000-000000000002"),
                1L,
                java.time.Instant.now())))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiUncertainException.class);

        assertThat(server.getAllServeEvents()).hasSize(8);
        server.verify(1, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
        server.verify(5, getRequestedFor(urlEqualTo(inboundPath())));
        server.verify(2, postRequestedFor(
                urlEqualTo(WEB_PATH + "/panel/api/inbounds/addClient")));
    }

    @Test
    void exhaustedBudgetBeforeMutationMustNotSendMutation() {
        stubLogin(COOKIE);
        server.stubFor(get(urlEqualTo(inboundPath()))
                .willReturn(json(inboundResponse("vless", "[]"))));

        assertThatThrownBy(() -> provider(2).provision(new VpnProvisionRequest(
                java.util.UUID.randomUUID(), 1L, java.time.Instant.now())))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiUncertainException.class);

        server.verify(0, postRequestedFor(
                urlEqualTo(WEB_PATH + "/panel/api/inbounds/addClient")));
        assertThat(server.getAllServeEvents()).hasSize(2);
    }

    @Test
    void malformedSettingsThrowableMustNotContainSensitiveSource() {
        String marker = "REALITY_PRIVATE_MARKER";
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> client.parseSettings(
                        new ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse(
                                42, 443, "vless", "{\"" + marker + "\"", marker)));

        java.io.StringWriter output = new java.io.StringWriter();
        thrown.printStackTrace(new java.io.PrintWriter(output));
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            assertThat(current.getMessage()).doesNotContain(marker);
            assertThat(current.toString()).doesNotContain(marker);
        }
        assertThat(output.toString()).doesNotContain(marker);
    }

    @Test
    void shouldNotRetryInvalidCredentials() {
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.getInbound(budget))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiAuthenticationException.class);

        server.verify(1, postRequestedFor(urlEqualTo(WEB_PATH + "/login")));
        server.verify(0, getRequestedFor(urlEqualTo(inboundPath())));
    }

    private ThreeXUiProperties properties() {
        return new ThreeXUiProperties(
                URI.create(server.baseUrl()),
                WEB_PATH,
                "test-user",
                "test-password",
                42,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                3,
                8,
                Duration.ZERO,
                Duration.ZERO);
    }

    private ThreeXUiVpnProvider provider(int maximumRequests) {
        ThreeXUiProperties base = properties();
        ThreeXUiProperties configured = new ThreeXUiProperties(
                base.baseUrl(), base.webBasePath(), base.username(), base.password(),
                base.inboundId(), base.connectTimeout(), base.readTimeout(),
                base.maxMutationAttempts(), maximumRequests,
                base.retryInitialDelay(), base.retryMaxDelay());
        return new ThreeXUiVpnProvider(
                client, new UnsupportedVpnConfigurationFactory(), configured);
    }

    private String inboundPath() {
        return WEB_PATH + "/panel/api/inbounds/get/42";
    }

    private void stubLogin(String cookie) {
        server.stubFor(post(urlEqualTo(WEB_PATH + "/login"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Set-Cookie", cookie + "; Path=/; HttpOnly")
                        .withBody("{\"success\":true,\"msg\":\"\",\"obj\":null}")));
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
            String body
    ) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private String inboundResponse(String protocol, String clientsOrSettings) {
        String settings = clientsOrSettings.startsWith("[")
                ? "{\"clients\":" + clientsOrSettings + "}"
                : clientsOrSettings;
        String escaped = settings.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return "{\"success\":true,\"msg\":\"\",\"obj\":{\"id\":42,\"port\":443,"
                + "\"protocol\":\"" + protocol + "\",\"settings\":\"" + escaped
                + "\",\"streamSettings\":\"{}\"}}";
    }
}
