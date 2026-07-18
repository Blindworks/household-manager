package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityEventFired;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Trigger auf Ereignisse von EVENT-Entitäten (z. B. Zigbee-Taster). Feuert bei
 * JEDEM Ereignis — auch bei wiederholt gleicher Aktion (kein Flanken-Verhalten,
 * kein forSeconds: Ereignisse haben keine Verweildauer). Optionaler
 * Aktions-Filter mit exaktem String-Vergleich; leer = jede Aktion.
 */
@Component
public class EntityEventTriggerHandler implements TriggerNodeHandler {

    @Override
    public String type() {
        return "entity-event-trigger";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public Optional<String> watchedEntityId(NodeConfig config) {
        return config.string("entityId");
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("entityId", "Entity", NodeFieldType.ENTITY_REF, true),
                NodeFieldDescriptor.field("action", "Aktion (leer = jede)", NodeFieldType.STRING, false));
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("entityId").isEmpty()) {
            errors.add("entityId fehlt");
        }
        return errors;
    }

    @Override
    public void onEntityEventFired(EntityEventFired event, NodeConfig config, NodeContext ctx) {
        String filter = config.string("action").orElse(null);
        if (filter != null && !filter.isBlank() && !filter.equals(event.action())) {
            return;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("entityId", event.entityId());
        values.put("action", event.action());
        values.put("attributes", event.attributes());
        values.put("timestamp", event.timestamp());
        values.put("triggerNodeId", ctx.nodeId());
        ctx.emit(0, FlowMessage.of(values));
    }
}
