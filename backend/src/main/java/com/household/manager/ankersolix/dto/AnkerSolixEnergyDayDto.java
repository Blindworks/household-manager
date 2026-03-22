package com.household.manager.ankersolix.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Daily energy summary for an Anker Solix power system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnkerSolixEnergyDayDto {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** Total solar energy produced during the day in kWh. */
    private double pvEnergyKwh;

    /** Total energy charged into the battery during the day in kWh. */
    private double batteryChargeKwh;

    /** Total energy discharged from the battery during the day in kWh. */
    private double batteryDischargeKwh;
}
