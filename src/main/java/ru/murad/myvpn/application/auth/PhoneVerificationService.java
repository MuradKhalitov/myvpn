package ru.murad.myvpn.application.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import ru.murad.myvpn.client.PhoneVerificationProvider;
import ru.murad.myvpn.client.PhoneVerificationStart;
import ru.murad.myvpn.client.PhoneVerificationState;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.*;
import ru.murad.myvpn.service.AccountVpnAccessService;
import java.time.*; import java.util.*;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name = {"auth.enabled", "sms.ru.enabled"}, havingValue = "true")
public class PhoneVerificationService {
    private final PhoneNormalizer normalizer; private final PhoneVerificationProvider provider;
    private final PhoneVerificationRepository verifications; private final AccountIdentityRepository identities;
    private final AccountRepository accounts; private final SubscriptionRepository subscriptions; private final AuthSessionRepository sessions;
    private final RefreshTokenGenerator tokens; private final RefreshTokenHashService refreshHash;
    private final JwtTokenService jwt; private final AuthProperties auth; private final AccountVpnAccessService access;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional public PhoneVerificationStartResponse start(String phone, String requestIp) {
        String normalized = normalizer.normalize(phone); Instant now = clock.instant(); verifications.lockPhone(normalized);
        var active = verifications.findFirstByPhoneAndStatusOrderByCreatedAtDesc(normalized, PhoneVerificationStatus.PENDING)
                .filter(value -> value.activeAt(now));
        if (active.isPresent()) return response(active.get());
        if (verifications.findFirstByPhoneOrderByCreatedAtDesc(normalized)
                .filter(value -> value.getCreatedAt().plus(Duration.ofMinutes(1)).isAfter(now)).isPresent()
                || verifications.countByPhoneAndCreatedAtAfter(normalized, now.minus(Duration.ofMinutes(15))) >= 3
                || requestIp != null && verifications.countByRequestIpAndCreatedAtAfter(requestIp, now.minus(Duration.ofMinutes(15))) >= 10) {
            throw new InvalidAuthenticationException();
        }
        return create(normalized, requestIp, provider.start(normalized));
    }
    private PhoneVerificationStartResponse create(String phone, String requestIp, PhoneVerificationStart started) {
        Instant now = clock.instant();
        PhoneVerification verification = verifications.save(PhoneVerification.builder().id(UUID.randomUUID()).phone(phone).requestIp(requestIp)
                .provider("SMS_RU").providerCheckId(started.externalCheckId()).status(PhoneVerificationStatus.PENDING)
                .callPhone(started.callPhone()).callPhonePretty(started.callPhonePretty())
                .createdAt(now).expiresAt(started.expiresAt()).build());
        return response(verification);
    }
    private PhoneVerificationStartResponse response(PhoneVerification verification) {
        return new PhoneVerificationStartResponse(verification.getId(), verification.getCallPhone(), verification.getCallPhonePretty(), verification.getExpiresAt());
    }
    @Transactional public PhoneVerificationStatusResponse status(UUID id) {
        PhoneVerification verification = verifications.findById(id).orElseThrow(InvalidAuthenticationException::new);
        Instant now = clock.instant(); if (!verification.activeAt(now)) return new PhoneVerificationStatusResponse(verification.getStatus().name(), null);
        PhoneVerificationState state = provider.getStatus(verification.getProviderCheckId());
        // The first read must not satisfy the subsequent FOR UPDATE from the
        // persistence context; force the database lock for concurrent polls.
        entityManager.clear();
        return resolve(id, state);
    }
    private PhoneVerificationStatusResponse resolve(UUID id, PhoneVerificationState state) {
        PhoneVerification verification = verifications.findByIdForUpdate(id).orElseThrow(InvalidAuthenticationException::new);
        Instant now = clock.instant();
        if (!verification.activeAt(now)) { verification.expire(now); return new PhoneVerificationStatusResponse(verification.getStatus().name(), null); }
        if (state == PhoneVerificationState.PENDING) return new PhoneVerificationStatusResponse("PENDING", null);
        if (state == PhoneVerificationState.EXPIRED) { verification.expire(now); return new PhoneVerificationStatusResponse("EXPIRED", null); }
        verifications.lockPhone(verification.getPhone());
        Account account = identities.findByTypeAndNormalizedSubject(AccountIdentityType.PHONE, verification.getPhone())
                .map(AccountIdentity::getAccount).orElseGet(() -> createAccount(verification.getPhone(), now));
        String exchange = tokens.generate(); verification.verify(account.getId(), refreshHash.hash(exchange), now.plus(Duration.ofMinutes(2)), now);
        return new PhoneVerificationStatusResponse("VERIFIED", exchange);
    }
    private Account createAccount(String phone, Instant now) {
        Account account = accounts.save(Account.builder().id(UUID.randomUUID()).status(AccountStatus.ACTIVE).createdAt(now).updatedAt(now).build());
        account.grantTrial(now, now.plus(Duration.ofDays(5))); accounts.save(account);
        identities.save(AccountIdentity.builder().id(UUID.randomUUID()).account(account).type(AccountIdentityType.PHONE)
                .subject(phone).normalizedSubject(phone).verifiedAt(now).createdAt(now).updatedAt(now).build());
        access.ensureTrialVpnAccess(account.getId()); return account;
    }
    @Transactional
    public PhoneAuthResponse exchange(UUID verificationId, String exchangeToken) {
        PhoneVerification verification = verifications.findByIdForUpdate(verificationId).orElseThrow(InvalidAuthenticationException::new);
        Instant now = clock.instant(); if (!verification.consumeExchange(refreshHash.hash(exchangeToken), now)) throw new InvalidAuthenticationException();
        Account account = accounts.findById(verification.getAccountId()).orElseThrow(InvalidAuthenticationException::new);
        String refresh = tokens.generate(); AuthSession session = sessions.save(AuthSession.builder().id(UUID.randomUUID()).account(account)
                .refreshTokenHash(refreshHash.hash(refresh)).tokenFamilyId(UUID.randomUUID()).rotationCounter(0)
                .expiresAt(now.plus(auth.refresh().ttl())).createdAt(now).build());
        var premium = subscriptions.findFirstByAccountIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                account.getId(), SubscriptionStatus.ACTIVE, now).orElse(null);
        boolean trial = account.hasActiveTrialAt(now);
        return new PhoneAuthResponse(account.getId(), jwt.issue(account.getId(), session.getId()), refresh,
                "Bearer", auth.jwt().accessTtl().toSeconds(), premium != null ? "PREMIUM" : trial ? "TRIAL" : "EXPIRED",
                premium != null ? premium.getExpiresAt() : trial ? account.getTrialExpiresAt() : null);
    }
}
