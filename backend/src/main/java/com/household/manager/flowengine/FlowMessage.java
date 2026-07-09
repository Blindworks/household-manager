package com.household.manager.flowengine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Die Nachricht, die durch die Wires wandert (Node-REDs "msg").
 * Unveränderlich — Verzweigung auf mehrere Wires braucht deshalb keine Kopie:
 * kein Zweig kann den Zustand eines anderen sehen oder ändern.
 */
public record FlowMessage(Map<String, Object> values) {

    public FlowMessage {
        // Map.copyOf verbietet null-Values; Trigger-Messages enthalten aber
        // legitim null-wertige Felder (z. B. oldState, wenn die Entität vorher
        // keinen Zustand hatte). Deshalb null-tolerante immutable Kopie.
        values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public static FlowMessage of(Map<String, Object> values) {
        return new FlowMessage(values);
    }

    public Object get(String key) {
        return values.get(key);
    }

    public FlowMessage with(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(values);
        copy.put(key, value);
        return new FlowMessage(copy);
    }

    public FlowMessage merged(Map<String, Object> other) {
        Map<String, Object> copy = new HashMap<>(values);
        copy.putAll(other);
        return new FlowMessage(copy);
    }
}
