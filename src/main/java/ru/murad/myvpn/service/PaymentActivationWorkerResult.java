package ru.murad.myvpn.service;

public record PaymentActivationWorkerResult(int claimed, int exhausted, int succeeded,
        int retryScheduled, int manualReview, int skipped, int infrastructureFailures) {
    public PaymentActivationWorkerResult {
        if (claimed < 0 || exhausted < 0 || succeeded < 0 || retryScheduled < 0
                || manualReview < 0 || skipped < 0 || infrastructureFailures < 0) {
            throw new IllegalArgumentException("Activation worker counters cannot be negative");
        }
        if (claimed != succeeded + retryScheduled + manualReview + skipped + infrastructureFailures) {
            throw new IllegalArgumentException("Activation worker outcome counters must equal claimed orders");
        }
    }

    public PaymentActivationWorkerResult(int claimed, int succeeded,
            int retryScheduled, int manualReview, int skipped, int infrastructureFailures) {
        this(claimed, 0, succeeded, retryScheduled, manualReview, skipped, infrastructureFailures);
    }

    public PaymentActivationWorkerResult(int claimed, int succeeded,
            int retryScheduled, int manualReview, int skipped) {
        this(claimed, 0, succeeded, retryScheduled, manualReview, skipped, 0);
    }

    @Override public String toString() { return "PaymentActivationWorkerResult[claimed=" + claimed
                + ", exhausted=" + exhausted + ", succeeded=" + succeeded + ", retryScheduled=" + retryScheduled
                + ", manualReview=" + manualReview + ", skipped=" + skipped
                + ", infrastructureFailures=" + infrastructureFailures + "]"; }
}
