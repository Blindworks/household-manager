package com.household.manager.tesla.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * Antwort von {@code GET /v1/tesla/vehicle}.
 *
 * @param configured {@code false} = kein Tessie-Token hinterlegt, die Kachel bleibt weg
 * @param vehicle    letzter erfolgreicher Stand, fehlt bis zum ersten Abruf
 * @param polledAt   Zeitpunkt des letzten erfolgreichen Abrufs
 * @param error      Grund des letzten gescheiterten Abrufs (der alte Stand bleibt dann stehen)
 * @param imageVersion Schlüssel des Fahrzeugbilds ({@code GET /v1/tesla/vehicle/image}), fehlt ohne Bild
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeslaStatusResponse(boolean configured, TeslaVehicleView vehicle, LocalDateTime polledAt,
                                  String error, String imageVersion) {

    public TeslaStatusResponse(boolean configured, TeslaVehicleView vehicle, LocalDateTime polledAt, String error) {
        this(configured, vehicle, polledAt, error, null);
    }

    public TeslaStatusResponse withImageVersion(String version) {
        return new TeslaStatusResponse(configured, vehicle, polledAt, error, version);
    }
}
