package com.household.manager.repository;

import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ZigbeeMeasurementRepository extends JpaRepository<ZigbeeMeasurement, Long> {

    List<ZigbeeMeasurement> findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            Long deviceId, MeasurementType measurementType, LocalDateTime from, LocalDateTime to);

    List<ZigbeeMeasurement> findByDeviceIdOrderByMeasuredAtAsc(Long deviceId);
}
