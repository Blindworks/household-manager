package com.household.manager.flowengine;

import com.household.manager.flowengine.model.NodeConfig;

import java.util.List;
import java.util.Map;

/**
 * Ein Node-Typ der Flow-Engine. Ein Spring-Bean pro Typ; neue Typen = neues Bean.
 * Handler sind zustandslos — per-Node-Zustand liegt im NodeContext.
 */
public interface NodeHandler {

    /** Typ-Kennung, z. B. "entity-state-trigger". */
    String type();

    /** Anzahl der Ausgangsports (Bedingung: 2, Debug: 0, sonst meist 1). */
    int outputPorts();

    /** Konfig-Prüfung beim Deploy; Rückgabe = Fehlermeldungen (leer = ok). */
    List<String> validate(NodeConfig config);

    /** Verarbeitet eine eingehende Message. */
    NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx);

    /** Beschreibung der Config-Felder (Schlüssel → Kurzbeschreibung) für den node-types-Katalog. */
    default Map<String, String> configSchema() {
        return Map.of();
    }
}
