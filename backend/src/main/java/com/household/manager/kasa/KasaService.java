package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.kasa.exception.KasaCommunicationException;
import com.household.manager.smartdevice.LightState;
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
    private static final String TRANSITION_LIGHT_STATE = "transition_light_state";

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
     * @throws KasaCommunicationException if the bulb reports a non-zero {@code err_code}
     */
    public void turnOn(String ip, boolean bulb) {
        log.info("Sending Kasa command turnOn to {} (bulb={})", ip, bulb);
        if (bulb) {
            String response = kasaTcpClient.send(ip, bulbTransitionPayload(true));
            parseLightServiceResult(ip, response);
        } else {
            kasaTcpClient.send(ip, TURN_ON_PAYLOAD);
        }
    }

    public void turnOff(String ip) {
        turnOff(ip, false);
    }

    /** @throws KasaCommunicationException if the bulb reports a non-zero {@code err_code} */
    public void turnOff(String ip, boolean bulb) {
        log.info("Sending Kasa command turnOff to {} (bulb={})", ip, bulb);
        if (bulb) {
            String response = kasaTcpClient.send(ip, bulbTransitionPayload(false));
            parseLightServiceResult(ip, response);
        } else {
            kasaTcpClient.send(ip, TURN_OFF_PAYLOAD);
        }
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
     * called; this method only builds the protocol request, sends it, and interprets the response.
     * <p>
     * <b>{@code on_off: 1} and {@code ignore_default: 1} are ALWAYS sent, for every light-state
     * change, no matter which fields the caller set.</b> This is measured, not documented,
     * behaviour (real KL110, 192.168.1.101, 2026-08-19 — device state restored afterwards):
     * <ul>
     *   <li>{@code {"brightness":40}} while OFF, no {@code on_off} -> device stayed OFF, brightness
     *       unchanged, response still {@code err_code: 0}. A light-state change without
     *       {@code on_off} is therefore a SILENT NO-OP, indistinguishable from success.</li>
     *   <li>{@code {"on_off":1,"brightness":60}}, no {@code ignore_default} -> device switched ON,
     *       but brightness landed on its stored default (100), not the requested 60 - again
     *       {@code err_code: 0}. Skipping {@code ignore_default} silently discards every value.</li>
     *   <li>{@code {"on_off":1,"brightness":35,"ignore_default":1}} -> {@code brightness: 35},
     *       {@code err_code: 0}. Only with BOTH fields present does the requested value land.</li>
     * </ul>
     * A light-state change implies the bulb should end up on, so forcing {@code on_off: 1} here
     * (rather than requiring the caller to also call {@link #turnOn}) is the correct default, not
     * just a workaround for the {@code ignore_default} quirk.
     * <p>
     * <b>A response with {@code err_code: 0} does NOT prove the requested value was applied</b> —
     * a colour request sent to a bulb that reports no {@code COLOR} capability at all (this KL110)
     * still returned {@code err_code: 0} with {@code hue} unchanged. The capability check in
     * {@link com.household.manager.service.SmartDeviceService#validateLightStateRequest} (400
     * before this method is ever reached) is what actually prevents that case in practice; this
     * method additionally never trusts the request as fact — it parses the device's own reported
     * resulting state out of the response and returns THAT, so the caller persists reality instead
     * of an assumption.
     * <p>
     * The device DOES report real protocol errors: {@code {"brightness":150,"ignore_default":1}}
     * -> {@code {"err_code":-10000,"err_msg":"Invalid input argument"}}. A non-zero {@code err_code}
     * is therefore thrown as a {@link KasaCommunicationException} carrying {@code err_msg}.
     * <p>
     * Colour and colour-temperature remain mutually exclusive modes on these bulbs, exactly as on
     * the Tapo side (see {@code TapoDeviceService.buildSetDeviceInfoParams}, which this mirrors): a
     * colour request sends {@code color_temp: 0} alongside {@code hue}/{@code saturation} to force
     * the bulb out of white mode, but only when {@code deviceSupportsColorTemp} is true - a device
     * that doesn't report {@code COLOR_TEMP} might reject an unexpected {@code color_temp} field.
     *
     * @param deviceSupportsColorTemp whether the device reports the {@code COLOR_TEMP} capability
     * @return the device's own reported resulting state (never the request's values)
     * @throws KasaCommunicationException if the device does not answer or reports a non-zero {@code err_code}
     */
    public KasaLightCommandResult setLightState(String ip, LightState lightState, boolean deviceSupportsColorTemp) {
        ObjectNode transitionState = objectMapper.createObjectNode();
        transitionState.put("on_off", 1);
        transitionState.put("ignore_default", 1);
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
        service.set(TRANSITION_LIGHT_STATE, transitionState);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set(LIGHT_SERVICE, service);

        log.info("Sending Kasa light-state command to {}", ip);
        String response = kasaTcpClient.send(ip, payload.toString());
        JsonNode result = parseLightServiceResult(ip, response);
        return new KasaLightCommandResult(KasaSysInfoMapper.readOnOff(result), KasaSysInfoMapper.readLightState(result));
    }

    private String bulbTransitionPayload(boolean on) {
        ObjectNode transitionState = objectMapper.createObjectNode();
        transitionState.put("on_off", on ? 1 : 0);
        transitionState.put("transition_period", 0);
        ObjectNode service = objectMapper.createObjectNode();
        service.set(TRANSITION_LIGHT_STATE, transitionState);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set(LIGHT_SERVICE, service);
        return payload.toString();
    }

    /**
     * Parses a {@code smartlife.iot.smartbulb.lightingservice} response and validates its
     * {@code err_code} — every write to that namespace goes through here, whether it came from a
     * plain on/off toggle or a full light-state change, since the device reports real errors
     * (measured: {@code err_code: -10000} for an out-of-range brightness) on either. Returns the
     * {@code transition_light_state} node itself (the resulting state, shaped like a
     * {@code light_state} node — see {@link KasaSysInfoMapper}) so callers that need the resulting
     * values can read them without a second parse.
     */
    private JsonNode parseLightServiceResult(String ip, String response) {
        JsonNode result;
        try {
            result = objectMapper.readTree(response).path(LIGHT_SERVICE).path(TRANSITION_LIGHT_STATE);
        } catch (Exception ex) {
            throw new KasaCommunicationException("Failed to parse Kasa light-service response from " + ip, ex);
        }
        if (result.isMissingNode()) {
            throw new KasaCommunicationException(
                    "Kasa light-service response from " + ip + " does not contain " + LIGHT_SERVICE + "." + TRANSITION_LIGHT_STATE);
        }
        int errCode = result.path("err_code").asInt(0);
        if (errCode != 0) {
            String errMsg = result.path("err_msg").asText("unbekannter Fehler");
            throw new KasaCommunicationException(
                    "Kasa-Geraet " + ip + " lehnte den Lichtbefehl ab (err_code=" + errCode + "): " + errMsg);
        }
        return result;
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
