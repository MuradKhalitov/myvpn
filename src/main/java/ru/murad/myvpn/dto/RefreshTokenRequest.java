package ru.murad.myvpn.dto;

public record RefreshTokenRequest(String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=<redacted>]";
    }
}
