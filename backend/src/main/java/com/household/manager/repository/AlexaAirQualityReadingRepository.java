package com.household.manager.repository;

import com.household.manager.model.entity.AlexaAirQualityReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AlexaAirQualityReadingRepository extends JpaRepository<AlexaAirQualityReading, Long> {

    List<AlexaAirQualityReading> findAllByOrderByReadingTimeAsc();

    Optional<AlexaAirQualityReading> findTopByApplianceIdOrderByReadingTimeDesc(String applianceId);

    @Query("select distinct r.applianceId from AlexaAirQualityReading r")
    List<String> findDistinctApplianceIds();

    /** Alle Geräte-IDs, die seit einem Zeitpunkt mindestens eine Messung geliefert haben. */
    @Query("select distinct r.applianceId from AlexaAirQualityReading r where r.readingTime >= :since")
    List<String> findDistinctApplianceIdsSince(@Param("since") LocalDateTime since);

    List<AlexaAirQualityReading> findByReadingTimeBetweenOrderByReadingTimeAsc(
            LocalDateTime from, LocalDateTime to);

    /** Messungen genau eines Geräts im Zeitfenster, aufsteigend — Grundlage des Sensor-Verlaufs. */
    List<AlexaAirQualityReading> findByApplianceIdAndReadingTimeBetweenOrderByReadingTimeAsc(
            String applianceId, LocalDateTime from, LocalDateTime to);
}
