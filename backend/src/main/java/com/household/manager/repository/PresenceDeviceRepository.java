package com.household.manager.repository;

import com.household.manager.model.entity.PresenceDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PresenceDeviceRepository extends JpaRepository<PresenceDevice, Long> {

    List<PresenceDevice> findAllByOrderByIdAsc();
}
