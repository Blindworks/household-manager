package com.household.manager.repository;

import com.household.manager.model.entity.EnergyReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EnergyReadingRepository extends JpaRepository<EnergyReading, Long> {

    List<EnergyReading> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime from, LocalDateTime to);
}
