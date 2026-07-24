package ru.murad.myvpn.client.threexui;

public record ThreeXUiApiResponse<T>(
        boolean success,
        String msg,
        T obj
) {
}
