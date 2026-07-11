package com.household.manager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Ein einzelner Schadstoff des UBA-Luftqualitätsindex. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AirQualityComponent {

    /** Kürzel, z. B. "PM10". */
    private String code;
    /** Anzeigesymbol, z. B. "PM₁₀". */
    private String symbol;
    /** Name, z. B. "Feinstaub". */
    private String name;
    private BigDecimal value;
    /** Einheit, z. B. "µg/m³". */
    private String unit;
    /** Teil-Index dieses Schadstoffs (0–4, -1 = keine Daten). */
    private int index;
}
