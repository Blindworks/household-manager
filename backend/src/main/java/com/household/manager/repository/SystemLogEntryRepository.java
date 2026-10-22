package com.household.manager.repository;

import com.household.manager.model.entity.SystemLogEntry;
import com.household.manager.systemlog.SystemLogKind;
import com.household.manager.systemlog.SystemLogStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
public interface SystemLogEntryRepository extends JpaRepository<SystemLogEntry, Long> {

    Optional<SystemLogEntry> findTopByKindAndSourceAndMessageAndOccurredAtAfterOrderByOccurredAtDesc(
            SystemLogKind kind, String source, String message, LocalDateTime after);

    /** Retention als Bulk-DML mit eigener Transaktion (abgeleitete Deletes haetten keine). */
    @Transactional
    @Modifying
    @Query("delete from SystemLogEntry e where e.occurredAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Gefilterte Suche, neueste zuerst. {@code null} oder leer heisst „kein Filter“; {@code pattern} ist ein
     * fertiges, kleingeschriebenes LIKE-Muster (Escape-Zeichen Backslash).
     * <p>
     * Die Listenfilter laufen ueber ein Flag und eine Platzhalter-Liste: Hibernate bindet {@code null} nicht
     * sauber als Liste ({@code in (null)}).
     */
    default Page<SystemLogEntry> search(LocalDateTime from, LocalDateTime to, List<SystemLogKind> kinds,
                                        List<SystemLogStatus> statuses, Long flowId, String pattern,
                                        Pageable pageable) {
        boolean byKind = kinds != null && !kinds.isEmpty();
        boolean byStatus = statuses != null && !statuses.isEmpty();
        return searchFiltered(from, to, byKind, byKind ? kinds : List.of(SystemLogKind.LOG),
                byStatus, byStatus ? statuses : List.of(SystemLogStatus.INFO), flowId, pattern, pageable);
    }

    @Query(value = "select e from SystemLogEntry e where e.occurredAt >= :from and e.occurredAt <= :to"
            + " and (:byKind = false or e.kind in :kinds)"
            + " and (:byStatus = false or e.status in :statuses)"
            + " and (:flowId is null or e.flowId = :flowId)"
            + " and (:pattern is null or lower(e.title) like :pattern escape '\\'"
            + "   or lower(e.message) like :pattern escape '\\' or lower(e.source) like :pattern escape '\\')"
            + " order by e.occurredAt desc, e.id desc",
            countQuery = "select count(e) from SystemLogEntry e where e.occurredAt >= :from and e.occurredAt <= :to"
            + " and (:byKind = false or e.kind in :kinds)"
            + " and (:byStatus = false or e.status in :statuses)"
            + " and (:flowId is null or e.flowId = :flowId)"
            + " and (:pattern is null or lower(e.title) like :pattern escape '\\'"
            + "   or lower(e.message) like :pattern escape '\\' or lower(e.source) like :pattern escape '\\')")
    Page<SystemLogEntry> searchFiltered(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                        @Param("byKind") boolean byKind, @Param("kinds") List<SystemLogKind> kinds,
                                        @Param("byStatus") boolean byStatus,
                                        @Param("statuses") List<SystemLogStatus> statuses,
                                        @Param("flowId") Long flowId, @Param("pattern") String pattern,
                                        Pageable pageable);

    List<SystemLogEntry> findByRunIdOrderByOccurredAtAscIdAsc(String runId);

    @Query("select distinct e.flowId from SystemLogEntry e where e.flowId is not null"
            + " and e.occurredAt >= :from and e.occurredAt <= :to")
    List<Long> findDistinctFlowIds(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
