package com.household.manager.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTriggerTest {

    @Test
    void describesEachTriggerType() {
        assertEquals("Grundtakt", new AgentTrigger(AgentTriggerType.SCHEDULED, List.of()).describe());
        assertEquals("Wecker: a, b", new AgentTrigger(AgentTriggerType.WAKE, List.of("a", "b")).describe());
        assertEquals("Manuell", new AgentTrigger(AgentTriggerType.MANUAL, List.of()).describe());
    }
}
