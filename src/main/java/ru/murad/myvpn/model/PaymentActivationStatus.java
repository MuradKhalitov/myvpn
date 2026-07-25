package ru.murad.myvpn.model;

public enum PaymentActivationStatus {
    NOT_READY,
    PENDING,
    PROCESSING,
    ACTIVATED,
    RETRY_REQUIRED,
    RECONCILIATION_REQUIRED,
    MANUAL_REVIEW_REQUIRED
}
