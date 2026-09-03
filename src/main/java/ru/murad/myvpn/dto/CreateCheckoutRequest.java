package ru.murad.myvpn.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCheckoutRequest(@NotBlank String tariffCode) {
}
