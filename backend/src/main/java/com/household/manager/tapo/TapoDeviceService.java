package com.household.manager.tapo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level Tapo device service.
 * <p>
 * Control priority:
 * 1. Local KLAP protocol (most devices with current firmware)
 * 2. Local AES protocol (fallback for older firmware)
 * 3. V2 Cloud API (last resort)
 * <p>
 * On local failure, the service automatically retries with the other protocol
 * and caches the working protocol for subsequent requests.
 */
@Service
@Slf4j
public class TapoDeviceService {

    private final TapoCloudService tapoCloudService;
    private final TapoDiscoveryService tapoDiscoveryService;
    private final TapoDeviceFactory tapoDeviceFactory;
    private final TapoProperties tapoProperties;
    private final SmartDeviceRepository smartDeviceRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, TapoLocalDeviceConnection> localConnectionCache = new ConcurrentHashMap<>();
    private final Map<String, TapoAuthProtocol> workingProtocolCache = new ConcurrentHashMap<>();
    private final Map<String, String> deviceIpCache = new ConcurrentHashMap<>();

    public TapoDeviceService(TapoCloudService tapoCloudService,
                             TapoDiscoveryService tapoDiscoveryService,
                             TapoDeviceFactory tapoDeviceFactory,
                             TapoProperties tapoProperties,
                             SmartDeviceRepository smartDeviceRepository,
                             ObjectMapper objectMapper) {
        this.tapoCloudService = tapoCloudService;
        this.tapoDiscoveryService = tapoDiscoveryService;
        this.tapoDeviceFactory = tapoDeviceFactory;
        this.tapoProperties = tapoProperties;
        this.smartDeviceRepository = smartDeviceRepository;
        this.objectMapper = objectMapper;

        log.info("Tapo-Konfiguration: email={}, {} statische Geraete konfiguriert",
                tapoProperties.getEmail() != null ? tapoProperties.getEmail() : "NICHT GESETZT",
                tapoProperties.getDevices().size());
        for (int i = 0; i < tapoProperties.getDevices().size(); i++) {
            TapoProperties.TapoDeviceConfig config = tapoProperties.getDevices().get(i);
            log.info("  Tapo-Geraet[{}]: name={}, ip={}, deviceId={}", i,
                    config.getName(), config.getIp(), config.getDeviceId());
            if (config.getDeviceId() != null && !config.getDeviceId().isBlank()
                    && config.getIp() != null && !config.getIp().isBlank()) {
                deviceIpCache.put(config.getDeviceId(), config.getIp());
                log.info("  -> Statische Tapo-IP registriert: {} -> {}", config.getDeviceId(), config.getIp());
            }
        }
    }

    public List<TapoCloudDevice> discoverCloudDevices() {
        return tapoCloudService.getTapoDevices(true);
    }

    public List<TapoDiscoveryDevice> discoverLocalDevices() {
        List<TapoDiscoveryDevice> devices = new java.util.ArrayList<>();

        // 1. UDP broadcast discovery
        try {
            List<TapoDiscoveryDevice> udpDevices = tapoDiscoveryService.discoverLocalDevices(tapoProperties, tapoDeviceFactory);
            devices.addAll(udpDevices);
        } catch (Exception ex) {
            log.debug("UDP-Discovery fehlgeschlagen: {}", ex.getMessage());
        }

        // 2. Static device config (for Docker / firewalled environments)
        java.util.Set<String> discoveredIps = devices.stream()
                .map(TapoDiscoveryDevice::ipAddress)
                .collect(java.util.stream.Collectors.toSet());

        for (TapoProperties.TapoDeviceConfig config : tapoProperties.getDevices()) {
            if (config.getIp() == null || config.getIp().isBlank()) continue;
            if (discoveredIps.contains(config.getIp())) continue;

            try {
                TapoDiscoveryDevice device = probeStaticDevice(config);
                if (device != null) {
                    devices.add(device);
                    log.info("Statisch konfiguriertes Tapo-Geraet gefunden: {} ({})", config.getName(), config.getIp());
                }
            } catch (Exception ex) {
                log.debug("Statisches Geraet {} ({}) nicht erreichbar: {}", config.getName(), config.getIp(), ex.getMessage());
            }
        }

        // Discovery updates only the in-memory caches. Writing to smart_devices here
        // would run as a side effect inside whatever transaction called us (refresh /
        // switch), and concurrent background refreshes would then write the same rows
        // from parallel connections -> MariaDB 1020 "Record has changed". Persistence
        // belongs solely to the explicit scan (SmartDeviceService.upsertTapoDevice).
        for (TapoDiscoveryDevice device : devices) {
            if (device.deviceId() != null && device.ipAddress() != null) {
                deviceIpCache.put(device.deviceId(), device.ipAddress());
                workingProtocolCache.put(device.deviceId(), device.authProtocol());
                getOrCreateLocalConnection(device.deviceId(), device.ipAddress(), device.authProtocol());
            }
        }
        return devices;
    }

    /**
     * Probe a statically configured device by trying KLAP, then AES.
     */
    private TapoDiscoveryDevice probeStaticDevice(TapoProperties.TapoDeviceConfig config) {
        LocalHandshakeResult handshake = tryLocalHandshake(config.getIp(), config.getName());
        if (handshake == null) {
            return null;
        }
        JsonNode info = handshake.info();
        String deviceId = config.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = firstText(info, "device_id", "deviceId");
        }
        return new TapoDiscoveryDevice(
                config.getIp(), handshake.protocol(), deviceId,
                firstText(info, "model", "device_model"),
                firstText(info, "nickname", "alias"),
                info.path("device_on").asBoolean(false)
        );
    }

    /**
     * Manually probes a device directly by IP, bypassing discovery and the connection caches
     * entirely: tries KLAP first (current firmware default), then AES (older firmware). Used to
     * set a device's address by hand when local UDP broadcast discovery cannot reach it (e.g. the
     * production backend running inside a Docker bridge network) but a direct local connection
     * works — mirrors {@link com.household.manager.kasa.KasaService#probe(String)}.
     *
     * @throws TapoException if the device answers neither protocol; a wrong IP must fail loudly
     *                        instead of silently persisting an unreachable address
     */
    public TapoAddressProbeResult probeAddress(String ip) {
        LocalHandshakeResult handshake = tryLocalHandshake(ip, ip);
        if (handshake == null) {
            throw new TapoException("Tapo-Geraet unter " + ip + " ist weder ueber KLAP noch ueber AES erreichbar.");
        }
        TapoDeviceState state = TapoDeviceState.fromLocal(handshake.info(), tapoCloudService);
        String deviceId = firstText(handshake.info(), "device_id", "deviceId");
        return new TapoAddressProbeResult(deviceId, handshake.protocol(), state);
    }

    /**
     * Shared KLAP-then-AES probe used by {@link #probeStaticDevice} and {@link #probeAddress}.
     * Returns the working protocol alongside the response rather than caching it on the instance:
     * this service is a singleton bean and concurrent probes (a scheduled scan racing a manual
     * "set address" request) must not share mutable state.
     */
    private LocalHandshakeResult tryLocalHandshake(String ip, String label) {
        for (TapoAuthProtocol protocol : new TapoAuthProtocol[]{TapoAuthProtocol.KLAP, TapoAuthProtocol.AES}) {
            try {
                TapoLocalDeviceConnection conn = tapoDeviceFactory.create(protocol, ip,
                        tapoProperties.getEmail(), tapoProperties.getPassword());
                JsonNode info = conn.getDeviceInfo();
                return new LocalHandshakeResult(protocol, info);
            } catch (Exception ex) {
                log.debug("Probe {} mit {} fuer {} fehlgeschlagen: {}", ip, protocol, label, ex.getMessage());
            }
        }
        return null;
    }

    private record LocalHandshakeResult(TapoAuthProtocol protocol, JsonNode info) {
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public TapoDeviceState getStatus(String deviceId) {
        return getStatus(deviceId, resolveIpAddress(deviceId), null);
    }

    public TapoDeviceState getStatus(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        JsonNode deviceInfo = executeLocalReadOnly(deviceId, ipAddress, protocol,
                TapoLocalDeviceConnection::getDeviceInfo);
        return TapoDeviceState.fromLocal(deviceInfo, tapoCloudService);
    }

    public void turnOn(String deviceId) {
        turnOn(deviceId, resolveIpAddress(deviceId), null);
    }

    public void turnOn(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        executeLocalWithRediscovery(deviceId, ipAddress, protocol,
                conn -> { conn.setDevicePowered(true); return null; });
        log.info("Tapo device switched on locally (deviceId={})", deviceId);
    }

    public void turnOff(String deviceId) {
        turnOff(deviceId, resolveIpAddress(deviceId), null);
    }

    public void turnOff(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        executeLocalWithRediscovery(deviceId, ipAddress, protocol,
                conn -> { conn.setDevicePowered(false); return null; });
        log.info("Tapo device switched off locally (deviceId={})", deviceId);
    }

    /**
     * Sets brightness, colour and/or colour temperature on a light-capable Tapo device via
     * {@code set_device_info}. Capability and range validation happens in
     * {@link com.household.manager.service.SmartDeviceService#setLightState} before this is
     * called; this method only builds the protocol request and sends it.
     *
     * @param deviceSupportsColorTemp whether the device reports the {@code COLOR_TEMP} capability
     *                                — gates whether a colour request is allowed to append
     *                                {@code color_temp: 0} (see {@link #buildSetDeviceInfoParams})
     */
    public void setLightState(String deviceId, String ipAddress, TapoAuthProtocol protocol,
                               LightState lightState, boolean deviceSupportsColorTemp) {
        ObjectNode params = buildSetDeviceInfoParams(lightState, deviceSupportsColorTemp);
        executeLocalWithRediscovery(deviceId, ipAddress, protocol,
                conn -> { conn.setDeviceInfo(params); return null; });
        log.info("Tapo light state set locally (deviceId={})", deviceId);
    }

    /**
     * Builds the {@code set_device_info} params for a light-state change. Only the fields
     * actually set on {@code lightState} are added to the request.
     * <p>
     * <b>Colour and colour-temperature are mutually exclusive modes on these bulbs</b> (verified
     * against the real L530 protocol behaviour, see {@code TapoLocalProbeManualTest} / the
     * tplink-leuchtmittel plan Task 1/4): setting {@code hue}/{@code saturation} while a non-zero
     * {@code color_temp} is still active leaves the bulb in white mode instead of switching it to
     * colour mode. A colour request must therefore explicitly send {@code color_temp: 0} alongside
     * {@code hue}/{@code saturation} — <b>but only when {@code deviceSupportsColorTemp} is true</b>.
     * A device that reports {@code COLOR} without {@code COLOR_TEMP} would reject an unexpected
     * {@code color_temp} field outright ({@code validateResponse} throws on any non-zero
     * {@code error_code}), and that failure then burns a protocol fallback plus a UDP
     * re-discovery before surfacing as "beide Protokolle fehlgeschlagen" — pointing at the network
     * instead of the actual cause, an unsupported parameter. Conversely, a pure colour-temperature
     * request sends only {@code color_temp} and omits {@code hue}/{@code saturation} entirely,
     * since sending either (even unset/0) risks re-triggering colour mode on some firmware. This
     * is the single place that encodes the rule; callers just describe the desired end state via
     * {@link LightState} plus whether the device supports colour temperature at all.
     */
    private ObjectNode buildSetDeviceInfoParams(LightState lightState, boolean deviceSupportsColorTemp) {
        ObjectNode params = objectMapper.createObjectNode();
        if (lightState.brightness() != null) {
            params.put("brightness", lightState.brightness());
        }

        boolean settingColor = lightState.hue() != null || lightState.saturation() != null;
        if (settingColor) {
            if (lightState.hue() != null) {
                params.put("hue", lightState.hue());
            }
            if (lightState.saturation() != null) {
                params.put("saturation", lightState.saturation());
            }
            if (deviceSupportsColorTemp) {
                params.put("color_temp", 0);
            }
        } else if (lightState.colorTemp() != null) {
            params.put("color_temp", lightState.colorTemp());
        }

        return params;
    }

    public JsonNode getEnergyUsage(String deviceId) {
        return getEnergyUsage(deviceId, resolveIpAddress(deviceId), null);
    }

    public JsonNode getEnergyUsage(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        return executeLocalReadOnly(deviceId, ipAddress, protocol,
                TapoLocalDeviceConnection::getEnergyUsage);
    }

    public JsonNode getCurrentPower(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            return executeLocalWithFallback(deviceId, ipAddress, protocol,
                    TapoLocalDeviceConnection::getCurrentPower);
        }
        throw new TapoException("getCurrentPower benoetigt eine lokale IP-Adresse.");
    }

    public String decodeAlias(String alias) {
        return tapoCloudService.decodeAlias(alias);
    }

    public Map<String, Object> buildMetadata(TapoCloudDevice device) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("aliasRaw", device.alias());
        metadata.put("deviceName", device.deviceName());
        metadata.put("deviceType", device.deviceType());
        metadata.put("deviceHwVer", device.deviceHwVer());
        metadata.put("deviceMac", device.deviceMac());
        metadata.put("fwVer", device.fwVer());
        metadata.put("appServerUrl", device.appServerUrl());
        metadata.put("cloudStatus", device.status());
        metadata.put("role", device.role());
        return metadata;
    }

    public void clearLocalConnection(String deviceId) {
        removeLocalConnections(deviceId);
        workingProtocolCache.remove(deviceId);
        deviceIpCache.remove(deviceId);
    }

    /**
     * Reiner Lesepfad (Status/Energie): genau EIN lokaler Versuch mit der bekannten IP,
     * ohne Re-Discovery. Statusabfragen werden im Hintergrund und parallel fuer alle
     * Geraete gefeuert; eine Re-Discovery pro Fehlschlag wuerde einen UDP-Broadcast-Sturm
     * ausloesen. Bei fehlender IP oder lokalem Fehlschlag wird eine TapoException geworfen,
     * die der Aufrufer als "offline" behandelt. Selbstheilung bleibt dem Schreibpfad
     * (turnOn/turnOff) vorbehalten.
     */
    private JsonNode executeLocalReadOnly(String deviceId, String ipAddress,
                                          TapoAuthProtocol protocol, LocalDeviceAction action) {
        if (ipAddress == null || ipAddress.isBlank()) {
            throw new TapoException("Keine lokale IP fuer Tapo-Geraet " + deviceId + " bekannt.");
        }
        return executeLocalWithFallback(deviceId, ipAddress, protocol, action);
    }

    /**
     * Schreibpfad (turnOn/turnOff): fuehrt eine lokale Aktion aus. Schlaegt sie fehl
     * (oder fehlt die IP), laeuft genau EINE Re-Discovery; liefert sie eine neue IP,
     * wird die Aktion einmal wiederholt. Der Tapo-Cloud-Passthrough kann Geraete nicht
     * steuern (immer -20571) und wird bewusst nicht mehr verwendet.
     */
    private JsonNode executeLocalWithRediscovery(String deviceId, String ipAddress,
                                                 TapoAuthProtocol protocol,
                                                 LocalDeviceAction action) {
        if (ipAddress == null || ipAddress.isBlank()) {
            String discovered = rediscoverIp(deviceId);
            if (discovered == null) {
                throw new TapoException("Keine IP fuer Tapo-Geraet " + deviceId
                        + " bekannt (auch nach erneuter Suche). Bitte Rescan ausfuehren.");
            }
            return executeLocalWithFallback(deviceId, discovered, resolveProtocol(deviceId, protocol), action);
        }
        try {
            return executeLocalWithFallback(deviceId, ipAddress, protocol, action);
        } catch (Exception ex) {
            log.info("Lokale Steuerung fuer {} ({}) fehlgeschlagen ({}), starte Re-Discovery",
                    deviceId, ipAddress, ex.getMessage());
            String freshIp = rediscoverIp(deviceId);
            if (freshIp == null || freshIp.equals(ipAddress)) {
                throw new TapoException("Tapo-Geraet " + deviceId
                        + " ist lokal nicht erreichbar (auch nach erneuter Suche): " + ex.getMessage(), ex);
            }
            log.info("Neue IP fuer {} gefunden: {} (vorher {})", deviceId, freshIp, ipAddress);
            return executeLocalWithFallback(deviceId, freshIp, resolveProtocol(deviceId, null), action);
        }
    }

    private String rediscoverIp(String deviceId) {
        deviceIpCache.remove(deviceId);
        removeLocalConnections(deviceId);
        try {
            discoverLocalDevices();
        } catch (Exception ex) {
            log.debug("Re-Discovery fehlgeschlagen: {}", ex.getMessage());
        }
        return deviceIpCache.get(deviceId);
    }

    private void removeLocalConnections(String deviceId) {
        for (TapoAuthProtocol protocol : TapoAuthProtocol.values()) {
            localConnectionCache.remove(deviceId + ":" + protocol.name());
        }
    }

    /**
     * Try the preferred protocol first, then fall back to the alternative protocol.
     * Caches the working protocol for future requests.
     */
    private JsonNode executeLocalWithFallback(String deviceId, String ipAddress,
                                               TapoAuthProtocol protocol,
                                               LocalDeviceAction action) {
        TapoAuthProtocol preferred = resolveProtocol(deviceId, protocol);
        TapoAuthProtocol alternative = preferred == TapoAuthProtocol.KLAP
                ? TapoAuthProtocol.AES : TapoAuthProtocol.KLAP;

        // Try preferred protocol
        try {
            TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, ipAddress, preferred);
            JsonNode result = action.execute(connection);
            workingProtocolCache.put(deviceId, preferred);
            return result;
        } catch (Exception ex) {
            log.debug("{}-Protokoll fuer {} fehlgeschlagen: {}, versuche {}",
                    preferred, deviceId, ex.getMessage(), alternative);
            localConnectionCache.remove(deviceId + ":" + preferred.name());
        }

        // Try alternative protocol
        try {
            TapoLocalDeviceConnection connection = getOrCreateLocalConnection(deviceId, ipAddress, alternative);
            JsonNode result = action.execute(connection);
            workingProtocolCache.put(deviceId, alternative);
            log.info("Tapo device {} funktioniert mit {} (statt {})", deviceId, alternative, preferred);
            return result;
        } catch (Exception ex) {
            localConnectionCache.remove(deviceId + ":" + alternative.name());
            throw new TapoException("Lokale Steuerung fuer " + deviceId + " mit beiden Protokollen fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    String resolveIpAddress(String deviceId) {
        String cached = deviceIpCache.get(deviceId);
        if (cached != null) {
            log.debug("IP fuer {} aus Cache: {}", deviceId, cached);
            return cached;
        }
        for (TapoProperties.TapoDeviceConfig config : tapoProperties.getDevices()) {
            if (deviceId.equals(config.getDeviceId()) && config.getIp() != null && !config.getIp().isBlank()) {
                deviceIpCache.put(deviceId, config.getIp());
                log.info("IP fuer {} aus statischer Konfiguration: {}", deviceId, config.getIp());
                return config.getIp();
            }
        }

        SmartDevice stored = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, deviceId)
                .orElse(null);
        if (stored != null && stored.getIpAddress() != null && !stored.getIpAddress().isBlank()) {
            deviceIpCache.put(deviceId, stored.getIpAddress());
            TapoAuthProtocol storedProtocol = readAuthProtocol(stored.getMetadata());
            if (storedProtocol != null && storedProtocol != TapoAuthProtocol.UNKNOWN) {
                workingProtocolCache.put(deviceId, storedProtocol);
            }
            log.info("IP fuer {} aus Datenbank: {}", deviceId, stored.getIpAddress());
            return stored.getIpAddress();
        }

        // Auto-discover: try local UDP broadcast to find the device
        log.info("Keine IP fuer {} bekannt, starte automatische lokale Suche...", deviceId);
        try {
            List<TapoDiscoveryDevice> localDevices = discoverLocalDevices();
            String discovered = deviceIpCache.get(deviceId);
            if (discovered != null) {
                log.info("IP fuer {} per Auto-Discovery gefunden: {}", deviceId, discovered);
                return discovered;
            }
            log.info("Auto-Discovery fand {} Geraete, aber keines mit ID {}", localDevices.size(), deviceId);
        } catch (Exception ex) {
            log.debug("Auto-Discovery fehlgeschlagen: {}", ex.getMessage());
        }
        return null;
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.debug("Geraete-Metadata nicht lesbar: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private TapoAuthProtocol readAuthProtocol(String metadataJson) {
        Object value = readMetadata(metadataJson).get("authProtocol");
        if (value instanceof String name) {
            try {
                return TapoAuthProtocol.valueOf(name);
            } catch (IllegalArgumentException ex) {
                log.debug("Unbekanntes authProtocol in Metadata: {}", name);
            }
        }
        return null;
    }

    private TapoAuthProtocol resolveProtocol(String deviceId, TapoAuthProtocol requested) {
        if (requested != null && requested != TapoAuthProtocol.UNKNOWN) {
            return requested;
        }
        TapoAuthProtocol cached = workingProtocolCache.get(deviceId);
        if (cached != null && cached != TapoAuthProtocol.UNKNOWN) {
            return cached;
        }
        return TapoAuthProtocol.AES;
    }

    private TapoLocalDeviceConnection getOrCreateLocalConnection(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        String cacheKey = deviceId + ":" + protocol.name();
        return localConnectionCache.computeIfAbsent(cacheKey, id -> {
            log.debug("Erstelle lokale Verbindung fuer {} ({}, {})", deviceId, ipAddress, protocol);
            return tapoDeviceFactory.create(protocol, ipAddress, tapoProperties.getEmail(), tapoProperties.getPassword());
        });
    }

    @FunctionalInterface
    private interface LocalDeviceAction {
        JsonNode execute(TapoLocalDeviceConnection connection);
    }
}
