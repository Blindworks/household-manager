package com.household.manager.repository;

import com.household.manager.model.entity.ChargingFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChargingFavoriteRepository extends JpaRepository<ChargingFavorite, Long> {

    List<ChargingFavorite> findAllByOrderByCreatedAtAscIdAsc();

    Optional<ChargingFavorite> findByStationId(String stationId);

    boolean existsByStationId(String stationId);
}
