package com.household.manager.repository;

import com.household.manager.model.entity.ShellyReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ShellyReadingRepository extends JpaRepository<ShellyReading, Long> {

    List<ShellyReading> findByDeviceNameOrderByTimestampDesc(String deviceName);

    List<ShellyReading> findByDeviceNameAndTimestampBetweenOrderByTimestampAsc(
            String deviceName, LocalDateTime from, LocalDateTime to);

    List<ShellyReading> findByTimestampBefore(LocalDateTime cutoff);

    void deleteAllByIdIn(List<Long> ids);
}
