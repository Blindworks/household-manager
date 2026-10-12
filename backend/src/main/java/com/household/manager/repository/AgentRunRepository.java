package com.household.manager.repository;

import com.household.manager.model.entity.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    List<AgentRun> findTop50ByOrderByStartedAtDesc();

    List<AgentRun> findTop3ByOrderByStartedAtDesc();

    /** Eine Zeile {inputTokens, outputTokens}; als Object[]-Liste, weil die Summe zwei Spalten liefert. */
    @Query("select coalesce(sum(r.inputTokens),0), coalesce(sum(r.outputTokens),0) from AgentRun r where r.startedAt >= :since")
    List<Object[]> sumTokensSince(@Param("since") LocalDateTime since);

    /** Retention als Bulk-DML mit eigener Transaktion (siehe NetworkConnectivitySampleRepository). */
    @Transactional
    @Modifying
    @Query("delete from AgentRun r where r.startedAt < :cutoff")
    int deleteByStartedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}