package com.household.manager.repository;

import com.household.manager.model.entity.AgentNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AgentNoteRepository extends JpaRepository<AgentNote, Long> {

    List<AgentNote> findAllByOrderByCreatedAtAsc();

    /** Bulk-DML mit eigener Transaktion: ein abgeleiteter Delete braeuchte eine bereits aktive. */
    @Transactional
    @Modifying
    @Query("delete from AgentNote n where n.expiresAt is not null and n.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}