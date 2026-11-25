package com.household.manager.meross.dto;

import java.util.List;

/**
 * Ergebnis eines Sammellaufs über alle Meross-Steckdosen: die gelesenen Messwerte
 * und die Steckdosen, die in diesem Lauf nicht gelesen werden konnten.
 *
 * @param readings    Messwerte aller erfolgreich gelesenen Steckdosen
 * @param unreachable Steckdosen ohne Messwert in diesem Lauf (Cloud meldet offline
 *                    oder der MQTT-Abruf scheiterte); Steckdosen ohne Energiemessung
 *                    stehen nie darin
 */
public record MerossPlugPollResult(List<MerossElectricityReading> readings, List<UnreachablePlug> unreachable) {

    public MerossPlugPollResult {
        readings = List.copyOf(readings);
        unreachable = List.copyOf(unreachable);
    }

    /** Warum eine Steckdose in diesem Lauf keinen Messwert hat. */
    public enum Reason {
        /** Die Meross-Cloud meldet das Gerät als offline: belastbar, gilt sofort. */
        OFFLINE,
        /** Der Abruf scheiterte, obwohl die Cloud das Gerät für online hält: kann ein Aussetzer sein. */
        READ_FAILED
    }

    public record UnreachablePlug(String deviceId, String deviceName, Reason reason) {
    }
}
