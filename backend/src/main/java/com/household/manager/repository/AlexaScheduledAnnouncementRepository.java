package com.household.manager.repository;

import com.household.manager.model.entity.AlexaScheduledAnnouncement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlexaScheduledAnnouncementRepository extends JpaRepository<AlexaScheduledAnnouncement, Long> {

    List<AlexaScheduledAnnouncement> findByEnabledTrue();
}
