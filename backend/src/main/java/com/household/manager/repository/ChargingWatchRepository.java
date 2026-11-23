package com.household.manager.repository;

import com.household.manager.model.entity.ChargingWatchEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChargingWatchRepository extends JpaRepository<ChargingWatchEntry, ChargingWatchEntry.Key> {

    List<ChargingWatchEntry> findAllByOrderByCreatedAtAsc();

    /** Entfernt den Auftrag; 1 nur fuer den Aufrufer, der ihn wirklich entfernt hat („genau ein Push“). */
    @Transactional
    @Modifying
    @Query("DELETE FROM ChargingWatchEntry w WHERE w.stationId = :stationId AND w.userId = :userId")
    int claim(@Param("stationId") String stationId, @Param("userId") Long userId);
}
