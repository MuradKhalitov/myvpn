package ru.murad.myvpn.dto;

public record OtpRequest(String email) {

    @Override
    public String toString() {
        return "OtpRequest[email=<redacted>]";
    }
}
