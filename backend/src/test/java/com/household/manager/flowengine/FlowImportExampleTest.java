package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.nodes.AlexaAnnounceNodeHandler;
import com.household.manager.flowengine.nodes.DebugNodeHandler;
import com.household.manager.flowengine.nodes.DelayNodeHandler;
import com.household.manager.flowengine.nodes.EntityConditionHandler;
import com.household.manager.flowengine.nodes.EntityStateTriggerHandler;
import com.household.manager.flowengine.nodes.RateLimitNodeHandler;
import com.household.manager.flowengine.nodes.ScheduleTriggerHandler;
import com.household.manager.flowengine.nodes.SwitchDeviceNodeHandler;
import com.household.manager.service.AlexaAnnouncementService;
import com.household.manager.service.SmartDeviceService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hält die in docs/flows/flow-import-format.md dokumentierten Beispiel-Flows valide.
 * Die Ressourcen unter flow-examples/ sind Kopien der Doku-Beispiele — beide synchron halten.
 */
class FlowImportExampleTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FlowDefinitionParser parser = new FlowDefinitionParser(mapper);
    private final FlowValidator validator = buildValidator();

    private FlowValidator buildValidator() {
        EntityStateService entityStateService = mock(EntityStateService.class);
        when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());
        List<NodeHandler> handlers = List.of(
                new EntityStateTriggerHandler(entityStateService),
                new ScheduleTriggerHandler(),
                new EntityConditionHandler(entityStateService),
                new DelayNodeHandler(),
                new RateLimitNodeHandler(),
                new DebugNodeHandler(),
                new AlexaAnnounceNodeHandler(mock(AlexaAnnouncementService.class)),
                new SwitchDeviceNodeHandler(mock(SmartDeviceService.class)));
        return new FlowValidator(handlers, entityStateService);
    }

    @Test
    void motionLightExampleIsValid() throws Exception {
        assertExampleValid("motion-light.json");
    }

    @Test
    void temperatureAnnounceExampleIsValid() throws Exception {
        assertExampleValid("temperature-announce.json");
    }

    private void assertExampleValid(String file) throws Exception {
        JsonNode wrapper;
        try (InputStream in = getClass().getResourceAsStream("/flow-examples/" + file)) {
            assertNotNull(in, "Beispiel-Datei fehlt: " + file);
            wrapper = mapper.readTree(in);
        }
        assertEquals(1, wrapper.get("schemaVersion").asInt());
        FlowDefinition definition = parser.parse(wrapper.get("definition").toString());
        ValidationResult result = validator.validate(definition);
        assertTrue(result.valid(), "Beispiel " + file + " hat Fehler: " + result.errors());
    }
}
