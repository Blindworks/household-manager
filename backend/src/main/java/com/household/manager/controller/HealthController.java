package com.household.manager.controller;

import com.household.manager.dto.HealthResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * REST controller providing health check and application status endpoints.
 * This controller is separate from Spring Actuator to provide custom health information.
 */
@Slf4j
@RestController
@RequestMapping("/v1/health")
public class HealthController {

    private final Optional<BuildProperties> buildProperties;

    public HealthController(Optional<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    /**
     * Simple health check endpoint.
     *
     * @return Health status response with application information
     */
    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        log.debug("Health check endpoint called");

        HealthResponse response = HealthResponse.builder()
                .status("UP")
                .message("Household Manager Backend is running")
                .timestamp(LocalDateTime.now())
                .version(buildProperties.map(BuildProperties::getVersion).orElse("development"))
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Detailed status endpoint providing more information about the application.
     *
     * @return Detailed application status
     */
    @GetMapping("/status")
    public ResponseEntity<HealthResponse> status() {
        log.debug("Status endpoint called");

        HealthResponse response = HealthResponse.builder()
                .status("UP")
                .message("Application is healthy and ready to serve requests")
                .timestamp(LocalDateTime.now())
                .version(buildProperties.map(BuildProperties::getVersion).orElse("development"))
                .applicationName("Household Manager Backend")
                .build();

        return ResponseEntity.ok(response);
    }
}
