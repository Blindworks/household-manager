package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing health check response information.
 * Used by the health endpoint to provide application status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HealthResponse {

    /**
     * Application health status (UP, DOWN, etc.)
     */
    private String status;

    /**
     * Human-readable message describing the health status
     */
    private String message;

    /**
     * Timestamp when the health check was performed
     */
    private LocalDateTime timestamp;

    /**
     * Application version
     */
    private String version;

    /**
     * Application name
     */
    private String applicationName;
}
