package ru.murad.myvpn.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.murad.myvpn.application.auth.EmailSender;
import ru.murad.myvpn.repository.AccountIdentityRepository;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.AuthSessionRepository;
import ru.murad.myvpn.repository.EmailOtpChallengeRepository;
import ru.murad.myvpn.repository.DeviceCredentialRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.PhoneVerificationRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AuthIntegrationTestSupport {
    protected static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:16.3-alpine");
    private static final KeyPair KEY_PAIR = generateKeyPair();
    static { POSTGRESQL.start(); }
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("auth.enabled", () -> "true");
        registry.add("auth.email-from", () -> "no-reply@example.test");
        registry.add("auth.otp.pepper", () -> "test-only-otp-pepper-at-least-32-bytes");
        registry.add("auth.refresh.pepper", () -> "test-only-refresh-pepper-at-least-32-bytes");
        registry.add("security.device.secret-pepper", () -> "test-only-device-pepper-at-least-32-bytes");
        registry.add("auth.jwt.private-key-base64", () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded()));
        registry.add("auth.jwt.public-key-base64", () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
        registry.add("management.health.mail.enabled", () -> "false");
    }
    @MockBean protected EmailSender emailSender;
    @Autowired protected WebTestClient webTestClient;
    @Autowired protected EmailOtpChallengeRepository challengeRepository;
    @Autowired protected DeviceCredentialRepository deviceCredentialRepository;
    @Autowired protected AuthSessionRepository sessionRepository;
    @Autowired protected AccountIdentityRepository identityRepository;
    @Autowired protected AccountRepository accountRepository;
    @Autowired protected VpnAccessRepository vpnAccessRepository;
    @Autowired protected PhoneVerificationRepository phoneVerificationRepository;
    @Autowired protected JwtDecoder jwtDecoder;
    @BeforeEach void cleanAuthData() {
        phoneVerificationRepository.deleteAll(); vpnAccessRepository.deleteAll();
        sessionRepository.deleteAll(); challengeRepository.deleteAll(); deviceCredentialRepository.deleteAll();
        identityRepository.deleteAll(); accountRepository.deleteAll();
    }
    private static KeyPair generateKeyPair() {
        try { KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
