package ru.murad.myvpn.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import ru.murad.myvpn.application.subscription.CurrentSubscriptionView;
import ru.murad.myvpn.application.vpn.VpnAccessView;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.VpnAccess;

@Mapper(componentModel = "spring", uses = VpnTariffMapper.class)
public interface SubscriptionMapper {

    CurrentSubscriptionView toCurrentSubscriptionView(Subscription subscription);

    @Mapping(target = "subscriptionId", source = "subscription.id")
    @Mapping(target = "configuration", source = "configurationData")
    @Mapping(target = "expiresAt", source = "subscription.expiresAt")
    VpnAccessView toVpnAccessView(VpnAccess access);
}
