package com.household.manager.repository;

import com.household.manager.model.entity.DirigeraSensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DirigeraSensorRepository extends JpaRepository<DirigeraSensor, Long> {

    Optional<DirigeraSensor> findByRelationId(String relationId);

    List<DirigeraSensor> findByKnownToHubTrue();
}
