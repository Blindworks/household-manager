package com.household.manager.repository;

import com.household.manager.model.entity.VisionPerson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisionPersonRepository extends JpaRepository<VisionPerson, Long> {
    List<VisionPerson> findAllByOrderByNameAsc();
    List<VisionPerson> findByActiveTrueOrderByNameAsc();
}
