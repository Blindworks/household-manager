package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Status response for Tasmota electricity polling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TasmotaElectricityPollingStatusResponse {

    private boolean enabled;

    private long intervalMs;

    private String url;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastPollTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastSuccessTime;

    private String lastError;
}
