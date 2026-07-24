package ru.murad.myvpn.client.threexui;

public record ThreeXUiApiResponse<T>(
        boolean success,
        String msg,
        T obj
) {

    @Override
    public String toString() {
        return "ThreeXUiApiResponse[success=" + success
                + ", messageRedacted=true, objectRedacted=true]";
    }
}
