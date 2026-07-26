package com.household.manager.repository;

import com.household.manager.model.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByOrderByTimestampDesc(Pageable pageable);
    List<AuditLog> findByActorOrderByTimestampDesc(String actor, Pageable pageable);
}
