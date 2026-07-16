package com.household.manager.repository;

import com.household.manager.model.entity.EntityUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EntityUsageRepository extends JpaRepository<EntityUsage, Long> {

    Optional<EntityUsage> findByEntityId(String entityId);

    List<EntityUsage> findByEntityIdIn(Collection<String> entityIds);
}
