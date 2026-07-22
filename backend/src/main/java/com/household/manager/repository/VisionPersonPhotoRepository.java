package com.household.manager.repository;

import com.household.manager.model.entity.VisionPersonPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisionPersonPhotoRepository extends JpaRepository<VisionPersonPhoto, Long> {
    List<VisionPersonPhoto> findByPersonId(Long personId);
    void deleteByPersonId(Long personId);
}
