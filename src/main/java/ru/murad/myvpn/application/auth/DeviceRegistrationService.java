package ru.murad.myvpn.application.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DeviceRegistrationService {

    private final AccountRepository accountRepository;
    private final AccountIdentityRepository identityRepository;
    private final DeviceCredentialRepository credentialRepository;
    private final AuthSessionRepository sessionRepository;
    private final DeviceSecretHashService deviceSecretHashService;
    private final RefreshTokenHashService refreshTokenHashService;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final JwtTokenService jwtTokenService;
    private final AuthProperties properties;
    private final AccountVpnAccessService vpnAccessService;
    private final Clock clock;

    @Transactional
    public DeviceRegistrationResult register(String installId, String deviceSecret) {
        validate(installId, deviceSecret);
        // A database constraint remains the final guard; this lock makes the
        // ordinary same-install race deterministic without orphan accounts.
        identityRepository.lockDeviceRegistration(installId);

        Instant now = clock.instant();
        AccountIdentity identity = identityRepository
                .findByTypeAndNormalizedSubject(AccountIdentityType.DEVICE, installId)
                .orElse(null);
        Account account;
        if (identity == null) {
            account = createAccount(installId, deviceSecret, now);
        } else {
            account = authenticate(identity, deviceSecret, now);
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidAuthenticationException();
        }

        String refreshToken = refreshTokenGenerator.generate();
        AuthSession session = sessionRepository.save(AuthSession.builder()
                .id(UUID.randomUUID())
                .account(account)
                .refreshTokenHash(refreshTokenHashService.hash(refreshToken))
                .tokenFamilyId(UUID.randomUUID())
                .rotationCounter(0)
                .createdAt(now)
                .build());
        return new DeviceRegistrationResult(
                account.getId(),
                new AuthTokens(jwtTokenService.issue(account.getId(), session.getId()), refreshToken,
                        properties.jwt().accessTtl()),
                "PROVISIONING");
    }

    private Account createAccount(String installId, String deviceSecret, Instant now) {
        Account account = accountRepository.save(Account.builder()
                .id(UUID.randomUUID())
                .status(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        AccountIdentity identity = identityRepository.save(AccountIdentity.builder()
                .id(UUID.randomUUID())
                .account(account)
                .type(AccountIdentityType.DEVICE)
                .subject(installId)
                .normalizedSubject(installId)
                .verifiedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        credentialRepository.save(DeviceCredential.builder()
                .id(UUID.randomUUID())
                .identity(identity)
                .secretHash(deviceSecretHashService.hash(deviceSecret))
                .createdAt(now)
                .updatedAt(now)
                .build());
        scheduleFreeProvisioning(account.getId());
        return account;
    }

    private Account authenticate(AccountIdentity identity, String deviceSecret, Instant now) {
        DeviceCredential credential = credentialRepository.findByIdentityId(identity.getId())
                .orElseThrow(InvalidAuthenticationException::new);
        if (!credential.usable() || !deviceSecretHashService.matches(deviceSecret, credential.getSecretHash())) {
            throw new InvalidAuthenticationException();
        }
        credential.used(now);
        return identity.getAccount();
    }

    private void scheduleFreeProvisioning(UUID accountId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    vpnAccessService.ensureFreeVpnAccess(accountId);
                } catch (RuntimeException ignored) {
                    // The account/session is already committed. Reconciliation
                    // handles provider retry without exposing provider data.
                }
            }
        });
    }

    private void validate(String installId, String deviceSecret) {
        try {
            UUID parsed = UUID.fromString(installId);
            if (!parsed.toString().equals(installId)) {
                throw new InvalidAuthenticationException();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidAuthenticationException();
        }
        if (deviceSecret == null || !deviceSecret.matches("[A-Za-z0-9_-]{43,}")) {
            throw new InvalidAuthenticationException();
        }
    }
}
