package com.household.manager.repository;

import com.household.manager.model.entity.WeatherReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Repository für {@link WeatherReading}. */
@Repository
public interface WeatherReadingRepository extends JpaRepository<WeatherReading, Long> {

    List<WeatherReading> findAllByOrderByReadingTimeAsc();

    List<WeatherReading> findByReadingTimeBetweenOrderByReadingTimeAsc(
            LocalDateTime from, LocalDateTime to);

    /** Jüngste Wettermessung, die eine Temperatur gesetzt hat. */
    Optional<WeatherReading> findTopByTemperatureIsNotNullOrderByReadingTimeDesc();
}
