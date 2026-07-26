package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.VpnDeliveryMessage;

public interface VpnConfigurationDeliveryGateway {
    /** Returns a remote message id only after Telegram accepted the message. */
    Long deliver(VpnDeliveryMessage message);
}
