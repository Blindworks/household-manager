package com.household.manager.service;

import com.household.manager.model.entity.EnergyReading;

import java.util.function.Function;

public enum EnergyMetricType {
    BKW_ALT(EnergyReading::getBkwAltW),
    BKW_NEU(EnergyReading::getBkwNeuW),
    PV_TOTAL(EnergyReading::getPvTotalW),
    HAUSVERBRAUCH(EnergyReading::getHausverbrauchW),
    GRID(EnergyReading::getGridW),
    ANKER_PV(EnergyReading::getAnkerPvW),
    AKKU(EnergyReading::getAkkuPercent);

    private final Function<EnergyReading, Double> extractor;

    EnergyMetricType(Function<EnergyReading, Double> extractor) {
        this.extractor = extractor;
    }

    public Double extract(EnergyReading reading) {
        return extractor.apply(reading);
    }
}
