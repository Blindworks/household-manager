package com.household.manager.flowengine.nodes;

import com.household.manager.dto.LightStateRequest;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.SmartDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktions-Node: setzt Helligkeit, Farbe und/oder Farbtemperatur einer Tapo-Lampe
 * ({@link SmartDeviceService#setLightState}).
 * <p>
 * Anders als switch-device schluckt dieser Handler Fehler beim Ansprechen des Geraets
 * (nicht erreichbar, Faehigkeit/Bereich vom Geraet abgelehnt): eine unerreichbare Lampe
 * darf einen nachgelagerten Telegram- oder Push-Zweig im selben Flow nicht mit abbrechen.
 * Der Fehler landet als Warnung im Log, die Message laeuft trotzdem auf dem Ausgang weiter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LightSetNodeHandler implements NodeHandler {

    private static final List<String> LIGHT_FIELDS = List.of("brightness", "hue", "saturation", "colorTemp");

    private final SmartDeviceService smartDeviceService;

    @Override
    public String type() {
        return "light-set";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.integer("deviceId").isEmpty()) {
            errors.add("deviceId fehlt oder ist nicht numerisch");
        }

        boolean anyLightFieldSet = false;
        for (String field : LIGHT_FIELDS) {
            String raw = config.string(field).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
            if (raw == null) {
                continue;
            }
            anyLightFieldSet = true;
            try {
                Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                errors.add(field + " muss numerisch sein");
            }
        }
        if (!anyLightFieldSet) {
            errors.add("mindestens ein Lichtwert (brightness, hue, saturation oder colorTemp) muss gesetzt sein");
        }

        // hue ohne saturation wird bewusst NICHT abgelehnt: SmartDeviceService prueft hue und
        // saturation unabhaengig voneinander (beide erfordern nur die COLOR-Faehigkeit) und laesst
        // ein alleinstehendes hue zu - das Geraet behaelt seine aktuelle Saettigung. Farbe und
        // Farbtemperatur sind dagegen exklusive Modi, das entscheidet ebenfalls erst das Backend
        // (colorTemp:0 beim Setzen von hue/saturation). Ein strengerer Validate-Fehler hier waere
        // enger als die eigentliche API-Grenze und muesste bei einer Aenderung dort mitgepflegt werden.
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        long deviceId = config.integer("deviceId").orElseThrow().longValue();
        LightStateRequest request = LightStateRequest.builder()
                .brightness(config.integer("brightness").orElse(null))
                .hue(config.integer("hue").orElse(null))
                .saturation(config.integer("saturation").orElse(null))
                .colorTemp(config.integer("colorTemp").orElse(null))
                .build();
        try {
            smartDeviceService.setLightState(deviceId, request);
        } catch (Exception ex) {
            log.warn("Konnte Lichtwert fuer Geraet {} nicht setzen: {}", deviceId, ex.getMessage());
        }
        return NodeResult.single(message);
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("deviceId", "Gerät", NodeFieldType.DEVICE_REF, true),
                NodeFieldDescriptor.field("brightness", "Helligkeit (1-100)", NodeFieldType.NUMBER, false),
                NodeFieldDescriptor.field("hue", "Farbton (0-360)", NodeFieldType.NUMBER, false),
                NodeFieldDescriptor.field("saturation", "Sättigung (0-100)", NodeFieldType.NUMBER, false),
                NodeFieldDescriptor.field("colorTemp", "Farbtemperatur in Kelvin", NodeFieldType.NUMBER, false));
    }
}
