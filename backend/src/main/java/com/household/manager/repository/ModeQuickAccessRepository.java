package com.household.manager.repository;

import com.household.manager.model.entity.ModeQuickAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModeQuickAccessRepository extends JpaRepository<ModeQuickAccess, Long> {

    List<ModeQuickAccess> findAllByOrderByIdAsc();

    List<ModeQuickAccess> findByActiveTrue();

    Optional<ModeQuickAccess> findByEntityId(String entityId);
}
