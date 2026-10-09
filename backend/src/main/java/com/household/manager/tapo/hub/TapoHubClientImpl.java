package com.household.manager.tapo.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.household.manager.tapo.TapoAuthProtocol;
import com.household.manager.tapo.TapoDeviceFactory;
import com.household.manager.tapo.TapoException;
import com.household.manager.tapo.TapoLocalDeviceConnection;
import com.household.manager.tapo.TapoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Spricht den H100-Hub lokal an (KLAP, Fallback AES). Pro Hub-IP wird das funktionierende
 * Protokoll und die Verbindung gecacht; ein Fehler verwirft die Verbindung und wiederholt
 * genau einmal frisch. Wiederholen ist unkritisch, weil alle Schreibbefehle absolute Werte setzen.
 *
 * <p>Schreibpfad und Feldnamen stuetzen sich auf dokumentierte Semantik, NICHT auf einen
 * Realtest gegen die Hardware.
 */
@Service
@Slf4j
public class TapoHubClientImpl implements TapoHubClient {

    private static final TapoAuthProtocol[] PROTOCOL_ORDER = {TapoAuthProtocol.KLAP, TapoAuthProtocol.AES};

    private final TapoDeviceFactory deviceFactory;
    private final TapoProperties tapoProperties;
    private final ThermostatReadingMapper readingMapper;
    private final ObjectMapper objectMapper;

    private final Map<String, TapoAuthProtocol> protocolByHub = new ConcurrentHashMap<>();
    private final Map<String, TapoLocalDeviceConnection> connectionByHub = new ConcurrentHashMap<>();

    public TapoHubClientImpl(TapoDeviceFactory deviceFactory,
                             TapoProperties tapoProperties,
                             ThermostatReadingMapper readingMapper,
                             ObjectMapper objectMapper) {
        this.deviceFactory = deviceFactory;
        this.tapoProperties = tapoProperties;
        this.readingMapper = readingMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ThermostatReading> readThermostats(String hubIp) {
        return execute(hubIp, this::readChildren);
    }

    @Override
    public void setTargetTemp(String hubIp, String childDeviceId, double targetTemp) {
        ObjectNode params = objectMapper.createObjectNode().put("target_temp", targetTemp);
        sendChildCommand(hubIp, childDeviceId, params);
    }

    @Override
    public void setFrostProtection(String hubIp, String childDeviceId, boolean on) {
        ObjectNode params = objectMapper.createObjectNode().put("frost_protection_on", on);
        sendChildCommand(hubIp, childDeviceId, params);
    }

    private void sendChildCommand(String hubIp, String childDeviceId, ObjectNode setParams) {
        JsonNode result = execute(hubIp,
                conn -> conn.request("control_child", controlChild(childDeviceId, setParams)));
        requireChildAccepted(childDeviceId, result);
    }

    /**
     * Das aeussere error_code (schon von der Verbindung geprueft) sagt nur, dass der Hub den
     * Befehl weitergereicht hat; ein vom Kind abgelehnter steht in response_data.error_code.
     * Bewusst ausserhalb von {@code execute}: eine Ablehnung ist kein Verbindungsfehler und
     * soll nicht mit neuer Verbindung wiederholt werden.
     */
    private void requireChildAccepted(String childDeviceId, JsonNode result) {
        if (result == null) {
            return;
        }
        JsonNode errorCode = result.path("response_data").path("error_code");
        if (errorCode.isNumber() && errorCode.asInt() != 0) {
            throw new TapoException("Thermostat " + childDeviceId + " hat den Befehl abgelehnt (error_code "
                    + errorCode.asInt() + ").");
        }
    }

    @Override
    public HubProbeResult probeHub(String hubIp) {
        TapoException last = null;
        for (TapoAuthProtocol protocol : PROTOCOL_ORDER) {
            try {
                TapoLocalDeviceConnection conn = newConnection(protocol, hubIp);
                JsonNode info = conn.getDeviceInfo();
                List<ThermostatReading> thermostats = readChildren(conn);
                remember(hubIp, protocol, conn);
                return new HubProbeResult(info.path("device_id").asText(null), thermostats);
            } catch (RuntimeException ex) {
                log.debug("Hub-Probe {} mit {} fehlgeschlagen: {}", hubIp, protocol, ex.getMessage());
                last = new TapoException("Tapo-Hub unter " + hubIp + " ist weder ueber KLAP noch ueber AES erreichbar.", ex);
            }
        }
        throw last;
    }

    private List<ThermostatReading> readChildren(TapoLocalDeviceConnection conn) {
        return readingMapper.map(conn.request("get_child_device_list", null));
    }

    private ObjectNode controlChild(String childDeviceId, ObjectNode setParams) {
        ObjectNode inner = objectMapper.createObjectNode().put("method", "set_device_info");
        inner.set("params", setParams);
        ObjectNode params = objectMapper.createObjectNode().put("device_id", childDeviceId);
        params.set("requestData", inner);
        return params;
    }

    private <T> T execute(String hubIp, Function<TapoLocalDeviceConnection, T> action) {
        TapoAuthProtocol known = protocolByHub.get(hubIp);
        if (known == null) {
            return detectAndExecute(hubIp, action);
        }
        try {
            return action.apply(connectionFor(hubIp, known));
        } catch (TapoException first) {
            log.debug("Hub {} Anfrage fehlgeschlagen ({}), wiederhole mit neuer Verbindung", hubIp, first.getMessage());
            connectionByHub.remove(hubIp);
            return action.apply(connectionFor(hubIp, known));
        }
    }

    private <T> T detectAndExecute(String hubIp, Function<TapoLocalDeviceConnection, T> action) {
        TapoException last = null;
        for (TapoAuthProtocol protocol : PROTOCOL_ORDER) {
            try {
                TapoLocalDeviceConnection conn = newConnection(protocol, hubIp);
                T result = action.apply(conn);
                remember(hubIp, protocol, conn);
                return result;
            } catch (TapoException ex) {
                last = ex;
            }
        }
        throw last;
    }

    private TapoLocalDeviceConnection connectionFor(String hubIp, TapoAuthProtocol protocol) {
        return connectionByHub.computeIfAbsent(hubIp, ip -> newConnection(protocol, ip));
    }

    private TapoLocalDeviceConnection newConnection(TapoAuthProtocol protocol, String hubIp) {
        return deviceFactory.create(protocol, hubIp, tapoProperties.getEmail(), tapoProperties.getPassword());
    }

    private void remember(String hubIp, TapoAuthProtocol protocol, TapoLocalDeviceConnection conn) {
        protocolByHub.put(hubIp, protocol);
        connectionByHub.put(hubIp, conn);
    }
}
