package ru.murad.myvpn.dto;
import java.math.BigDecimal;import java.net.URI;import java.time.Instant;import java.util.UUID;
import ru.murad.myvpn.model.*;
public record PreparedCheckout(UUID orderId,UUID accountId,UUID tariffId,PaymentProviderType provider,UUID idempotenceKey,BigDecimal amount,String currency,String tariffCode,String tariffName,int durationDays,PaymentStatus status,URI confirmationUrl,Instant localExpiresAt) { }
