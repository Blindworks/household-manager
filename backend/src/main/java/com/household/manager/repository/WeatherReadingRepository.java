package com.household.manager.repository;

import com.household.manager.model.entity.WeatherReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Repository für {@link WeatherReading}. */
@Repository
public interface WeatherReadingRepository extends JpaRepository<WeatherReading, Long> {

    List<WeatherReading> findByReadingTimeBetweenOrderByReadingTimeAsc(
            LocalDateTime from, LocalDateTime to);

    List<WeatherReading> findAllByOrderByReadingTimeAsc();
}
