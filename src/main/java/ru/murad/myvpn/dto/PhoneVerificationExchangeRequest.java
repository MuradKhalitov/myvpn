package ru.murad.myvpn.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
public record PhoneVerificationExchangeRequest(@NotNull UUID verificationId, @NotBlank String exchangeToken) { }
