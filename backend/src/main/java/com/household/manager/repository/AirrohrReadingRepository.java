package com.household.manager.repository;

import com.household.manager.model.entity.AirrohrReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for {@link AirrohrReading} entity operations.
 */
@Repository
public interface AirrohrReadingRepository extends JpaRepository<AirrohrReading, Long> {
}
