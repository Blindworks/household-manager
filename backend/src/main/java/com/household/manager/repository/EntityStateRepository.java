package com.household.manager.repository;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EntityStateRepository extends JpaRepository<EntityState, Long> {

    Optional<EntityState> findByEntityId(String entityId);

    List<EntityState> findAllByOrderByEntityIdAsc();

    List<EntityState> findByDomainOrderByEntityIdAsc(EntityDomain domain);

    List<EntityState> findBySourceOrderByEntityIdAsc(EntitySource source);

    List<EntityState> findByDomainAndSourceOrderByEntityIdAsc(EntityDomain domain, EntitySource source);

    List<EntityState> findByDomainInOrderByEntityIdAsc(Collection<EntityDomain> domains);

    List<EntityState> findByEntityIdIn(Collection<String> entityIds);

    void deleteByEntityId(String entityId);
}
