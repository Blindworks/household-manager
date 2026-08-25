package com.household.manager.repository;

import com.household.manager.model.entity.PresenceDevice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PresenceDeviceRepository extends JpaRepository<PresenceDevice, Long> {

    java.util.List<PresenceDevice> findAllByOrderByIdAsc();
}
