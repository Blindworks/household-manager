package com.household.manager.flowengine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugBufferTest {

    @Test
    void keepsAtMost100EntriesPerNodeNewestLast() {
        DebugBuffer buffer = new DebugBuffer();
        for (int i = 0; i < 150; i++) {
            buffer.add(1L, "n1", "label", FlowMessage.of(Map.of("i", i)));
        }

        var entries = buffer.entries(1L, "n1");
        assertEquals(100, entries.size());
        assertEquals(50, entries.get(0).message().get("i"));
        assertEquals(149, entries.get(99).message().get("i"));
    }

    @Test
    void unknownNodeReturnsEmptyListAndClearRemovesFlow() {
        DebugBuffer buffer = new DebugBuffer();
        assertTrue(buffer.entries(9L, "nope").isEmpty());

        buffer.add(2L, "n1", null, FlowMessage.of(Map.of()));
        buffer.clearFlow(2L);
        assertTrue(buffer.entries(2L, "n1").isEmpty());
    }
}
