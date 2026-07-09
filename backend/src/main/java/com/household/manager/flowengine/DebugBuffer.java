package com.household.manager.flowengine;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ring-Puffer für Debug-Nodes: letzte 100 Messages pro (Flow, Node).
 * Grundlage der Debug-Sidebar in Stufe 3b.
 */
@Component
public class DebugBuffer {

    public record DebugEntry(LocalDateTime timestamp, String label, Map<String, Object> message) {
    }

    private static final int MAX_ENTRIES = 100;

    private final Map<String, Deque<DebugEntry>> buffers = new ConcurrentHashMap<>();

    public void add(long flowId, String nodeId, String label, FlowMessage message) {
        Deque<DebugEntry> deque = buffers.computeIfAbsent(key(flowId, nodeId), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new DebugEntry(LocalDateTime.now(), label, message.values()));
            while (deque.size() > MAX_ENTRIES) {
                deque.removeFirst();
            }
        }
    }

    public List<DebugEntry> entries(long flowId, String nodeId) {
        Deque<DebugEntry> deque = buffers.get(key(flowId, nodeId));
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void clearFlow(long flowId) {
        buffers.keySet().removeIf(key -> key.startsWith(flowId + ":"));
    }

    private String key(long flowId, String nodeId) {
        return flowId + ":" + nodeId;
    }
}
