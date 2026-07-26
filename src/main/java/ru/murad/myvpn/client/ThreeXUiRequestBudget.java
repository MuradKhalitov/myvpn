package ru.murad.myvpn.client;

import ru.murad.myvpn.exception.ThreeXUiUncertainException;
import ru.murad.myvpn.exception.VpnProviderFailureCode;

public final class ThreeXUiRequestBudget {

    private int remaining;
    private boolean reconciliationReserved;

    public ThreeXUiRequestBudget(int maximumRequests) {
        if (maximumRequests < 1) {
            throw new IllegalArgumentException("Request budget must be positive");
        }
        this.remaining = maximumRequests;
    }

    public synchronized void reserveReconciliation() {
        if (remaining < 2) {
            throw new ThreeXUiUncertainException(VpnProviderFailureCode.REQUEST_BUDGET_EXHAUSTED);
        }
        reconciliationReserved = true;
    }

    public synchronized void acquire() {
        int reserved = reconciliationReserved ? 1 : 0;
        if (remaining <= reserved) {
            throw new ThreeXUiUncertainException(VpnProviderFailureCode.REQUEST_BUDGET_EXHAUSTED);
        }
        remaining--;
    }

    public synchronized void acquireReconciliation() {
        if (remaining == 0) {
            throw new ThreeXUiUncertainException(VpnProviderFailureCode.REQUEST_BUDGET_EXHAUSTED);
        }
        remaining--;
        reconciliationReserved = false;
    }

    public synchronized int remaining() {
        return remaining;
    }
}
