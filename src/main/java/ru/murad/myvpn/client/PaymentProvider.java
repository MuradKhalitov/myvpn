package ru.murad.myvpn.client;

import ru.murad.myvpn.dto.CreatePaymentCommand;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.ProviderPayment;

public interface PaymentProvider {

    CreatedPayment createPayment(CreatePaymentCommand command);

    ProviderPayment getPayment(String providerPaymentId);
}
