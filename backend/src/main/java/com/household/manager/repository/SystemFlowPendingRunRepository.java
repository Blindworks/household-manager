package com.household.manager.repository;

import com.household.manager.model.entity.SystemFlowPendingRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface SystemFlowPendingRunRepository extends JpaRepository<SystemFlowPendingRun, String> {

    List<SystemFlowPendingRun> findByFlowId(long flowId);

    @Transactional
    @Modifying
    @Query("delete from SystemFlowPendingRun p where p.runId = :runId")
    int deleteByRunId(@Param("runId") String runId);

    @Transactional
    @Modifying
    @Query("delete from SystemFlowPendingRun p where p.flowId = :flowId")
    int deleteByFlowId(@Param("flowId") long flowId);
}
