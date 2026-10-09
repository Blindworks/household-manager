package com.household.manager.repository;

import com.household.manager.model.entity.Thermostat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ThermostatRepository extends JpaRepository<Thermostat, Long> {

    List<Thermostat> findByActiveTrue();

    Optional<Thermostat> findByChildDeviceId(String childDeviceId);

    List<Thermostat> findByHubIp(String hubIp);
}
