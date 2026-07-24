package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class ThreeXUiSessionManager {

    private final ThreeXUiAuthClient authClient;
    private volatile String sessionCookie;

    public ThreeXUiSessionManager(ThreeXUiAuthClient authClient) {
        this.authClient = authClient;
    }

    public String getSessionCookie(ThreeXUiRequestBudget budget) {
        String current = sessionCookie;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (sessionCookie == null) {
                sessionCookie = authClient.login(budget);
            }
            return sessionCookie;
        }
    }

    public synchronized void invalidate(String rejectedCookie) {
        if (rejectedCookie != null && rejectedCookie.equals(sessionCookie)) {
            sessionCookie = null;
        }
    }
}
