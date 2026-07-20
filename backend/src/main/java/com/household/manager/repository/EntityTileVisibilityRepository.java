package com.household.manager.repository;

import com.household.manager.model.entity.EntityTileVisibility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EntityTileVisibilityRepository extends JpaRepository<EntityTileVisibility, Long> {

    Optional<EntityTileVisibility> findByEntityIdAndTileKey(String entityId, String tileKey);

    List<EntityTileVisibility> findByTileKey(String tileKey);

    List<EntityTileVisibility> findByEntityIdIn(Collection<String> entityIds);

    void deleteByEntityIdAndTileKey(String entityId, String tileKey);
}
