package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.zigbee.model.MeasurementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Live-Event eines eingetroffenen Zigbee-Messwerts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeLiveResponse {

    private String friendlyName;
    private MeasurementType measurementType;
    private BigDecimal value;
    private String unit;
    private Integer batteryPercent;
    private Integer linkQuality;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime measuredAt;
}
