package ru.murad.myvpn.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "security.device") public record DeviceSecurityProperties(String secretPepper) { @Override public String toString(){return "DeviceSecurityProperties[secretPepper=<redacted>]";} }
