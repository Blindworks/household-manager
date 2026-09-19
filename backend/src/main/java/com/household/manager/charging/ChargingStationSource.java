package com.household.manager.charging;

import java.util.List;

/**
 * Die einzige Stelle, an der das Modul mit einer Ladesaeulen-Quelle spricht. Heute EnBW
 * (inoffiziell); die Quelle ist hinter diesem Interface austauschbar (Muster WebPushClient).
 */
public interface ChargingStationSource {

    /** Standorte im Rechteck; die Mindestleistung darf die Quelle vorfiltern, muss aber nicht. */
    List<ChargingStation> searchArea(BoundingBox box, double minPowerKw);

    /** Ladepunkte einer Station einzeln. */
    ChargingStationDetails stationDetails(String stationId);
}
