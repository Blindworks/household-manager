package com.household.manager.flowengine.nodes;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktions-Node: setzt einen manuellen Boolean-Helfer auf "on" oder "off" —
 * die Brücke von einem Flow zu einer Anzeige im Dashboard (z. B. die Karte
 * „Waschmaschine fertig" im Intelligence Hub).
 *
 * <p>Geschrieben wird ausschließlich über {@link ManualEntityService}; dessen
 * Beschränkung auf {@code EntitySource.MANUAL} verhindert, dass ein Flow den
 * Zustand eines echten Geräts oder Sensors fälscht.
 *
 * <p>Anders als {@code light-set} schluckt dieser Node Fehler <b>nicht</b>: hier
 * scheitert kein unerreichbares Funkgerät, sondern ein Schreibzugriff auf die
 * eigene Datenbank — ein Konfigurationsfehler, der im Flow-Debug sichtbar sein soll.
 */
@Component
@RequiredArgsConstructor
public class HelperSetNodeHandler implements NodeHandler {

    private static final String ON = "on";
    private static final String OFF = "off";

    private final ManualEntityService manualEntityService;
    private final AuditService auditService;

    @Override
    public String type() {
        return "helper-set";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("entityId").filter(id -> !id.isBlank()).isEmpty()) {
            errors.add("entityId fehlt");
        }
        String action = config.string("action").orElse(null);
        if (!ON.equals(action) && !OFF.equals(action)) {
            errors.add("action muss 'on' oder 'off' sein");
        }
        // Ob der Helfer existiert, prueft erst die Laufzeit: ein Deploy darf nicht
        // daran scheitern, dass er im Moment des Deploys noch fehlt.
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String entityId = config.string("entityId").orElseThrow();
        String action = config.string("action").orElseThrow();
        manualEntityService.setState(entityId, action);
        auditService.record("helper.set", entityId + " -> " + action);
        return NodeResult.single(message);
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("entityId", "Helfer", NodeFieldType.ENTITY_REF, true),
                NodeFieldDescriptor.enumField("action", "Aktion", true, List.of(ON, OFF)));
    }
}
