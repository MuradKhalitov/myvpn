package ru.murad.myvpn.application.auth;
public final class DeviceSecretHashService { private final SecretHmac hmac; public DeviceSecretHashService(String pepper) { hmac = new SecretHmac(pepper); } public String hash(String secret) { return hmac.hash(secret); } public boolean matches(String secret, String hash) { return hmac.matches(secret, hash); } }
