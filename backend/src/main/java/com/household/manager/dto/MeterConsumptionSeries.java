package com.household.manager.dto;

import com.household.manager.model.entity.MeterType;

import java.util.List;

/**
 * Die Verbrauchsreihe genau eines Zaehlertyps - eine Kachel der Tablet-Ansicht.
 *
 * @param meterType Zaehlertyp
 * @param unit      Einheit der Werte ("kWh" bei Strom, "m³" bei Gas und Wasser)
 * @param points    Balken, aeltester zuerst
 */
public record MeterConsumptionSeries(
        MeterType meterType,
        String unit,
        List<ConsumptionPoint> points
) {
}
