package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
public class EnergyLiveDto {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    private BigDecimal bkwAltW;
    private BigDecimal bkwNeuW;
    private BigDecimal pvTotalW;

    private BigDecimal gridW;
    private BigDecimal hausverbrauchW;

    private boolean gridImporting;
    private boolean shellyReachable;
    private boolean tasmotaReachable;
}
