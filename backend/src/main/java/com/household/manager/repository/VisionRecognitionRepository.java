package com.household.manager.repository;

import com.household.manager.model.entity.VisionRecognition;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisionRecognitionRepository extends JpaRepository<VisionRecognition, Long> {
    List<VisionRecognition> findAllByOrderByRecognizedAtDesc(Pageable pageable);
}
