package com.household.manager.repository;

import com.household.manager.model.entity.EntityPowerHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EntityPowerHistoryRepository extends JpaRepository<EntityPowerHistory, Long> {

    List<EntityPowerHistory> findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            String entityId, LocalDateTime from, LocalDateTime to);

    /** Fenster fuer die Verdichtung: nur der noch unverdichtete Bereich. */
    List<EntityPowerHistory> findByMeasuredAtBetween(LocalDateTime from, LocalDateTime to);

    void deleteAllByIdIn(List<Long> ids);

    void deleteByMeasuredAtBefore(LocalDateTime cutoff);
}
