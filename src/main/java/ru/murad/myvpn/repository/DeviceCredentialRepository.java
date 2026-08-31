package ru.murad.myvpn.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.murad.myvpn.model.DeviceCredential;
import java.util.Optional; import java.util.UUID;
public interface DeviceCredentialRepository extends JpaRepository<DeviceCredential, UUID> { Optional<DeviceCredential> findByIdentityId(UUID identityId); }
