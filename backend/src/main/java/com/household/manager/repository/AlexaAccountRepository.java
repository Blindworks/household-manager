package com.household.manager.repository;

import com.household.manager.model.entity.AlexaAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlexaAccountRepository extends JpaRepository<AlexaAccount, Long> {

    Optional<AlexaAccount> findFirstByOrderByIdAsc();
}
