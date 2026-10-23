package com.household.manager.repository;

import com.household.manager.model.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    List<AuditLog> findByOrderByTimestampDesc(Pageable pageable);
    List<AuditLog> findByActorOrderByTimestampDesc(String actor, Pageable pageable);

    /** Alle je vorgekommenen Aktionen — Quelle der Auswahlliste im Aktionsfilter. */
    @Query("select distinct a.action from AuditLog a order by a.action")
    List<String> findDistinctActions();
}
