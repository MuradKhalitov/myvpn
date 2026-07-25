package ru.murad.myvpn.exception;
public class ProviderPaymentValidationException extends RuntimeException {
    private final String safeFailureCode;
    private final boolean manualReview;
    public ProviderPaymentValidationException(String code, boolean manualReview) {
        super("Provider payment validation failed"); this.safeFailureCode = code; this.manualReview = manualReview;
    }
    public String safeFailureCode() { return safeFailureCode; }
    public boolean manualReview() { return manualReview; }
}
