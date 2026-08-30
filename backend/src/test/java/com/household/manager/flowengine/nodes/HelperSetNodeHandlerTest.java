package com.household.manager.flowengine.nodes;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HelperSetNodeHandlerTest {

    private static final String HELPER = "input_boolean.manual_waschmaschine_fertig";

    @Mock
    private ManualEntityService manualEntityService;

    @Mock
    private AuditService auditService;

    private HelperSetNodeHandler handler() {
        return new HelperSetNodeHandler(manualEntityService, auditService);
    }

    @Test
    void setsHelperOnAndPassesTheMessageThrough() {
        NodeConfig cfg = new NodeConfig(Map.of("entityId", HELPER, "action", "on"));

        NodeResult result = handler().handle(FlowMessage.of(Map.of("foo", "bar")), cfg, null);

        verify(manualEntityService).setState(HELPER, "on");
        verify(auditService).record("helper.set", HELPER + " -> on");
        assertEquals("bar", result.outputs().get(0).get(0).get("foo"));
    }

    @Test
    void setsHelperOff() {
        NodeConfig cfg = new NodeConfig(Map.of("entityId", HELPER, "action", "off"));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        verify(manualEntityService).setState(HELPER, "off");
    }

    @Test
    void validationRejectsMissingEntityIdAndUnknownAction() {
        List<String> errors = handler().validate(new NodeConfig(Map.of("action", "an")));

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("entityId")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("action")));
    }

    @Test
    void validationAcceptsACompleteConfiguration() {
        assertTrue(handler().validate(new NodeConfig(Map.of("entityId", HELPER, "action", "off"))).isEmpty());
    }

    @Test
    void validationDoesNotTouchTheDatabase() {
        handler().validate(new NodeConfig(Map.of("entityId", HELPER, "action", "on")));

        // Ein Deploy darf nicht daran scheitern, dass der Helfer noch fehlt.
        verifyNoInteractions(manualEntityService);
    }

    @Test
    void serviceExceptionBreaksTheBranchInsteadOfBeingSwallowed() {
        NodeConfig cfg = new NodeConfig(Map.of("entityId", HELPER, "action", "on"));
        doThrow(new ResourceNotFoundException("Entity not found: " + HELPER))
                .when(manualEntityService).setState(HELPER, "on");

        assertThrows(ResourceNotFoundException.class,
                () -> handler().handle(FlowMessage.of(Map.of()), cfg, null));
    }

    @Test
    void exposesTypeFieldsAndSinglePort() {
        HelperSetNodeHandler h = handler();

        assertEquals("helper-set", h.type());
        assertEquals(1, h.outputPorts());
        assertEquals(List.of("Ausgang"), h.portLabels());
        assertEquals(NodeFieldType.ENTITY_REF, h.fields().get(0).type());
        assertTrue(h.fields().get(0).required());
        assertEquals(NodeFieldType.ENUM, h.fields().get(1).type());
        assertEquals(List.of("on", "off"), h.fields().get(1).options());
        assertFalse(h.fields().isEmpty());
    }
}
