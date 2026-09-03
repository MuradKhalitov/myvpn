package ru.murad.myvpn.config;

import java.net.URI;

public final class YooKassaReturnUrlValidator {

    private YooKassaReturnUrlValidator() {
    }

    public static URI validate(URI returnUrl) {
        if (returnUrl == null
                || !"https".equalsIgnoreCase(returnUrl.getScheme())
                || returnUrl.getHost() == null
                || returnUrl.getHost().isBlank()
                || returnUrl.getUserInfo() != null
                || returnUrl.getFragment() != null) {
            throw new IllegalArgumentException("YooKassa return URL must be an absolute HTTPS URL without credentials or fragment");
        }
        return returnUrl;
    }
}
