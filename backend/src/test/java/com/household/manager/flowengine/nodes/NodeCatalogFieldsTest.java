package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeCatalogFieldsTest {

    private NodeFieldDescriptor field(List<NodeFieldDescriptor> fields, String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void entityStateTriggerFieldsAndPorts() {
        var h = new EntityStateTriggerHandler(null);
        var fields = h.fields();
        assertEquals(NodeFieldType.ENTITY_REF, field(fields, "entityId").type());
        assertTrue(field(fields, "entityId").required());
        assertEquals(NodeFieldType.ENUM, field(fields, "operator").type());
        assertTrue(field(fields, "operator").options().contains("changed"));
        assertEquals(NodeFieldType.NUMBER, field(fields, "forSeconds").type());
        assertFalse(field(fields, "forSeconds").required());
        assertEquals(List.of("Ausgang"), h.portLabels());
    }

    @Test
    void entityEventTriggerFieldsAndPorts() {
        var h = new EntityEventTriggerHandler();
        var fields = h.fields();
        assertEquals(NodeFieldType.ENTITY_REF, field(fields, "entityId").type());
        assertTrue(field(fields, "entityId").required());
        assertFalse(field(fields, "action").required());
        assertEquals(List.of("Ausgang"), h.portLabels());
    }

    @Test
    void entityConditionHasTruthyFalsyPortLabels() {
        var h = new EntityConditionHandler(null);
        assertEquals(List.of("wahr", "falsch"), h.portLabels());
        assertEquals(NodeFieldType.ENTITY_REF, field(h.fields(), "entityId").type());
    }

    @Test
    void scheduleTriggerHasCronField() {
        assertEquals(NodeFieldType.STRING, field(new ScheduleTriggerHandler().fields(), "cron").type());
    }

    @Test
    void delayAndRateLimitHaveNumberFields() {
        assertEquals(NodeFieldType.NUMBER, field(new DelayNodeHandler().fields(), "seconds").type());
        assertEquals(NodeFieldType.NUMBER, field(new RateLimitNodeHandler().fields(), "minIntervalSeconds").type());
    }

    @Test
    void debugHasOptionalLabelAndNoPorts() {
        var h = new DebugNodeHandler();
        assertFalse(field(h.fields(), "label").required());
        assertTrue(h.portLabels().isEmpty());
    }

    @Test
    void alexaAnnounceFields() {
        var fields = new AlexaAnnounceNodeHandler(null).fields();
        assertEquals(NodeFieldType.STRING, field(fields, "text").type());
        assertEquals(List.of("SPEAK", "ANNOUNCE"), field(fields, "mode").options());
        assertEquals(NodeFieldType.ALEXA_DEVICE_LIST, field(fields, "deviceSerials").type());
    }

    @Test
    void switchDeviceFields() {
        var fields = new SwitchDeviceNodeHandler(null, null).fields();
        assertEquals(NodeFieldType.DEVICE_REF, field(fields, "deviceId").type());
        assertEquals(List.of("on", "off"), field(fields, "action").options());
    }

    @Test
    void nukiLockActionFields() {
        var fields = new NukiLockActionNodeHandler(null).fields();
        assertEquals(NodeFieldType.STRING, field(fields, "smartlockId").type());
        assertTrue(field(fields, "smartlockId").required());
        assertEquals(NodeFieldType.ENUM, field(fields, "action").type());
        assertTrue(field(fields, "action").required());
        assertEquals(List.of("lock", "unlock", "unlatch"), field(fields, "action").options());
    }

    @Test
    void helperSetHasEntityRefAndOnOffAction() {
        var h = new HelperSetNodeHandler(null, null);
        var fields = h.fields();
        assertEquals(NodeFieldType.ENTITY_REF, field(fields, "entityId").type());
        assertTrue(field(fields, "entityId").required());
        assertEquals(List.of("on", "off"), field(fields, "action").options());
        assertEquals(List.of("Ausgang"), h.portLabels());
    }
}
