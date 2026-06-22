package com.household.manager.zigbee.repository;

import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ZigbeeDeviceRepository extends JpaRepository<ZigbeeDevice, Long> {

    Optional<ZigbeeDevice> findByFriendlyName(String friendlyName);
}
