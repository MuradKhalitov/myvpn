package ru.murad.myvpn.client;

import ru.murad.myvpn.exception.ThreeXUiUncertainException;

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
            throw new ThreeXUiUncertainException();
        }
        reconciliationReserved = true;
    }

    public synchronized void acquire() {
        int reserved = reconciliationReserved ? 1 : 0;
        if (remaining <= reserved) {
            throw new ThreeXUiUncertainException();
        }
        remaining--;
    }

    public synchronized void acquireReconciliation() {
        if (remaining == 0) {
            throw new ThreeXUiUncertainException();
        }
        remaining--;
        reconciliationReserved = false;
    }

    public synchronized int remaining() {
        return remaining;
    }
}
