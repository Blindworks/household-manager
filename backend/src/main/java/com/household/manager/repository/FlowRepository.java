package com.household.manager.repository;

import com.household.manager.model.entity.Flow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface FlowRepository extends JpaRepository<Flow, Long> {

    List<Flow> findAllByOrderByNameAsc();

    List<Flow> findByEnabledTrueAndDeployedDefinitionNotNull();

    /** Bulk-Update ohne Entity-Lifecycle: {@code updated_at} bleibt unberührt. */
    @Transactional
    @Modifying
    @Query("UPDATE Flow f SET f.lastTriggeredAt = :at WHERE f.id = :id")
    int updateLastTriggeredAt(@Param("id") Long id, @Param("at") LocalDateTime at);
}
