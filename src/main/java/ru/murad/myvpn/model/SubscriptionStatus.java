package ru.murad.myvpn.model;

public enum SubscriptionStatus {
    PENDING,
    RECONCILIATION_REQUIRED,
    MANUAL_REVIEW_REQUIRED,
    ACTIVE,
    FAILED,
    EXPIRED,
    REVOKED
}
