package com.household.manager.flowengine;

import com.household.manager.flowengine.model.NodeConfig;

import java.util.List;

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

    /** Typisierte Feld-Deskriptoren für den node-types-Katalog (schema-getriebenes Panel). */
    default List<NodeFieldDescriptor> fields() {
        return List.of();
    }

    /** Labels der Ausgangsports (Länge == outputPorts); Default "Ausgang" je Port. */
    default List<String> portLabels() {
        return java.util.stream.IntStream.range(0, outputPorts())
                .mapToObj(i -> "Ausgang")
                .toList();
    }
}
