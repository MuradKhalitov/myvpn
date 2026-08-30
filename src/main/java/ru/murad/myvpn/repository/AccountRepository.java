package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.murad.myvpn.model.Account;

import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
