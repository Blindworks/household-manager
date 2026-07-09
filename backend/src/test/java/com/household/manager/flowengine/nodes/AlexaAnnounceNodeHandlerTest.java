package com.household.manager.flowengine.nodes;

import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.AlexaAnnouncementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlexaAnnounceNodeHandlerTest {

    @Mock
    private AlexaAnnouncementService announcementService;

    private AlexaAnnounceNodeHandler handler() {
        return new AlexaAnnounceNodeHandler(announcementService);
    }

    @Test
    void announcesWithResolvedPlaceholders() {
        NodeConfig cfg = new NodeConfig(Map.of(
                "text", "Temperatur ist {newState} Grad ({entityId})",
                "mode", "ANNOUNCE",
                "deviceSerials", List.of("G09")));
        FlowMessage msg = FlowMessage.of(Map.of(
                "entityId", "sensor.x", "newState", "21.5", "oldState", "20"));

        NodeResult result = handler().handle(msg, cfg, null);

        verify(announcementService).announce("Temperatur ist 21.5 Grad (sensor.x)", List.of("G09"), AlexaTtsMode.ANNOUNCE);
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void missingPlaceholderValuesRenderEmpty() {
        NodeConfig cfg = new NodeConfig(Map.of(
                "text", "Wert: {newState}", "mode", "SPEAK", "deviceSerials", List.of("G09")));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        verify(announcementService).announce("Wert: ", List.of("G09"), AlexaTtsMode.SPEAK);
    }

    @Test
    void validateRequiresTextModeAndDevices() {
        assertEquals(3, handler().validate(NodeConfig.empty()).size());
        assertFalse(handler().validate(new NodeConfig(Map.of(
                "text", "x", "mode", "FALSCH", "deviceSerials", List.of("G09")))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of(
                "text", "x", "mode", "ANNOUNCE", "deviceSerials", List.of("G09")))).isEmpty());
    }
}
