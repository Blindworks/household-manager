package com.household.manager.repository;

import com.household.manager.model.entity.ApplicationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for accessing application settings stored in the database.
 */
@Repository
public interface ApplicationSettingRepository extends JpaRepository<ApplicationSetting, Long> {

    List<ApplicationSetting> findByCategory(String category);

    Optional<ApplicationSetting> findByCategoryAndSettingKey(String category, String settingKey);
}
