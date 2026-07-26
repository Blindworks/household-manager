package com.household.manager.repository;

import com.household.manager.model.entity.ServiceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceTokenRepository extends JpaRepository<ServiceToken, Long> {
    Optional<ServiceToken> findByTokenHashAndEnabledTrue(String tokenHash);
    boolean existsByName(String name);
}
