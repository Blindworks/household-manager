package com.household.manager.repository;

import com.household.manager.model.entity.SystemLogEntry;
import com.household.manager.systemlog.SystemLogKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface SystemLogEntryRepository extends JpaRepository<SystemLogEntry, Long> {

    Optional<SystemLogEntry> findTopByKindAndSourceAndMessageAndOccurredAtAfterOrderByOccurredAtDesc(
            SystemLogKind kind, String source, String message, LocalDateTime after);

    /** Retention als Bulk-DML mit eigener Transaktion (abgeleitete Deletes haetten keine). */
    @Transactional
    @Modifying
    @Query("delete from SystemLogEntry e where e.occurredAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
