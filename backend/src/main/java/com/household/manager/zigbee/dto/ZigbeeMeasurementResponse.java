package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.zigbee.model.MeasurementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeMeasurementResponse {
    private MeasurementType measurementType;
    private BigDecimal value;
    private String unit;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime measuredAt;
}
