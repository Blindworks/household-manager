package com.household.manager.repository;

import com.household.manager.model.entity.HouseOpening;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HouseOpeningRepository extends JpaRepository<HouseOpening, Long> {

    List<HouseOpening> findAllByOrderByIdAsc();

    Optional<HouseOpening> findByDeviceId(String deviceId);
}
