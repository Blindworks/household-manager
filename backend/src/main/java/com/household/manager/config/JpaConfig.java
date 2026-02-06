package com.household.manager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA configuration enabling auditing and transaction management.
 * This configuration enables Spring Data JPA features and ensures proper transaction handling.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.household.manager.repository")
@EnableJpaAuditing
@EnableTransactionManagement
public class JpaConfig {
    // Configuration is handled through annotations and application.yml
}
