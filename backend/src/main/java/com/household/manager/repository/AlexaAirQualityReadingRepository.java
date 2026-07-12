package com.household.manager.repository;

import com.household.manager.model.entity.AlexaAirQualityReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AlexaAirQualityReadingRepository extends JpaRepository<AlexaAirQualityReading, Long> {

    List<AlexaAirQualityReading> findAllByOrderByReadingTimeAsc();

    Optional<AlexaAirQualityReading> findTopByApplianceIdOrderByReadingTimeDesc(String applianceId);

    @Query("select distinct r.applianceId from AlexaAirQualityReading r")
    List<String> findDistinctApplianceIds();
}
