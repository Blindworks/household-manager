package com.household.manager.repository;

import com.household.manager.model.entity.TractivePosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Zugriff auf die selbst mitgeschriebene Positionshistorie der Tractive-Tracker. */
public interface TractivePositionRepository extends JpaRepository<TractivePosition, Long> {

    /** Alle Punkte eines Trackers ab einem Zeitpunkt, aelteste zuerst. */
    List<TractivePosition> findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
            String trackerId, Instant from);

    /**
     * Ist dieser Bericht schon gespeichert? Bei ausgeschaltetem Tracker liefert die
     * API denselben Zeitstempel immer wieder — ohne diese Pruefung entstuende ein
     * kuenstlich lueckenloser Positionsstrom, und der Detektor saehe einen einzigen,
     * nie endenden Spaziergang.
     */
    boolean existsByTrackerIdAndPositionTime(String trackerId, Instant positionTime);
}
