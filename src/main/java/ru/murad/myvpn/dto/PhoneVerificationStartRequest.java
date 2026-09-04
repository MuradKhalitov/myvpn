package ru.murad.myvpn.dto;

import jakarta.validation.constraints.NotBlank;

public record PhoneVerificationStartRequest(@NotBlank String phone) { }
