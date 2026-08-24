package com.household.manager.repository;

import com.household.manager.model.entity.NetworkSpeedtestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NetworkSpeedtestResultRepository extends JpaRepository<NetworkSpeedtestResult, Long> {

    List<NetworkSpeedtestResult> findByTestedAtAfterOrderByTestedAtAsc(LocalDateTime after);

    Optional<NetworkSpeedtestResult> findTopBySuccessTrueOrderByTestedAtDesc();

    long deleteByTestedAtBefore(LocalDateTime cutoff);
}
