package com.household.manager.repository;

import com.household.manager.communicator.CommunicatorLogDtos.Row;
import com.household.manager.model.entity.CommunicatorRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Listen laufen ueber {@link Row}: der Verlauf ({@code details}) ist gross und wird nur einzeln geladen. */
public interface CommunicatorRequestRepository extends JpaRepository<CommunicatorRequest, Long> {

    String ROW = "select new com.household.manager.communicator.CommunicatorLogDtos$Row(r.id, r.askedAt, r.actor,"
            + " r.voice, r.status, r.question, r.error, r.model, r.audioBytes, r.audioType, r.audioMs, r.speechMs,"
            + " r.totalMs, r.aiCalls, r.toolCalls, r.toolFailures, r.inputTokens, r.outputTokens,"
            + " r.cacheReadTokens, r.cacheWriteTokens, r.endpoints) from CommunicatorRequest r";
    /** Gegenstueck zu {@link Row#problem()}. */
    String FILTER = " where r.askedAt >= :from"
            + " and (:problemsOnly = false"
            + " or r.status <> :ok"
            + " or r.error is not null or r.toolFailures > 0)"
            + " and (:pattern is null or lower(r.question) like :pattern escape '!'"
            + " or lower(r.error) like :pattern escape '!' or lower(r.endpoints) like :pattern escape '!')";

    @Query(ROW + " where r.askedAt >= :from order by r.askedAt, r.id")
    List<Row> findRowsSince(@Param("from") LocalDateTime from);

    @Query(value = ROW + FILTER + " order by r.askedAt desc, r.id desc",
            countQuery = "select count(r) from CommunicatorRequest r" + FILTER)
    Page<Row> search(@Param("from") LocalDateTime from, @Param("problemsOnly") boolean problemsOnly,
                     @Param("ok") CommunicatorRequest.Status ok,
                     @Param("pattern") String pattern, Pageable page);

    @Transactional
    @Modifying
    @Query("delete from CommunicatorRequest r where r.askedAt < :cutoff")
    int deleteAskedBefore(@Param("cutoff") LocalDateTime cutoff);
}
