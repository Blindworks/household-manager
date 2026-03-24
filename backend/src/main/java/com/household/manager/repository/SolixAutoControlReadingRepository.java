package com.household.manager.repository;

import com.household.manager.model.entity.SolixAutoControlReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SolixAutoControlReadingRepository extends JpaRepository<SolixAutoControlReading, Long> {

    List<SolixAutoControlReading> findByTimestampBetweenOrderByTimestampAsc(
            LocalDateTime from, LocalDateTime to);
}
