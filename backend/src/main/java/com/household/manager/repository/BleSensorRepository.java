package com.household.manager.repository;

import com.household.manager.model.entity.BleSensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BleSensorRepository extends JpaRepository<BleSensor, Long> {

    Optional<BleSensor> findByMac(String mac);

    List<BleSensor> findByActiveTrue();

    /** Nicht uebernommene (active=false) zuerst, dann nach Name und MAC. */
    List<BleSensor> findAllByOrderByActiveAscNameAscMacAsc();
}
