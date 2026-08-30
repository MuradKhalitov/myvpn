package ru.murad.myvpn.application.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.repository.AuthSessionRepository;

import java.time.Clock;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LogoutService {

    private final AuthSessionRepository sessionRepository;
    private final Clock clock;

    @Transactional
    public void logout(UUID sessionId, UUID accountId) {
        sessionRepository.findByIdForUpdate(sessionId).ifPresent(session -> {
            if (session.getAccount().getId().equals(accountId)) {
                session.revoke(clock.instant());
            }
        });
    }
}
