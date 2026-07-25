package ru.murad.myvpn.exception;

public class OpenPaymentOrderDifferentTariffException
        extends IllegalArgumentException {

    public OpenPaymentOrderDifferentTariffException() {
        super("У вас уже есть незавершённый платёж по другому тарифу");
    }
}
