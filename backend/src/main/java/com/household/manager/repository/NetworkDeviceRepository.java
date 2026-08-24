package com.household.manager.repository;

import com.household.manager.model.entity.NetworkDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NetworkDeviceRepository extends JpaRepository<NetworkDevice, Long> {

    List<NetworkDevice> findByActiveTrueOrderBySortOrderAscIdAsc();

    List<NetworkDevice> findAllByOrderBySortOrderAscIdAsc();
}
