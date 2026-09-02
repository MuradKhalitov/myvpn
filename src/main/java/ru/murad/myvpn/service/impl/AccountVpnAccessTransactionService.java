package ru.murad.myvpn.service.impl;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.model.VpnPolicyStatus;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import java.time.Instant;
import java.util.UUID;

@Service
public class AccountVpnAccessTransactionService {
    private final AccountRepository accounts;
    private final VpnAccessRepository accesses;
    private final EntityManager entityManager;

    public AccountVpnAccessTransactionService(AccountRepository accounts, VpnAccessRepository accesses,
            EntityManager entityManager) {
        this.accounts = accounts; this.accesses = accesses; this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VpnAccess reserveFree(UUID accountId, String providerName, Instant start, Instant end) {
        return accesses.findFirstByAccountIdAndSubscriptionIsNull(accountId).orElseGet(() -> {
            if (!accounts.existsById(accountId)) throw new IllegalArgumentException("Account not found");
            return accesses.save(VpnAccess.builder().id(UUID.randomUUID())
                    .account(entityManager.getReference(Account.class, accountId))
                    .providerName(providerName).externalAccessId(accountId.toString())
                    .providerClientKey("acc_" + accountId)
                    .status(VpnAccessStatus.PROVISIONING).issuedAt(start).createdAt(start).updatedAt(start)
                    .desiredEntitlement(VpnEntitlement.FREE).policyStatus(VpnPolicyStatus.PENDING)
                    .policyGeneration(1).nextPolicyAttemptAt(start)
                    .quotaPeriodStartedAt(start).quotaPeriodEndsAt(end).build());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeFree(UUID accessId, long generation, String providerName,
            String configurationData, Instant now) {
        return accesses.findByIdForUpdate(accessId).map(access -> {
            if (access.getPolicyGeneration() != generation) return false;
            access.completeProvisioning(providerName, configurationData, now);
            return access.applyPolicy(generation, now);
        }).orElse(false);
    }
}
