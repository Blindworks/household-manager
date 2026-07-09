package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitNodeHandlerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-09T10:00:00Z"));
    private RateLimitNodeHandler handler;
    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private NodeContext ctx;

    @BeforeEach
    void setUp() {
        handler = new RateLimitNodeHandler(new Clock() {
            public Instant instant() { return now.get(); }
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
        });
        ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "r"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { }
            public org.springframework.scheduling.TaskScheduler scheduler() { return null; }
            public void debug(String label, FlowMessage message) { }
        };
    }

    private final NodeConfig cfg = new NodeConfig(Map.of("minIntervalSeconds", 1800));
    private final FlowMessage msg = FlowMessage.of(Map.of());

    @Test
    void firstMessagePassesSecondWithinIntervalIsDropped() {
        assertFalse(handler.handle(msg, cfg, ctx).outputs().isEmpty());
        now.set(now.get().plusSeconds(60));
        assertTrue(handler.handle(msg, cfg, ctx).outputs().isEmpty());
    }

    @Test
    void messageAfterIntervalPassesAgain() {
        handler.handle(msg, cfg, ctx);
        now.set(now.get().plusSeconds(1801));
        assertFalse(handler.handle(msg, cfg, ctx).outputs().isEmpty());
    }

    @Test
    void validateRequiresPositiveInterval() {
        assertFalse(handler.validate(NodeConfig.empty()).isEmpty());
        assertTrue(handler.validate(cfg).isEmpty());
    }
}
