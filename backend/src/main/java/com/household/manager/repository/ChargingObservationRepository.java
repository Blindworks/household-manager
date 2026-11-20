package com.household.manager.repository;

import com.household.manager.model.entity.ChargingObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChargingObservationRepository extends JpaRepository<ChargingObservation, Long> {

    Optional<ChargingObservation> findByStationIdAndHourStart(String stationId, Instant hourStart);

    List<ChargingObservation> findByStationIdAndHourStartGreaterThanEqual(String stationId, Instant from);

    @Query("select coalesce(sum(o.observedSeconds), 0) from ChargingObservation o where o.stationId = :stationId")
    long sumObservedSeconds(String stationId);

    boolean existsByStationId(String stationId);

    /** Haengt die Beobachtungszeit einer Station an eine neue Id (Stationsumzug, siehe ChargingStationMigrationService). */
    @Transactional
    @Modifying
    @Query("update ChargingObservation o set o.stationId = :newStationId where o.stationId = :oldStationId")
    int reassignStation(String oldStationId, String newStationId);

    /** Bulk-Delete mit eigener Transaktion (abgeleitete deleteBy-Methoden braechten keine mit). */
    @Transactional
    @Modifying
    @Query("delete from ChargingObservation o where o.hourStart < :cutoff")
    int deleteHoursBefore(Instant cutoff);
}
