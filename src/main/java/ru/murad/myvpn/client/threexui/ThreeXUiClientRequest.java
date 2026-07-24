package ru.murad.myvpn.client.threexui;

public record ThreeXUiClientRequest(
        int id,
        String settings
) {

    @Override
    public String toString() {
        return "ThreeXUiClientRequest[id=redacted, settingsRedacted=true]";
    }
}
