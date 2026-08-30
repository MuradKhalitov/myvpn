package ru.murad.myvpn.dto;

public record OtpVerifyRequest(String email, String code) {

    @Override
    public String toString() {
        return "OtpVerifyRequest[email=<redacted>, code=<redacted>]";
    }
}
