package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.kasa.exception.KasaCommunicationException;
import com.household.manager.tapo.LightState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KasaService {

    private static final String TURN_ON_PAYLOAD = "{\"system\":{\"set_relay_state\":{\"state\":1}}}";
    private static final String TURN_OFF_PAYLOAD = "{\"system\":{\"set_relay_state\":{\"state\":0}}}";
    private static final String GET_STATUS_PAYLOAD = "{\"system\":{\"get_sysinfo\":{}}}";

    /**
     * Documented-semantics, NOT measured against a real bulb (unlike the {@code get_sysinfo}
     * fixture, which was): switching and dimming a Kasa bulb go through the
     * {@code smartlife.iot.smartbulb.lightingservice} namespace rather than {@code system}. A
     * plug has no such service and keeps using {@code system.set_relay_state} exactly as before.
     */
    private static final String LIGHT_SERVICE = "smartlife.iot.smartbulb.lightingservice";

    private final KasaTcpClient kasaTcpClient;
    private final ObjectMapper objectMapper;

    /**
     * Legacy single-argument form kept for the raw, device-context-free {@code /kasa/{ip}/on}
     * controller endpoint, which has never known whether the IP behind it is a plug or a bulb.
     * Always sends the plug payload - unchanged behaviour from before bulb support existed.
     */
    public void turnOn(String ip) {
        turnOn(ip, false);
    }

    /**
     * @param bulb whether the device at {@code ip} is a bulb (routes to the lighting-service
     *             payload) or a plug (routes to {@code set_relay_state}, unchanged). Callers with
     *             device context (see {@link com.household.manager.service.SmartDeviceService})
     *             pass the bulb/plug distinction captured once at scan/probe/refresh time from the
     *             device's own sysinfo, rather than re-querying it here - Kasa devices accept only
     *             one TCP connection at a time, so an extra round trip before every single toggle
     *             would double command latency and risk contention with a concurrent status poll.
     */
    public void turnOn(String ip, boolean bulb) {
        log.info("Sending Kasa command turnOn to {} (bulb={})", ip, bulb);
        kasaTcpClient.send(ip, bulb ? bulbTransitionPayload(true) : TURN_ON_PAYLOAD);
    }

    public void turnOff(String ip) {
        turnOff(ip, false);
    }

    public void turnOff(String ip, boolean bulb) {
        log.info("Sending Kasa command turnOff to {} (bulb={})", ip, bulb);
        kasaTcpClient.send(ip, bulb ? bulbTransitionPayload(false) : TURN_OFF_PAYLOAD);
    }

    public KasaStatusDto getStatus(String ip) {
        log.info("Sending Kasa command getStatus to {}", ip);
        String response = kasaTcpClient.send(ip, GET_STATUS_PAYLOAD);
        JsonNode sysInfo = parseSysInfo(ip, response);
        return KasaSysInfoMapper.toStatusDto(sysInfo);
    }

    /**
     * Unicast counterpart to {@link KasaDiscoveryService#discover()} for networks where the
     * UDP broadcast discovery cannot reach the device (e.g. a Docker bridge network), but a
     * direct TCP connection works. Returns the same richer {@link KasaDiscoveryDto} discovery
     * uses, so a manually probed device is indistinguishable from a discovered one downstream.
     *
     * @param ip the device's IP address
     * @return the probed device's discovery information
     * @throws KasaCommunicationException if the device is unreachable or the response is malformed
     */
    public KasaDiscoveryDto probe(String ip) {
        log.info("Sending Kasa command probe to {}", ip);
        String response = kasaTcpClient.send(ip, GET_STATUS_PAYLOAD);
        JsonNode sysInfo = parseSysInfo(ip, response);
        return KasaSysInfoMapper.toDiscoveryDto(ip, sysInfo);
    }

    /**
     * Sets brightness, colour and/or colour temperature on a light-capable Kasa bulb via
     * {@code smartlife.iot.smartbulb.lightingservice.transition_light_state}. Capability and range
     * validation happens in
     * {@link com.household.manager.service.SmartDeviceService#setLightState} before this is
     * called; this method only builds the protocol request and sends it. Only the fields actually
     * set on {@code lightState} are added - {@code on_off} is deliberately never sent here, since
     * this call is for adjusting an already-known power state, not toggling it (that is
     * {@link #turnOn(String, boolean)}/{@link #turnOff(String, boolean)}).
     * <p>
     * Colour and colour-temperature are mutually exclusive modes on these bulbs, exactly as on the
     * Tapo side (see {@code TapoDeviceService.buildSetDeviceInfoParams}, which this mirrors): a
     * colour request sends {@code color_temp: 0} alongside {@code hue}/{@code saturation} to force
     * the bulb out of white mode, but only when {@code deviceSupportsColorTemp} is true - a device
     * that doesn't report {@code COLOR_TEMP} might reject an unexpected {@code color_temp} field.
     *
     * @param deviceSupportsColorTemp whether the device reports the {@code COLOR_TEMP} capability
     */
    public void setLightState(String ip, LightState lightState, boolean deviceSupportsColorTemp) {
        ObjectNode transitionState = objectMapper.createObjectNode();
        transitionState.put("transition_period", 0);

        if (lightState.brightness() != null) {
            transitionState.put("brightness", lightState.brightness());
        }

        boolean settingColor = lightState.hue() != null || lightState.saturation() != null;
        if (settingColor) {
            if (lightState.hue() != null) {
                transitionState.put("hue", lightState.hue());
            }
            if (lightState.saturation() != null) {
                transitionState.put("saturation", lightState.saturation());
            }
            if (deviceSupportsColorTemp) {
                transitionState.put("color_temp", 0);
            }
        } else if (lightState.colorTemp() != null) {
            transitionState.put("color_temp", lightState.colorTemp());
        }

        ObjectNode service = objectMapper.createObjectNode();
        service.set("transition_light_state", transitionState);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set(LIGHT_SERVICE, service);

        log.info("Sending Kasa light-state command to {}", ip);
        kasaTcpClient.send(ip, payload.toString());
    }

    private String bulbTransitionPayload(boolean on) {
        ObjectNode transitionState = objectMapper.createObjectNode();
        transitionState.put("on_off", on ? 1 : 0);
        transitionState.put("transition_period", 0);
        ObjectNode service = objectMapper.createObjectNode();
        service.set("transition_light_state", transitionState);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set(LIGHT_SERVICE, service);
        return payload.toString();
    }

    private JsonNode parseSysInfo(String ip, String response) {
        try {
            JsonNode sysInfo = objectMapper.readTree(response)
                    .path("system")
                    .path("get_sysinfo");

            if (sysInfo.isMissingNode()) {
                throw new KasaCommunicationException("Kasa response from " + ip + " does not contain system.get_sysinfo");
            }

            return sysInfo;
        } catch (KasaCommunicationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KasaCommunicationException("Failed to parse Kasa status response from " + ip, ex);
        }
    }
}
