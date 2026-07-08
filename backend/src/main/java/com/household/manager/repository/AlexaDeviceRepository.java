package com.household.manager.repository;

import com.household.manager.model.entity.AlexaDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlexaDeviceRepository extends JpaRepository<AlexaDevice, Long> {

    Optional<AlexaDevice> findBySerialNumber(String serialNumber);
}
