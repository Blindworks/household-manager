package com.household.manager.tesla;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Befehle, die die Dashboard-Kachel anbietet. {@link #path} ist der Tessie-Pfad
 * unter {@code /{vin}/command/}; weitere Befehle gibt es bewusst nicht.
 */
@Getter
@RequiredArgsConstructor
public enum TeslaCommand {
    LOCK("lock", "verriegeln"),
    UNLOCK("unlock", "entriegeln"),
    OPEN_CHARGE_PORT("open_charge_port", "Ladeklappe öffnen"),
    CLOSE_CHARGE_PORT("close_charge_port", "Ladeklappe schließen"),
    START_CLIMATE("start_climate", "Klima an"),
    STOP_CLIMATE("stop_climate", "Klima aus"),
    START_DEFROST("start_max_defrost", "Enteisen an"),
    STOP_DEFROST("stop_max_defrost", "Enteisen aus");

    private final String path;
    private final String label;
}
