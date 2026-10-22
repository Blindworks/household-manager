package com.household.manager.repository;

import com.household.manager.model.entity.SystemJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemJobStatusRepository extends JpaRepository<SystemJobStatus, String> {
}
