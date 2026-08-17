package com.household.manager.repository;

import com.household.manager.model.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserId(Long userId);

    Optional<PushSubscription> findByIdAndUserId(Long id, Long userId);
}
