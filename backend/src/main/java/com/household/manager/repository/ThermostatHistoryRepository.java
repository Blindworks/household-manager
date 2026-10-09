package com.household.manager.repository;

import com.household.manager.model.entity.ThermostatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ThermostatHistoryRepository extends JpaRepository<ThermostatHistory, Long> {

    List<ThermostatHistory> findByThermostatIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            Long thermostatId, LocalDateTime from, LocalDateTime to);

    Optional<ThermostatHistory> findTopByThermostatIdOrderByRecordedAtDesc(Long thermostatId);

    /**
     * Retention als Bulk-DML mit eigener Transaktion (Muster
     * {@code NetworkConnectivitySampleRepository}): abgeleitete Deletes bringen keine mit.
     */
    @Transactional
    @Modifying
    @Query("delete from ThermostatHistory h where h.recordedAt < :cutoff")
    int deleteByRecordedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
