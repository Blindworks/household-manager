package com.household.manager.flowengine.nodes;

import com.household.manager.dto.LightStateRequest;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.SmartDeviceService;
import com.household.manager.tapo.TapoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LightSetNodeHandlerTest {

    @Mock
    private SmartDeviceService smartDeviceService;

    private LightSetNodeHandler handler() {
        return new LightSetNodeHandler(smartDeviceService);
    }

    @Test
    void setsBrightnessOnGivenDevice() {
        NodeConfig cfg = new NodeConfig(Map.of("deviceId", "42", "brightness", "80"));

        NodeResult result = handler().handle(FlowMessage.of(Map.of()), cfg, null);

        ArgumentCaptor<LightStateRequest> captor = ArgumentCaptor.forClass(LightStateRequest.class);
        verify(smartDeviceService).setLightState(eq(42L), captor.capture());
        LightStateRequest request = captor.getValue();
        assertEquals(80, request.getBrightness());
        assertNull(request.getHue());
        assertNull(request.getSaturation());
        assertNull(request.getColorTemp());
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void passesAllFourFieldsThroughToTheService() {
        NodeConfig cfg = new NodeConfig(Map.of(
                "deviceId", "7",
                "brightness", "50",
                "hue", "180",
                "saturation", "60",
                "colorTemp", "4000"));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        ArgumentCaptor<LightStateRequest> captor = ArgumentCaptor.forClass(LightStateRequest.class);
        verify(smartDeviceService).setLightState(eq(7L), captor.capture());
        LightStateRequest request = captor.getValue();
        assertEquals(50, request.getBrightness());
        assertEquals(180, request.getHue());
        assertEquals(60, request.getSaturation());
        assertEquals(4000, request.getColorTemp());
    }

    @Test
    void serviceExceptionDoesNotBreakTheFlowBranch() {
        NodeConfig cfg = new NodeConfig(Map.of("deviceId", "1", "brightness", "50"));
        doThrow(new TapoException("Geraet antwortet nicht")).when(smartDeviceService).setLightState(eq(1L), any());

        NodeResult result = handler().handle(FlowMessage.of(Map.of("foo", "bar")), cfg, null);

        assertFalse(result.outputs().isEmpty());
        assertEquals("bar", result.outputs().get(0).get(0).get("foo"));
    }

    @Test
    void illegalArgumentFromServiceDoesNotBreakTheFlowBranchEither() {
        NodeConfig cfg = new NodeConfig(Map.of("deviceId", "1", "hue", "50"));
        doThrow(new IllegalArgumentException("Geraet meldet COLOR nicht")).when(smartDeviceService)
                .setLightState(eq(1L), any());

        NodeResult result = handler().handle(FlowMessage.of(Map.of()), cfg, null);

        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void messageIsPassedThroughUnchanged() {
        NodeConfig cfg = new NodeConfig(Map.of("deviceId", "1", "brightness", "10"));
        FlowMessage msg = FlowMessage.of(Map.of("entityId", "light.x"));

        NodeResult result = handler().handle(msg, cfg, null);

        assertEquals(msg, result.outputs().get(0).get(0));
    }

    @Test
    void validateRequiresNumericDeviceId() {
        assertFalse(handler().validate(NodeConfig.empty()).isEmpty());
        assertFalse(handler().validate(new NodeConfig(Map.of("deviceId", "abc", "brightness", "10"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("deviceId", "1", "brightness", "10"))).isEmpty());
    }

    @Test
    void validateRequiresAtLeastOneLightField() {
        assertFalse(handler().validate(new NodeConfig(Map.of("deviceId", "1"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("deviceId", "1", "hue", "10"))).isEmpty());
    }

    @Test
    void validateRejectsNonNumericLightFields() {
        assertFalse(handler().validate(new NodeConfig(Map.of("deviceId", "1", "brightness", "bright"))).isEmpty());
        assertFalse(handler().validate(new NodeConfig(Map.of("deviceId", "1", "hue", "x"))).isEmpty());
        assertFalse(handler().validate(new NodeConfig(Map.of("deviceId", "1", "saturation", "x"))).isEmpty());
        assertFalse(handler().validate(new NodeConfig(Map.of("deviceId", "1", "colorTemp", "x"))).isEmpty());
    }

    @Test
    void validateAllowsHueWithoutSaturation() {
        // Bewusst erlaubt: der Backend-Service prueft hue/saturation unabhaengig voneinander,
        // ein alleinstehendes hue ist gueltig (Geraet behaelt seine aktuelle Saettigung).
        assertTrue(handler().validate(new NodeConfig(Map.of("deviceId", "1", "hue", "180"))).isEmpty());
    }

    @Test
    void typeAndPortsMatchCatalogExpectations() {
        assertEquals("light-set", handler().type());
        assertEquals(1, handler().outputPorts());
        assertFalse(handler().fields().isEmpty());
    }
}
