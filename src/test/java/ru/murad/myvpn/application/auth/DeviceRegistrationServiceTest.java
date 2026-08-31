package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountIdentity;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.AuthSession;
import ru.murad.myvpn.model.DeviceCredential;
import ru.murad.myvpn.repository.AccountIdentityRepository;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.AuthSessionRepository;
import ru.murad.myvpn.repository.DeviceCredentialRepository;
import ru.murad.myvpn.service.AccountVpnAccessService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DeviceRegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final String INSTALL_ID = "8b60d9a2-59ed-42a8-a0cb-6b95797a5732";
    private static final String SECRET = "A".repeat(43);

    @Mock private AccountRepository accountRepository;
    @Mock private AccountIdentityRepository identityRepository;
    @Mock private DeviceCredentialRepository credentialRepository;
    @Mock private AuthSessionRepository sessionRepository;
    @Mock private RefreshTokenHashService refreshTokenHashService;
    @Mock private RefreshTokenGenerator refreshTokenGenerator;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private AccountVpnAccessService vpnAccessService;
    @Mock private AuthProperties properties;
    @Mock private AuthProperties.Refresh refresh;
    @Mock private AuthProperties.Jwt jwt;
    @Spy private DeviceSecretHashService deviceSecretHashService =
            new DeviceSecretHashService("device-test-pepper-at-least-32-bytes");
    @Spy private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks private DeviceRegistrationService service;

    @BeforeEach
    void setUp() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        lenient().when(properties.refresh()).thenReturn(refresh);
        lenient().when(refresh.ttl()).thenReturn(Duration.ofDays(30));
        lenient().when(properties.jwt()).thenReturn(jwt);
        lenient().when(jwt.accessTtl()).thenReturn(Duration.ofMinutes(15));
        lenient().when(refreshTokenGenerator.generate()).thenReturn("refresh-token");
        lenient().when(refreshTokenHashService.hash("refresh-token")).thenReturn("f".repeat(64));
        lenient().when(jwtTokenService.issue(any(), any())).thenReturn("access-token");
        lenient().when(sessionRepository.save(any(AuthSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void createsAccountDeviceIdentityAndHashedCredentialWithoutSubscription() {
        when(identityRepository.findByTypeAndNormalizedSubject(AccountIdentityType.DEVICE, INSTALL_ID))
                .thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(identityRepository.save(any(AccountIdentity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(credentialRepository.save(any(DeviceCredential.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceRegistrationResult result = service.register(INSTALL_ID, SECRET);

        ArgumentCaptor<AccountIdentity> identity = ArgumentCaptor.forClass(AccountIdentity.class);
        ArgumentCaptor<DeviceCredential> credential = ArgumentCaptor.forClass(DeviceCredential.class);
        verify(identityRepository).save(identity.capture());
        verify(credentialRepository).save(credential.capture());
        assertThat(result.accountId()).isEqualTo(identity.getValue().getAccount().getId());
        assertThat(identity.getValue().getType()).isEqualTo(AccountIdentityType.DEVICE);
        assertThat(identity.getValue().getNormalizedSubject()).isEqualTo(INSTALL_ID);
        assertThat(credential.getValue().getSecretHash()).isNotEqualTo(SECRET).hasSize(64);
        assertThat(result.vpnStatus()).isEqualTo("PROVISIONING");
    }

    @Test
    void repeatsAuthenticationForExistingDeviceWithoutCreatingIdentityOrCredential() {
        Account account = Account.builder().id(UUID.randomUUID()).status(AccountStatus.ACTIVE)
                .createdAt(NOW).updatedAt(NOW).build();
        AccountIdentity identity = AccountIdentity.builder().id(UUID.randomUUID()).account(account)
                .type(AccountIdentityType.DEVICE).subject(INSTALL_ID).normalizedSubject(INSTALL_ID)
                .createdAt(NOW).updatedAt(NOW).build();
        DeviceCredential credential = DeviceCredential.builder().id(UUID.randomUUID()).identity(identity)
                .secretHash(deviceSecretHashService.hash(SECRET)).createdAt(NOW).updatedAt(NOW).build();
        when(identityRepository.findByTypeAndNormalizedSubject(AccountIdentityType.DEVICE, INSTALL_ID))
                .thenReturn(Optional.of(identity));
        when(credentialRepository.findByIdentityId(identity.getId())).thenReturn(Optional.of(credential));

        DeviceRegistrationResult result = service.register(INSTALL_ID, SECRET);

        assertThat(result.accountId()).isEqualTo(account.getId());
        assertThat(credential.getLastUsedAt()).isEqualTo(NOW);
        verify(accountRepository, never()).save(any());
        verify(identityRepository, never()).save(any());
        verify(credentialRepository, never()).save(any());
    }

    @Test
    void rejectsWrongSecretWithoutCreatingSession() {
        Account account = Account.builder().id(UUID.randomUUID()).status(AccountStatus.ACTIVE)
                .createdAt(NOW).updatedAt(NOW).build();
        AccountIdentity identity = AccountIdentity.builder().id(UUID.randomUUID()).account(account)
                .type(AccountIdentityType.DEVICE).subject(INSTALL_ID).normalizedSubject(INSTALL_ID)
                .createdAt(NOW).updatedAt(NOW).build();
        DeviceCredential credential = DeviceCredential.builder().id(UUID.randomUUID()).identity(identity)
                .secretHash(deviceSecretHashService.hash(SECRET)).createdAt(NOW).updatedAt(NOW).build();
        when(identityRepository.findByTypeAndNormalizedSubject(AccountIdentityType.DEVICE, INSTALL_ID))
                .thenReturn(Optional.of(identity));
        when(credentialRepository.findByIdentityId(identity.getId())).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.register(INSTALL_ID, "B".repeat(43)))
                .isInstanceOf(InvalidAuthenticationException.class);
        verify(sessionRepository, never()).save(any());
    }
}
