package ru.murad.myvpn.client.threexui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreeXUiVlessClient(
        String id,
        String security,
        String password,
        String flow,
        String auth,
        String email,
        Integer limitIp,
        Long totalGB,
        Long expiryTime,
        Boolean enable,
        Long tgId,
        String subId,
        String comment,
        Integer reset,
        @JsonProperty("created_at") Long createdAt,
        @JsonProperty("updated_at") Long updatedAt
) {
    public ThreeXUiVlessClient withExpiryTime(long newExpiryTime) {
        return new ThreeXUiVlessClient(
                id, security, password, flow, auth, email, limitIp, totalGB,
                newExpiryTime, enable, tgId, subId, comment, reset, createdAt, updatedAt);
    }

    public static ThreeXUiVlessClient create(
            String id,
            String email,
            long expiryTime
    ) {
        return new ThreeXUiVlessClient(
                id, null, null, "", null, email, 0, 0L, expiryTime,
                true, 0L, "", null, null, null, null);
    }
}
