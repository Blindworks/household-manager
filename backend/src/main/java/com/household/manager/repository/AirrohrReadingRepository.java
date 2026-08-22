package com.household.manager.repository;

import com.household.manager.model.entity.AirrohrReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository interface for {@link AirrohrReading} entity operations.
 */
@Repository
public interface AirrohrReadingRepository extends JpaRepository<AirrohrReading, Long> {

    /**
     * Messwerte eines Zeitfensters, aufsteigend. Der Serien-Endpunkt fragt bewusst ein
     * Fenster ab statt die komplette Historie: die Tabelle waechst unbegrenzt, und das
     * Wandtablet ruft alle fuenf Minuten neu ab.
     */
    List<AirrohrReading> findByReadingTimeBetweenOrderByReadingTimeAsc(
            LocalDateTime from, LocalDateTime to);
}
