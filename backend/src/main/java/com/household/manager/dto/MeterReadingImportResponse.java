package com.household.manager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for CSV import results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeterReadingImportResponse {

    /**
     * Number of created meter readings.
     */
    private int createdCount;
}
