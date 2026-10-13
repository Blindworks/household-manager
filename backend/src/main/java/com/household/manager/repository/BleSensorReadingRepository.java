package com.household.manager.repository;

import com.household.manager.model.entity.BleSensorReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BleSensorReadingRepository extends JpaRepository<BleSensorReading, Long> {

    Optional<BleSensorReading> findTopBySensorIdOrderByMeasuredAtDesc(Long sensorId);

    List<BleSensorReading> findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            Long sensorId, LocalDateTime from, LocalDateTime to);
}
