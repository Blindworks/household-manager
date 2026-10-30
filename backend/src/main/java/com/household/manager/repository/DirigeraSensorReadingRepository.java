package com.household.manager.repository;

import com.household.manager.model.entity.DirigeraSensorReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DirigeraSensorReadingRepository extends JpaRepository<DirigeraSensorReading, Long> {

    Optional<DirigeraSensorReading> findTopBySensorIdOrderByMeasuredAtDesc(Long sensorId);

    List<DirigeraSensorReading> findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            Long sensorId, LocalDateTime from, LocalDateTime to);
}
