package com.household.manager.repository;

import com.household.manager.model.entity.ChargingPointOccupancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ChargingPointOccupancyRepository extends JpaRepository<ChargingPointOccupancy, String> {

    List<ChargingPointOccupancy> findByStationId(String stationId);

    /**
     * Bulk-Delete mit eigener Transaktion (Muster WasteCollectionEventRepository): eine
     * abgeleitete deleteBy-Methode brachte KEINE Transaktion mit und wuerfe gefangen
     * TransactionRequiredException - das Aufraeumen waere still wirkungslos.
     */
    @Transactional
    @Modifying
    @Query("delete from ChargingPointOccupancy o where o.stationId = :stationId")
    int deleteByStationId(String stationId);
}
