package com.household.manager.flowengine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlowMessageTest {

    @Test
    void withCreatesNewMessageWithoutMutatingOriginal() {
        FlowMessage original = FlowMessage.of(Map.of("entityId", "sensor.x"));
        FlowMessage extended = original.with("note", "hi");

        assertNull(original.get("note"));
        assertEquals("hi", extended.get("note"));
        assertEquals("sensor.x", extended.get("entityId"));
    }

    @Test
    void mergedOverwritesExistingKeys() {
        FlowMessage msg = FlowMessage.of(Map.of("a", "1", "b", "2"));
        FlowMessage merged = msg.merged(Map.of("b", "3", "c", "4"));

        assertEquals("1", merged.get("a"));
        assertEquals("3", merged.get("b"));
        assertEquals("4", merged.get("c"));
    }

    @Test
    void valuesAreImmutable() {
        FlowMessage msg = FlowMessage.of(Map.of("a", "1"));
        assertThrows(UnsupportedOperationException.class, () -> msg.values().put("x", "y"));
    }
}
