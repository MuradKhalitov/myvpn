package ru.murad.myvpn.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import ru.murad.myvpn.application.subscription.CurrentSubscriptionView;
import ru.murad.myvpn.application.vpn.VpnAccessView;
import ru.murad.myvpn.dto.SubscriptionDto;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.VpnAccess;

@Mapper(componentModel = "spring", uses = VpnTariffMapper.class)
public interface SubscriptionMapper {

    @Mapping(target = "id", source = "subscription.id")
    @Mapping(target = "status", source = "subscription.status")
    @Mapping(target = "telegramId", source = "subscription.user.telegramId")
    @Mapping(target = "tariff", source = "subscription.tariff")
    @Mapping(target = "providerName", source = "access.providerName")
    @Mapping(target = "configurationData", source = "access.configurationData")
    SubscriptionDto toDto(Subscription subscription, VpnAccess access);

    CurrentSubscriptionView toCurrentSubscriptionView(Subscription subscription);

    @Mapping(target = "subscriptionId", source = "subscription.id")
    @Mapping(target = "configuration", source = "configurationData")
    @Mapping(target = "expiresAt", source = "subscription.expiresAt")
    VpnAccessView toVpnAccessView(VpnAccess access);
}
