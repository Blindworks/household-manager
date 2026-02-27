package com.household.manager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO returned when a new meter reading is created.
 * <p>
 * In addition to the saved reading, this response includes any estimated readings
 * that were automatically generated for missed Fridays (when the gap between the
 * previous and new reading spans more than one week).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeterReadingCreateResponse {

    /**
     * The meter reading that was explicitly saved by the user.
     */
    private MeterReadingResponse reading;

    /**
     * Estimated readings that were automatically created for missed Fridays.
     * <p>
     * Empty when no Fridays were skipped since the last reading.
     */
    private List<MeterReadingResponse> autoCreatedReadings;
}
