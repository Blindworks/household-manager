package com.household.manager.repository;

import com.household.manager.model.entity.NetworkConnectivitySample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NetworkConnectivitySampleRepository extends JpaRepository<NetworkConnectivitySample, Long> {

    List<NetworkConnectivitySample> findBySampledAtAfterOrderBySampledAtAsc(LocalDateTime after);

    Optional<NetworkConnectivitySample> findTopByOrderBySampledAtDesc();

    long deleteBySampledAtBefore(LocalDateTime cutoff);
}
