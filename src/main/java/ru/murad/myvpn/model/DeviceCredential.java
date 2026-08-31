package ru.murad.myvpn.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "device_credentials") @Getter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DeviceCredential {
    @Id private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "identity_id", nullable = false, unique = true) private AccountIdentity identity;
    @Column(name = "secret_hash", nullable = false, length = 64) private String secretHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Version private long version;
    public boolean usable() { return revokedAt == null; }
    public void used(Instant now) { lastUsedAt = now; updatedAt = now; }
    public void revoke(Instant now) { revokedAt = now; updatedAt = now; }
}
