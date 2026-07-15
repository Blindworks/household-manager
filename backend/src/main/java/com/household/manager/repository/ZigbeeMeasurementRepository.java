package com.household.manager.repository;

import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ZigbeeMeasurementRepository extends JpaRepository<ZigbeeMeasurement, Long> {

    List<ZigbeeMeasurement> findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            Long deviceId, MeasurementType measurementType, LocalDateTime from, LocalDateTime to);

    List<ZigbeeMeasurement> findByDeviceIdOrderByMeasuredAtAsc(Long deviceId);

    /** Geräte, die im Zeitfenster mindestens einen Messwert des Typs geliefert haben. */
    @Query("select distinct m.device from ZigbeeMeasurement m "
            + "where m.measurementType = :type and m.measuredAt between :from and :to")
    List<ZigbeeDevice> findDistinctDevicesByMeasurementTypeInRange(
            @Param("type") MeasurementType type,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** Alle Geräte, die jemals einen Messwert des Typs geliefert haben. */
    @Query("select distinct m.device from ZigbeeMeasurement m where m.measurementType = :type")
    List<ZigbeeDevice> findDistinctDevicesByMeasurementType(@Param("type") MeasurementType type);

    /** Jüngster Messwert eines Geräts für einen Messtyp. */
    Optional<ZigbeeMeasurement> findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
            Long deviceId, MeasurementType measurementType);
}
