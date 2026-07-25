package ru.murad.myvpn.service;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.exception.UnsupportedPaymentProviderException;
import ru.murad.myvpn.model.PaymentProviderType;
import java.util.EnumMap;
import java.util.List;
@Component
public class ProviderPaymentVerificationPolicyRegistry {
    private final EnumMap<PaymentProviderType, ProviderPaymentVerificationPolicy> policies = new EnumMap<>(PaymentProviderType.class);
    public ProviderPaymentVerificationPolicyRegistry(List<ProviderPaymentVerificationPolicy> values) {
        for (var value : values) if (policies.put(value.providerType(), value) != null)
            throw new IllegalStateException("Duplicate payment verification policy");
    }
    public ProviderPaymentVerificationPolicy resolve(PaymentProviderType type) {
        var value = policies.get(type);
        if (value == null) throw new UnsupportedPaymentProviderException(type);
        return value;
    }
}
