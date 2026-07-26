package ru.murad.myvpn.service;

/** Safe operational counters; no delivery identifiers, recipients, or failure details. */
public record VpnDeliveryWorkerResult(int claimed, int exhausted, int delivered, int retryScheduled,
        int manualReview, int skipped, int infrastructureFailures) {
    public VpnDeliveryWorkerResult {
        if (claimed < 0 || exhausted < 0 || delivered < 0 || retryScheduled < 0 || manualReview < 0
                || skipped < 0 || infrastructureFailures < 0) throw new IllegalArgumentException("Negative delivery worker counter");
        if (claimed != delivered + retryScheduled + manualReview + skipped + infrastructureFailures) {
            throw new IllegalArgumentException("Delivery worker counter conservation violated");
        }
    }
    @Override public String toString() { return "VpnDeliveryWorkerResult[claimed=" + claimed + ", exhausted=" + exhausted
            + ", delivered=" + delivered + ", retryScheduled=" + retryScheduled + ", manualReview=" + manualReview
            + ", skipped=" + skipped + ", infrastructureFailures=" + infrastructureFailures + "]"; }
}
