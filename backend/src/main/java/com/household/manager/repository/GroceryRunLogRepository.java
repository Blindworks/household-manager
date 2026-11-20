package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryRunLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface GroceryRunLogRepository extends JpaRepository<GroceryRunLog, Long> {

    List<GroceryRunLog> findAllByOrderByStartedAtDescIdDesc(Pageable page);

    /** Retention: Läufe vor {@code cutoff}. Eigene Transaktion. */
    @Transactional
    @Modifying
    @Query("delete from GroceryRunLog r where r.startedAt < :cutoff")
    int deleteStartedBefore(@Param("cutoff") LocalDateTime cutoff);
}
