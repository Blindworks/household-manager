package com.household.manager.repository;

import com.household.manager.model.entity.ChargingOccupancyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface ChargingOccupancyHistoryRepository extends JpaRepository<ChargingOccupancyRecord, Long> {

    List<ChargingOccupancyRecord> findByStationIdAndStartedAtGreaterThanEqual(String stationId, Instant from);

    /** Bulk-Delete mit eigener Transaktion (abgeleitete deleteBy-Methoden braechten keine mit). */
    @Transactional
    @Modifying
    @Query("delete from ChargingOccupancyRecord r where r.endedAt < :cutoff")
    int deleteEndedBefore(Instant cutoff);
}
