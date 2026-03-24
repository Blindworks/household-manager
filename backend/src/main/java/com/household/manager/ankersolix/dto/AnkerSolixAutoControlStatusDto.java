package com.household.manager.ankersolix.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Status information for the Anker Solix auto-control regulation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnkerSolixAutoControlStatusDto {

    /** Whether auto-control is enabled. */
    private boolean enabled;

    /** Minimum change in watts required to trigger an adjustment. */
    private int thresholdW;

    /** Last output power that was set by auto-control (watts), or null if never adjusted. */
    private Integer lastSetOutputW;

    /** Last measured grid power in watts, or null if no measurement yet. */
    private Double lastGridPowerW;

    /** Timestamp of the last successful output adjustment, or null if never adjusted. */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastAdjustmentTime;

    /** Reason why the last cycle was skipped, or null if the last cycle made an adjustment. */
    private String lastSkipReason;
}
