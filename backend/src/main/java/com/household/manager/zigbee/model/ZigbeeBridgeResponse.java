package com.household.manager.zigbee.model;

/**
 * Antwort auf einen eigenen Request, aus {@code zigbee2mqtt/bridge/response/<request>}.
 *
 * @param request     Teil nach {@code bridge/response/}, z. B. {@code device/rename}
 * @param transaction von uns gesetzte Transaktions-ID, von z2m gespiegelt; kann fehlen
 * @param ok          {@code status == "ok"}
 * @param error       z2m's Fehlertext bei {@code status == "error"}, sonst null
 */
public record ZigbeeBridgeResponse(String request, String transaction, boolean ok, String error) {
}
