package com.household.manager.repository;

import com.household.manager.model.entity.HouseDevicePosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HouseDevicePositionRepository extends JpaRepository<HouseDevicePosition, String> {

    List<HouseDevicePosition> findAllByOrderByDeviceIdAsc();
}
