package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.VpnDelivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VpnDeliveryRepository extends JpaRepository<VpnDelivery, UUID> {
    @Query(value = """
        select * from vpn_deliveries where
        ((status in ('PENDING','RETRY_REQUIRED') and (next_attempt_at is null or next_attempt_at <= :now) and (lease_until is null or lease_until <= :now))
          or (status = 'PROCESSING' and lease_until <= :now))
        and attempts < :maxAttempts
        order by next_attempt_at asc nulls first, created_at asc, id asc for update skip locked limit :limit
        """, nativeQuery = true)
    List<VpnDelivery> lockCandidates(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    @Query(value = """
        select * from vpn_deliveries where attempts >= :maxAttempts and
        ((status in ('PENDING','RETRY_REQUIRED')) or (status = 'PROCESSING' and lease_until <= :now))
        order by created_at asc, id asc for update skip locked limit :limit
        """, nativeQuery = true)
    List<VpnDelivery> lockExhaustedCandidates(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);
}
