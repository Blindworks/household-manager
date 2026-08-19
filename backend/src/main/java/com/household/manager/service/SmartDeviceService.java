package com.household.manager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.LightStateRequest;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.dto.SmartDeviceUpdateRequest;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.SmartDeviceEntityMapper;
import com.household.manager.kasa.KasaDiscoveryService;
import com.household.manager.kasa.KasaLightCommandResult;
import com.household.manager.kasa.KasaService;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.meross.dto.MerossPlugResponse;
import com.household.manager.meross.service.MerossDeviceService;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.smartdevice.LightState;
import com.household.manager.tapo.TapoAddressProbeResult;
import com.household.manager.tapo.TapoAuthProtocol;
import com.household.manager.tapo.TapoCloudDevice;
import com.household.manager.tapo.TapoDeviceService;
import com.household.manager.tapo.TapoDeviceState;
import com.household.manager.tapo.TapoDiscoveryDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for managing smart home devices across multiple platforms.
 * <p>
 * Provides unified device discovery, persistence, and control for Kasa, Tapo, and Meross devices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmartDeviceService {

    private final SmartDeviceRepository smartDeviceRepository;
    private final KasaService kasaService;
    private final KasaDiscoveryService kasaDiscoveryService;
    private final MerossDeviceService merossDeviceService;
    private final TapoDeviceService tapoDeviceService;
    private final ObjectMapper objectMapper;
    private final SmartDeviceEntityMapper smartDeviceEntityMapper;
    private final EntityStateService entityStateService;
    private final AuditService auditService;

    /** Fallback colour-temperature range (Kelvin) when a device never reported its own. */
    private static final int DEFAULT_COLOR_TEMP_MIN = 2500;
    private static final int DEFAULT_COLOR_TEMP_MAX = 6500;

    /**
     * Get all smart devices from the database.
     *
     * @return list of all devices sorted by type and name
     */
    @Transactional(readOnly = true)
    public List<SmartDeviceResponse> getAllDevices() {
        log.debug("Retrieving all smart devices");
        List<SmartDevice> devices = smartDeviceRepository.findAllByOrderByDeviceTypeAscDeviceNameAsc();
        return devices.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific smart device by ID.
     *
     * @param id the device ID
     * @return the device response
     * @throws IllegalArgumentException if device not found
     */
    @Transactional(readOnly = true)
    public SmartDeviceResponse getDeviceById(Long id) {
        log.debug("Retrieving smart device with ID: {}", id);
        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));
        return toResponse(device);
    }

    /**
     * Scan for devices and persist them to the database.
     * <p>
     * Discovers devices of the specified type and creates or updates database records.
     *
     * @param deviceType the type of devices to scan for
     * @return list of discovered and persisted devices
     */
    @Transactional
    public List<SmartDeviceResponse> scanAndPersistDevices(DeviceType deviceType) {
        log.info("Scanning for {} devices", deviceType);

        List<SmartDevice> persistedDevices = switch (deviceType) {
            case KASA -> scanKasaDevices();
            case TAPO -> scanTapoDevices();
            case MEROSS -> scanMerossDevices();
        };

        persistedDevices.forEach(this::reportEntityState);

        log.info("Scanned and persisted {} {} devices", persistedDevices.size(), deviceType);
        return persistedDevices.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Update a smart device's name and metadata.
     *
     * @param id the device ID
     * @param request the update request
     * @return updated device response
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public SmartDeviceResponse updateDevice(Long id, SmartDeviceUpdateRequest request) {
        log.info("Updating smart device with ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        device.setDeviceName(request.getDeviceName());
        if (request.getMetadata() != null) {
            device.setMetadata(serializeMetadata(request.getMetadata()));
        }

        SmartDevice updated = smartDeviceRepository.save(device);
        reportEntityState(updated);
        log.info("Successfully updated device: {}", updated.getDeviceName());
        return toResponse(updated);
    }

    /**
     * Delete a smart device from the database.
     *
     * @param id the device ID
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public void deleteDevice(Long id) {
        log.info("Deleting smart device with ID: {}", id);
        if (!smartDeviceRepository.existsById(id)) {
            throw new IllegalArgumentException("Device not found with ID: " + id);
        }
        smartDeviceRepository.deleteById(id);
        log.info("Successfully deleted device with ID: {}", id);
    }

    /**
     * Refresh a device's state by fetching current status from the physical device.
     *
     * @param id the device ID
     * @return updated device response
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public SmartDeviceResponse refreshDeviceState(Long id) {
        log.info("Refreshing device state for ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        try {
            switch (device.getDeviceType()) {
                case KASA -> refreshKasaDeviceState(device);
                case TAPO -> refreshTapoDeviceState(device);
                case MEROSS -> refreshMerossDeviceState(device);
            }

            SmartDevice updated = smartDeviceRepository.save(device);
            reportEntityState(updated);
            log.info("Successfully refreshed device state for: {}", device.getDeviceName());
            return toResponse(updated);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to refresh device state for ID {}: {}", id, ex.getMessage());
            throw new RuntimeException("Failed to refresh device state: " + ex.getMessage(), ex);
        }
    }

    /**
     * Turn on a smart device.
     *
     * @param id the device ID
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public void turnOn(Long id) {
        log.info("Turning on device with ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        try {
            switch (device.getDeviceType()) {
                case KASA -> kasaService.turnOn(device.getIpAddress(), isKasaBulb(device));
                case TAPO -> {
                    String tapoIp = device.getIpAddress();
                    TapoAuthProtocol tapoProto = extractAuthProtocol(device);
                    tapoDeviceService.turnOn(device.getExternalDeviceId(), tapoIp, tapoProto);
                }
                case MEROSS -> merossDeviceService.turnOn(device.getExternalDeviceId());
            }

            device.setPoweredOn(true);
            smartDeviceRepository.save(device);
            reportEntityState(device);
            log.info("Successfully turned on device: {}", device.getDeviceName());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to turn on device ID {}: {}", id, ex.getMessage());
            throw new RuntimeException("Failed to turn on device: " + ex.getMessage(), ex);
        }
    }

    /**
     * Turn off a smart device.
     *
     * @param id the device ID
     * @throws IllegalArgumentException if device not found
     */
    @Transactional
    public void turnOff(Long id) {
        log.info("Turning off device with ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        try {
            switch (device.getDeviceType()) {
                case KASA -> kasaService.turnOff(device.getIpAddress(), isKasaBulb(device));
                case TAPO -> {
                    String tapoIp = device.getIpAddress();
                    TapoAuthProtocol tapoProto = extractAuthProtocol(device);
                    tapoDeviceService.turnOff(device.getExternalDeviceId(), tapoIp, tapoProto);
                }
                case MEROSS -> merossDeviceService.turnOff(device.getExternalDeviceId());
            }

            device.setPoweredOn(false);
            smartDeviceRepository.save(device);
            reportEntityState(device);
            log.info("Successfully turned off device: {}", device.getDeviceName());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to turn off device ID {}: {}", id, ex.getMessage());
            throw new RuntimeException("Failed to turn off device: " + ex.getMessage(), ex);
        }
    }

    /**
     * Manually add (or update) a Kasa device by its IP address.
     * <p>
     * Bypasses UDP broadcast discovery entirely by sending a single unicast probe to the
     * given IP, so it also works in environments where broadcast is blocked (e.g. the
     * production backend running inside a Docker bridge network) but a direct TCP
     * connection to the device works. Uses the same persist logic as
     * {@link #scanKasaDevices()}, so the resulting row is indistinguishable in the database
     * from a discovered one: identified by the stable hardware {@code deviceId}, created or
     * updated accordingly, and followed by the same entity-state report.
     *
     * @param ip the device's IP address to probe
     * @return the persisted device
     * @throws com.household.manager.kasa.exception.KasaCommunicationException if the device is unreachable or answers unexpectedly
     */
    @Transactional
    public SmartDeviceResponse addKasaDeviceByIp(String ip) {
        log.info("Adding Kasa device manually by IP: {}", ip);

        KasaDiscoveryDto dto = kasaService.probe(ip);
        SmartDevice device = upsertKasaDevice(dto);
        reportEntityState(device);

        log.info("Successfully added/updated Kasa device: {}", device.getDeviceName());
        return toResponse(device);
    }

    /**
     * Manually set (or correct) a Tapo device's IP address by probing it directly.
     * <p>
     * Counterpart to {@link #addKasaDeviceByIp(String)} for Tapo: unlike Kasa this does not
     * create a brand-new device (Tapo devices are identified by their cloud {@code deviceId},
     * which only a cloud/local discovery scan can establish), but corrects the address of an
     * already-known device whose local UDP discovery cannot reach it — e.g. the production
     * backend running inside a Docker bridge network, which never sees any Tapo device via
     * broadcast even though a direct KLAP/AES connection to it works fine.
     * <p>
     * The probe runs (and must succeed) before anything is persisted: a wrong IP must fail
     * loudly with a {@link TapoException} rather than being stored and silently leaving the
     * device offline.
     * <p>
     * <b>Identity check:</b> a successful probe alone is not proof the IP belongs to the device
     * being edited — with nine near-identical Tapo devices on one LAN, a mistyped-but-still-valid
     * IP would otherwise probe a completely different physical device and overwrite this row with
     * its name/model/capabilities/power state, leaving two DB rows pointing at one device (and a
     * later toggle of one row switching the other). The probe response's own {@code device_id} is
     * therefore compared against {@link SmartDevice#getExternalDeviceId()} before anything is
     * written, exactly like {@link #addKasaDeviceByIp(String)} is structurally immune to this by
     * always keying off the probed device's own id rather than trusting the caller's IP blindly.
     *
     * @param id the device's database ID
     * @param ip the IPv4 address to probe and persist
     * @return the updated device
     * @throws IllegalArgumentException if no device exists with this ID, it is not a Tapo device,
     *                                   or the device answering at {@code ip} is not the one being
     *                                   edited (device-id mismatch)
     * @throws TapoException if the device does not answer at the given IP
     */
    @Transactional
    public SmartDeviceResponse setTapoDeviceAddress(Long id, String ip) {
        log.info("Setting Tapo device address manually: id={}, ip={}", id, ip);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));
        if (device.getDeviceType() != DeviceType.TAPO) {
            throw new IllegalArgumentException(
                    "Geraet mit ID " + id + " ist kein Tapo-Geraet (Typ: " + device.getDeviceType() + ").");
        }

        // Probe first: a failure must not leave a half-written record behind. Only on success
        // do we touch the entity, so a wrong IP fails loudly (502) with nothing persisted.
        TapoAddressProbeResult probe = tapoDeviceService.probeAddress(ip);
        TapoDeviceState state = probe.state();

        // Identity check BEFORE any mutation: a probe that succeeds against the wrong physical
        // device is worse than one that fails outright, since it would silently corrupt this row.
        if (probe.deviceId() == null || probe.deviceId().isBlank()
                || !probe.deviceId().equalsIgnoreCase(device.getExternalDeviceId())) {
            throw new IllegalArgumentException(
                    "Das Geraet unter " + ip + " meldet die Geraete-ID "
                            + (probe.deviceId() == null || probe.deviceId().isBlank() ? "(keine)" : probe.deviceId())
                            + ", bearbeitet wird aber Geraet " + device.getExternalDeviceId()
                            + ". Adresse wurde nicht gespeichert - vermutlich eine Verwechslung mit einem anderen Geraet.");
        }

        device.setIpAddress(ip);
        device.setOnline(true);
        device.setPoweredOn(state.poweredOn());
        device.setCapabilities(state.capabilities());
        if (state.nickname() != null && !state.nickname().isBlank()) {
            device.setDeviceName(state.nickname());
        }
        if (state.model() != null && !state.model().isBlank()) {
            device.setModel(state.model());
        }

        Map<String, Object> metadata = new HashMap<>(deserializeMetadata(device.getMetadata()));
        metadata.put("authProtocol", probe.protocol().name());
        metadata.remove("localDiscoveryError");
        applyColorTempRange(metadata, state);
        applyCurrentLightState(metadata, state);
        device.setMetadata(serializeMetadata(metadata));

        SmartDevice saved = smartDeviceRepository.save(device);

        // The connection cache is keyed on deviceId:protocol and IGNORES the ipAddress argument
        // (TapoDeviceService.getOrCreateLocalConnection) - without this, a cached connection
        // object created against the OLD ip would keep being reused by turnOn/turnOff, silently
        // controlling whatever device now sits at the old address instead of this one.
        tapoDeviceService.clearLocalConnection(device.getExternalDeviceId());

        reportEntityState(saved);
        log.info("Successfully set Tapo device address: {} -> {}", saved.getDeviceName(), ip);
        return toResponse(saved);
    }

    /**
     * Sets brightness, colour and/or colour temperature on a Tapo light.
     * <p>
     * Validates the request before touching the device: a field the device doesn't report as a
     * capability (e.g. {@code hue} on a device without {@code COLOR}) is rejected with 400 rather
     * than silently ignored — silently ignoring it would make the caller believe it was applied.
     * Range checks use the device's own {@code color_temp_range} where it was captured on a
     * previous scan/refresh/probe (stored in metadata), falling back to 2500-6500 Kelvin otherwise.
     * <p>
     * Nothing is persisted unless the device actually accepts the change: a communication failure
     * propagates the {@link TapoException} untouched (mapped to 502 by the global exception
     * handler) and leaves the stored row exactly as it was.
     *
     * @param id      the device's database ID
     * @param request the light-state fields to set; all are optional but at least one is required
     * @return the device response with its state refreshed from the device
     * @throws IllegalArgumentException if the device doesn't exist, doesn't support light control
     *                                   (TAPO, or a KASA device whose stored {@code kasaBulb} flag
     *                                   is set — see {@link #isKasaBulb}; a Kasa wall dimmer speaks
     *                                   a different, unimplemented protocol and is rejected exactly
     *                                   like a Meross device), requests a capability the device
     *                                   doesn't report, sets no field at all, or sets a value
     *                                   outside its valid range
     * @throws TapoException if a Tapo device does not answer
     * @throws com.household.manager.kasa.exception.KasaCommunicationException if a Kasa device
     *                                   does not answer, or reports a non-zero {@code err_code}
     */
    @Transactional
    public SmartDeviceResponse setLightState(Long id, LightStateRequest request) {
        log.info("Setting light state for device ID: {}", id);

        SmartDevice device = smartDeviceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Device not found with ID: " + id));

        // Gating on DeviceType.KASA alone would let a Kasa WALL DIMMER (HS220/KS220/KP405) through:
        // those report is_dimmable:1 (see KasaCapabilityMapper) but have no light_state at all and
        // do not speak smartlife.iot.smartbulb.lightingservice - the isKasaBulb() structural flag
        // (persisted from light_state presence at scan/probe/refresh time, same source as the
        // KasaCapabilityMapper gate) is what actually tells a bulb apart from a dimmer switch.
        boolean supportsLightControl = device.getDeviceType() == DeviceType.TAPO
                || (device.getDeviceType() == DeviceType.KASA && isKasaBulb(device));
        if (!supportsLightControl) {
            throw new IllegalArgumentException(
                    "Geraet mit ID " + id + " unterstuetzt keine Lichtsteuerung (Typ: " + device.getDeviceType() + ").");
        }

        LightState lightState = validateLightStateRequest(device, request);
        boolean supportsColorTemp = parseCapabilities(device.getCapabilities()).contains("COLOR_TEMP");

        if (device.getDeviceType() == DeviceType.TAPO) {
            // Only reached once the device actually accepted the change: refresh and persist the
            // display-relevant fields (online/poweredOn/name/model/capabilities/light values) from
            // a fresh status read, the same fields a plain refresh keeps up to date.
            String ip = device.getIpAddress();
            TapoAuthProtocol protocol = extractAuthProtocol(device);
            tapoDeviceService.setLightState(device.getExternalDeviceId(), ip, protocol, lightState, supportsColorTemp);
            refreshTapoDeviceState(device);
        } else {
            // KasaService.setLightState already parses the device's OWN reported resulting state
            // out of the same response (see its javadoc for the measured evidence that err_code:0
            // does not prove the request's values landed) - persisting that directly, instead of
            // issuing a second getStatus() round trip the way the Tapo branch does, is both more
            // truthful (no assumption that the request was applied) and cheaper: Kasa devices
            // accept only one TCP connection at a time, so a redundant read right after a write
            // that already told us everything we need is pure waste.
            KasaLightCommandResult result = kasaService.setLightState(device.getIpAddress(), lightState, supportsColorTemp);
            device.setOnline(true);
            device.setPoweredOn(result.poweredOn());
            Map<String, Object> metadata = new HashMap<>(deserializeMetadata(device.getMetadata()));
            applyCurrentLightState(metadata, result.lightState());
            device.setMetadata(serializeMetadata(metadata));
        }

        SmartDevice saved = smartDeviceRepository.save(device);
        reportEntityState(saved);

        auditService.record("device.light.set", "deviceId=" + id + ", " + describeLightState(lightState));

        log.info("Successfully set light state for device: {}", saved.getDeviceName());
        return toResponse(saved);
    }

    private LightState validateLightStateRequest(SmartDevice device, LightStateRequest request) {
        Integer brightness = request.getBrightness();
        Integer hue = request.getHue();
        Integer saturation = request.getSaturation();
        Integer colorTemp = request.getColorTemp();

        if (brightness == null && hue == null && saturation == null && colorTemp == null) {
            throw new IllegalArgumentException(
                    "Es wurde kein Lichtwert angegeben (Helligkeit, Farbe oder Farbtemperatur).");
        }

        // Colour and colour-temperature are mutually exclusive modes on the device itself (see
        // TapoDeviceService.buildSetDeviceInfoParams). Without this check, a mixed request like
        // {hue, saturation, colorTemp} would silently take the colour branch, send color_temp:0
        // to the device (discarding the requested colorTemp), still return 200, and the audit
        // entry would claim the requested colorTemp was set even though it never reached the
        // device - exactly the silent-ignore this API forbids in the other direction (see the
        // capability check below). Reject loudly instead, structurally, before any device-specific
        // capability/range check even runs.
        if ((hue != null || saturation != null) && colorTemp != null) {
            throw new IllegalArgumentException(
                    "Farbe und Farbtemperatur schliessen sich aus - bitte nur eines von beiden setzen.");
        }

        List<String> capabilities = parseCapabilities(device.getCapabilities());

        if (brightness != null) {
            requireCapability(device, capabilities, "BRIGHTNESS");
            if (brightness < 1 || brightness > 100) {
                throw new IllegalArgumentException("Helligkeit muss zwischen 1 und 100 liegen.");
            }
        }
        if (hue != null || saturation != null) {
            requireCapability(device, capabilities, "COLOR");
            if (hue != null && (hue < 0 || hue > 360)) {
                throw new IllegalArgumentException("Farbton (hue) muss zwischen 0 und 360 liegen.");
            }
            if (saturation != null && (saturation < 0 || saturation > 100)) {
                throw new IllegalArgumentException("Saettigung muss zwischen 0 und 100 liegen.");
            }
        }
        if (colorTemp != null) {
            requireCapability(device, capabilities, "COLOR_TEMP");
            int[] range = resolveColorTempRange(device);
            if (colorTemp < range[0] || colorTemp > range[1]) {
                throw new IllegalArgumentException(
                        "Farbtemperatur muss zwischen " + range[0] + " und " + range[1] + " Kelvin liegen.");
            }
        }

        return new LightState(brightness, hue, saturation, colorTemp);
    }

    private void requireCapability(SmartDevice device, List<String> capabilities, String capability) {
        if (!capabilities.contains(capability)) {
            throw new IllegalArgumentException(
                    "Geraet " + device.getDeviceName() + " meldet die Faehigkeit " + capability + " nicht.");
        }
    }

    /**
     * Reads the device's own {@code colorTempRangeMin}/{@code colorTempRangeMax} metadata
     * (captured from Tapo's {@code color_temp_range} on a previous scan/refresh/address-set, see
     * {@link #applyColorTempRange}), falling back to a generic default range if the device never
     * reported one — e.g. it hasn't been probed since this field was added.
     * <p>
     * <b>Kasa bulbs always take this fallback</b>: no equivalent range field has been found in the
     * Kasa protocol (unlike Tapo's {@code color_temp_range}), so {@code colorTempRangeMin}/{@code
     * colorTempRangeMax} are never written for a Kasa device and a Kasa colour-temperature request
     * is always validated against 2500-6500 Kelvin, regardless of the bulb's actual supported range.
     */
    private int[] resolveColorTempRange(SmartDevice device) {
        Map<String, Object> metadata = deserializeMetadata(device.getMetadata());
        Integer min = asInteger(metadata.get("colorTempRangeMin"));
        Integer max = asInteger(metadata.get("colorTempRangeMax"));
        if (min != null && max != null && min < max) {
            return new int[]{min, max};
        }
        return new int[]{DEFAULT_COLOR_TEMP_MIN, DEFAULT_COLOR_TEMP_MAX};
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    /**
     * Stores the device's self-reported colour-temperature range in metadata so future
     * {@link #setLightState} calls can validate against it without re-probing the device. Only
     * overwrites the stored range when this state actually carries one (a live probe result);
     * a state without one (e.g. a failed live probe whose caller kept the previous value) leaves
     * whatever was already in the metadata map untouched.
     */
    private void applyColorTempRange(Map<String, Object> metadata, TapoDeviceState state) {
        if (state.colorTempMin() != null && state.colorTempMax() != null) {
            metadata.put("colorTempRangeMin", state.colorTempMin());
            metadata.put("colorTempRangeMax", state.colorTempMax());
        }
    }

    /**
     * Stores the device's current brightness/hue/saturation/colorTemp in metadata so
     * {@link #toResponse} can seed the frontend's light controls with the bulb's actual state
     * instead of an invented default (e.g. a slider starting at 100% on a bulb sitting at 50%).
     * Same pattern as {@link #applyColorTempRange}: only the fields this state actually reports
     * are written, so a device with no light capabilities at all (or a round where the live probe
     * reported nothing) leaves whatever was previously stored untouched rather than clobbering it.
     */
    private void applyCurrentLightState(Map<String, Object> metadata, TapoDeviceState state) {
        applyCurrentLightState(metadata, state.currentLightState());
    }

    /**
     * Device-type-agnostic core of {@link #applyCurrentLightState(Map, TapoDeviceState)}, reused
     * directly by the Kasa upsert/refresh paths (which have no {@code TapoDeviceState} wrapper of
     * their own, just the four raw fields) so both platforms write the SAME metadata keys and
     * {@link #toResponse} needs no per-platform branching to read them back.
     */
    private void applyCurrentLightState(Map<String, Object> metadata, LightState light) {
        if (light == null) {
            return;
        }
        if (light.brightness() != null) {
            metadata.put("lightBrightness", light.brightness());
        }
        if (light.hue() != null) {
            metadata.put("lightHue", light.hue());
        }
        if (light.saturation() != null) {
            metadata.put("lightSaturation", light.saturation());
        }
        if (light.colorTemp() != null) {
            metadata.put("lightColorTemp", light.colorTemp());
        }
    }

    private String describeLightState(LightState lightState) {
        StringBuilder detail = new StringBuilder();
        if (lightState.brightness() != null) {
            detail.append("brightness=").append(lightState.brightness()).append(' ');
        }
        if (lightState.hue() != null) {
            detail.append("hue=").append(lightState.hue()).append(' ');
        }
        if (lightState.saturation() != null) {
            detail.append("saturation=").append(lightState.saturation()).append(' ');
        }
        if (lightState.colorTemp() != null) {
            detail.append("colorTemp=").append(lightState.colorTemp()).append(' ');
        }
        return detail.toString().trim();
    }

    // ==================== Kasa Device Methods ====================

    private List<SmartDevice> scanKasaDevices() {
        List<KasaDiscoveryDto> discovered = kasaDiscoveryService.discover();
        log.info("Discovered {} Kasa devices", discovered.size());

        return discovered.stream()
                .map(this::upsertKasaDevice)
                .collect(Collectors.toList());
    }

    private SmartDevice upsertKasaDevice(KasaDiscoveryDto dto) {
        // The hardware deviceId is stable, the IP is not: DHCP can reassign it, which used to
        // create duplicate records and leave stale entries stuck "offline". Identify by deviceId
        // and keep the IP purely as the (refreshable) communication address.
        String deviceId = dto.getDeviceId();
        String externalId = (deviceId != null && !deviceId.isBlank()) ? deviceId : dto.getIp();
        Optional<SmartDevice> existing = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, externalId);

        SmartDevice device;
        if (existing.isPresent()) {
            device = existing.get();
            log.debug("Updating existing Kasa device: {}", dto.getAlias());
        } else {
            device = new SmartDevice();
            device.setDeviceType(DeviceType.KASA);
            device.setExternalDeviceId(externalId);
            log.debug("Creating new Kasa device: {}", dto.getAlias());
        }

        device.setDeviceName(
                Optional.ofNullable(dto.getAlias()).filter(name -> !name.isBlank()).orElse("Kasa Device"));
        device.setModel(dto.getModel());
        device.setIpAddress(dto.getIp());
        device.setOnline(true);  // If discovered, it's online
        device.setPoweredOn(dto.isRelayState());
        // Derived from the device's own is_dimmable/is_color/is_variable_color_temp flags
        // (KasaCapabilityMapper) rather than hardcoded - dto is always fresh here (discover()/
        // probe() only ever hand this a just-received sysinfo), so there is no "keep the old value
        // on a failed probe" concern the way there is on the Tapo scan path.
        device.setCapabilities(dto.getCapabilities() != null ? dto.getCapabilities() : "SWITCH");

        // Store additional metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceId", dto.getDeviceId());
        metadata.put("kasaBulb", dto.isBulb());
        applyCurrentLightState(metadata, new LightState(dto.getBrightness(), dto.getHue(), dto.getSaturation(), dto.getColorTemp()));
        device.setMetadata(serializeMetadata(metadata));

        return smartDeviceRepository.save(device);
    }

    /** Reads the {@code kasaBulb} flag captured at scan/probe/refresh time (see {@link #upsertKasaDevice}). */
    private boolean isKasaBulb(SmartDevice device) {
        return Boolean.TRUE.equals(deserializeMetadata(device.getMetadata()).get("kasaBulb"));
    }

    private void refreshKasaDeviceState(SmartDevice device) {
        try {
            KasaStatusDto status = kasaService.getStatus(device.getIpAddress());
            device.setOnline(true);
            device.setPoweredOn(status.relayState());
            // Same blank-check as upsertKasaDevice: KasaSysInfoMapper.trimAlias turns a
            // whitespace-only alias into "", and a plain != null check would happily persist that
            // empty string as the device's display name.
            if (status.alias() != null && !status.alias().isBlank()) {
                device.setDeviceName(status.alias());
            }
            device.setCapabilities(status.capabilities());

            Map<String, Object> metadata = new HashMap<>(deserializeMetadata(device.getMetadata()));
            metadata.put("kasaBulb", status.bulb());
            applyCurrentLightState(metadata, new LightState(status.brightness(), status.hue(), status.saturation(), status.colorTemp()));
            device.setMetadata(serializeMetadata(metadata));
        } catch (Exception ex) {
            log.warn("Kasa device {} ({}) appears offline: {}",
                    device.getExternalDeviceId(), device.getIpAddress(), ex.getMessage());
            device.setOnline(false);
        }
    }

    // ==================== Tapo Device Methods ====================

    /**
     * Package-private (not {@code private}) purely so unit tests can drive it directly with the
     * richer {@code List<SmartDevice>} return type — {@link #scanAndPersistDevices} only exposes
     * the DTO-mapped result.
     * <p>
     * Merges the cloud device list and the local discovery map by device ID instead of filtering
     * the cloud list: a device present in both is upserted once via {@link #upsertTapoDevice}; a
     * cloud-only device behaves exactly as before; a device found <em>only</em> locally (no TP-Link
     * cloud account knows it) is adopted via {@link #upsertLocalOnlyTapoDevice} instead of being
     * silently dropped, which used to happen when this method started from the cloud list alone.
     * Both sides are matched via {@link #normalizeTapoDeviceId(String)} (case-insensitive) — the
     * cloud API and the device's own {@code get_device_info} are two independent, unverified
     * sources for what should be the same id, and a bare case difference must not create a
     * duplicate row for one physical device.
     * <p>
     * <b>Persistence failures are allowed to propagate</b> (not caught here): this method runs
     * inside the caller's {@code @Transactional} boundary, and {@link SmartDevice} uses an
     * IDENTITY primary key, so Hibernate flushes each {@code save()} immediately rather than at
     * commit. A constraint violation on ANY device therefore already marks the whole transaction
     * rollback-only the moment it happens — catching and logging it here would not save the other
     * devices in this scan (they get rolled back too at commit via
     * {@code UnexpectedRollbackException}), it would only make the log lie about which devices
     * survived. Letting it propagate is honest and matches {@link #upsertTapoDevice}, which has
     * never caught around its own {@code save()}. A failed <em>handshake</em> (the actually common
     * case for a local-only device) is a different, non-transactional failure mode and is already
     * isolated inside {@link #upsertLocalOnlyTapoDevice} before persistence is even attempted.
     */
    List<SmartDevice> scanTapoDevices() {
        List<TapoCloudDevice> discovered = tapoDeviceService.discoverCloudDevices();
        log.info("Discovered {} Tapo cloud devices", discovered.size());

        Map<String, TapoDiscoveryDevice> localDeviceMap = discoverLocalTapoDevices();
        Set<String> cloudDeviceIds = discovered.stream()
                .map(TapoCloudDevice::deviceId)
                .filter(Objects::nonNull)
                .map(SmartDeviceService::normalizeTapoDeviceId)
                .collect(Collectors.toSet());

        List<SmartDevice> result = new ArrayList<>(discovered.stream()
                .map(cloudDevice -> upsertTapoDevice(
                        cloudDevice, localDeviceMap.get(normalizeTapoDeviceId(cloudDevice.deviceId()))))
                .collect(Collectors.toList()));

        // IPs already claimed by a device the merge above matched via its cloud deviceId. Used
        // only to flag a suspicious case below - the merge itself already happened correctly.
        // Kept deliberately (not deleted) despite being unreachable under normal operation:
        // TapoDeviceService.discoverLocalDevices() already dedupes UDP-vs-static entries by IP
        // (the static-config loop skips any IP the UDP broadcast already found), so two DIFFERENT
        // localDeviceMap entries sharing one IP is not supposed to happen. The realistic trigger
        // is a misconfigured static tapo.devices entry whose configured deviceId is stale/wrong
        // while its IP now belongs to a different, correctly cloud-matched device (e.g. the
        // physical device at that address was swapped, or the static deviceId was copy-pasted
        // from another entry) - exactly the kind of silent-wrong-target mistake the identity check
        // in setTapoDeviceAddress guards against for the manual path. This warning is the scan
        // path's only signal for the equivalent misconfiguration; a future refactor that removes
        // it should replace it with something else, not drop the check silently.
        Set<String> ipsClaimedByMatchedDevices = discovered.stream()
                .map(TapoCloudDevice::deviceId)
                .filter(Objects::nonNull)
                .map(id -> localDeviceMap.get(normalizeTapoDeviceId(id)))
                .filter(Objects::nonNull)
                .map(TapoDiscoveryDevice::ipAddress)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (Map.Entry<String, TapoDiscoveryDevice> entry : localDeviceMap.entrySet()) {
            if (cloudDeviceIds.contains(entry.getKey())) {
                continue; // already merged into its cloud counterpart above
            }
            TapoDiscoveryDevice localOnly = entry.getValue();
            if (localOnly.ipAddress() != null && ipsClaimedByMatchedDevices.contains(localOnly.ipAddress())) {
                // The cloud <-> local id match is an unverified assumption (two independent
                // sources for "the same" id). This device's id didn't match anything in the
                // cloud list even after normalization, yet its IP is already claimed by a device
                // that DID match - most likely the same physical device under a genuinely
                // different id spelling, about to be adopted a second time as local-only.
                log.warn("Lokal gefundenes Tapo-Geraet {} ({}) hat dieselbe IP wie ein bereits per "
                                + "Cloud-Liste zugeordnetes Geraet — moeglicherweise dasselbe physische "
                                + "Geraet unter einer abweichenden deviceId.",
                        entry.getKey(), localOnly.ipAddress());
            }
            result.add(upsertLocalOnlyTapoDevice(localOnly));
        }

        return result;
    }

    /**
     * Case-insensitive normalization for matching a Tapo device's id across its two independent,
     * unverified sources (TP-Link cloud API vs. the device's own {@code get_device_info}) — see
     * {@link #scanTapoDevices()}.
     * <p>
     * This normalization only affects the in-memory merge decision (which {@code TapoCloudDevice}
     * pairs with which {@code TapoDiscoveryDevice}). The actual database row identity — whether
     * {@code findByDeviceTypeAndExternalDeviceId} treats two differently-cased spellings of the
     * same id as the same row and so avoids creating a duplicate — is decided by
     * {@link #upsertTapoDevice} passing the RAW, un-normalized {@code dto.deviceId()} to that
     * repository lookup. That "no duplicate row" guarantee therefore still depends on MariaDB's
     * case-insensitive default collation (e.g. {@code utf8mb4_general_ci}) for the
     * {@code external_device_id} column, not on this method.
     */
    private static String normalizeTapoDeviceId(String deviceId) {
        return deviceId == null ? null : deviceId.toUpperCase(Locale.ROOT);
    }

    private Map<String, TapoDiscoveryDevice> discoverLocalTapoDevices() {
        try {
            List<TapoDiscoveryDevice> localDevices = tapoDeviceService.discoverLocalDevices();
            log.info("Discovered {} Tapo local devices", localDevices.size());
            return localDevices.stream()
                    .filter(d -> d.deviceId() != null)
                    .collect(Collectors.toMap(d -> normalizeTapoDeviceId(d.deviceId()), Function.identity(), (a, b) -> a));
        } catch (Exception ex) {
            log.warn("Local Tapo discovery failed, continuing with cloud only: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private SmartDevice upsertTapoDevice(TapoCloudDevice dto, TapoDiscoveryDevice localDevice) {
        String externalId = dto.deviceId();
        Optional<SmartDevice> existing = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, externalId);

        SmartDevice device;
        if (existing.isPresent()) {
            device = existing.get();
            log.debug("Updating existing Tapo device: {}", dto.deviceId());
        } else {
            device = new SmartDevice();
            device.setDeviceType(DeviceType.TAPO);
            device.setExternalDeviceId(externalId);
            // Default for a brand-new device with no get_device_info response yet. A real
            // response (below) overwrites this with the derived value; a probe failure on an
            // EXISTING device must not fall back here, or a briefly offline bulb would lose its
            // capabilities and its controls would vanish from the UI.
            device.setCapabilities("SWITCH");
            log.debug("Creating new Tapo device: {}", dto.deviceId());
        }

        String decodedAlias = tapoDeviceService.decodeAlias(dto.alias());
        device.setDeviceName(
                Optional.ofNullable(decodedAlias)
                        .filter(name -> !name.isBlank())
                        .orElseGet(() -> Optional.ofNullable(dto.deviceName()).filter(name -> !name.isBlank()).orElse("Tapo Device"))
        );
        device.setModel(dto.model());
        // Cloud status field is unreliable for Tapo devices; only trust local reachability here.
        // A successful live probe below (independent of local discovery) still flips this to
        // true, e.g. for a device whose IP was set manually via setTapoDeviceAddress and which
        // local UDP discovery can no longer reach (production Docker bridge network).
        device.setOnline(localDevice != null);

        // buildMetadata() returns a FRESH map assembled purely from the cloud DTO — it knows
        // nothing about a previously stored authProtocol or colour-temp range. Read the old
        // values before they are overwritten below, so a manually-set (or previously discovered)
        // protocol/range survives a scan round in which local discovery finds nothing or the
        // live probe below fails.
        TapoAuthProtocol previouslyKnownProtocol = extractAuthProtocol(device);
        String previousIp = device.getIpAddress();
        Map<String, Object> priorMetadata = deserializeMetadata(device.getMetadata());
        Integer previousColorTempMin = asInteger(priorMetadata.get("colorTempRangeMin"));
        Integer previousColorTempMax = asInteger(priorMetadata.get("colorTempRangeMax"));

        Map<String, Object> metadata = tapoDeviceService.buildMetadata(dto);
        if (localDevice != null) {
            device.setIpAddress(localDevice.ipAddress());
            metadata.put("authProtocol", localDevice.authProtocol().name());
            log.debug("Tapo device {} found locally at {}", externalId, localDevice.ipAddress());
            if (previousIp != null && !previousIp.equals(localDevice.ipAddress())) {
                // TapoDeviceService.getOrCreateLocalConnection keys its connection cache on
                // deviceId:protocol only, NOT the ip - after a DHCP reshuffle a cached connection
                // object created against the OLD ip would otherwise keep being reused, silently
                // talking to whatever physical device now happens to sit at that old address (it
                // would authenticate happily if it's another bulb on the same Tapo account). Same
                // bug class setTapoDeviceAddress already guards against explicitly; a scan-discovered
                // IP change needs the identical guard.
                tapoDeviceService.clearLocalConnection(externalId);
                log.info("Tapo device {} IP-Wechsel erkannt ({} -> {}), verworfene Verbindung invalidiert",
                        externalId, previousIp, localDevice.ipAddress());
            }
        } else if (previouslyKnownProtocol != null) {
            metadata.put("authProtocol", previouslyKnownProtocol.name());
        }
        if (previousColorTempMin != null && previousColorTempMax != null) {
            metadata.put("colorTempRangeMin", previousColorTempMin);
            metadata.put("colorTempRangeMax", previousColorTempMax);
        }
        device.setMetadata(serializeMetadata(metadata));

        String ip = device.getIpAddress();
        TapoAuthProtocol protocol = extractAuthProtocol(device);
        try {
            TapoDeviceState state = tapoDeviceService.getStatus(externalId, ip, protocol);
            device.setOnline(state.online());
            device.setPoweredOn(state.poweredOn());
            device.setCapabilities(state.capabilities());
            if (state.nickname() != null && !state.nickname().isBlank()) {
                device.setDeviceName(state.nickname());
            }
            if (state.model() != null && !state.model().isBlank()) {
                device.setModel(state.model());
            }
            applyColorTempRange(metadata, state);
            applyCurrentLightState(metadata, state);
            device.setMetadata(serializeMetadata(metadata));
        } catch (Exception ex) {
            // Live probe failed: keep online (set above from local reachability), poweredOn
            // (last known-good value, either just persisted by local discovery or the
            // SmartDevice default) and capabilities (previously stored value, or the SWITCH
            // default for a brand-new device) untouched rather than clobbering them.
            log.debug("Skipping live Tapo state during scan for {}: {}", externalId, ex.getMessage());
        }

        return smartDeviceRepository.save(device);
    }

    /**
     * Adopts a Tapo device that local discovery found but which has no matching entry in the
     * cloud device list (by id, see {@link #normalizeTapoDeviceId(String)}) — e.g. it is
     * registered under a different household member's TP-Link account than the one configured
     * here. Without this, {@link #scanTapoDevices()} used to silently drop it, since it only
     * ever iterated the cloud device list.
     * <p>
     * The device is always created/kept — a visible-but-offline record beats a device that is
     * simply missing.
     * <p>
     * <b>Note on the live probe below:</b> reaching this method at all already required a
     * successful authenticated handshake — {@code localDevice} only exists because
     * {@code TapoDiscoveryService.discoverLocalDevices} performs a full {@code get_device_info}
     * call and silently drops anything that doesn't answer with the configured credentials. So
     * a "wrong account" is <em>not</em> a realistic failure mode for the getStatus() call below;
     * what IS realistic is the device becoming unreachable again between that earlier discovery
     * handshake and this follow-up probe (network hiccup, or the device only accepting one
     * concurrent session and being mid-handshake with something else) — a genuine TOCTOU race,
     * not an authentication problem. On such a failure the device is still persisted, marked
     * offline with a plain-German hint, and the scan continues for the rest.
     */
    private SmartDevice upsertLocalOnlyTapoDevice(TapoDiscoveryDevice localDevice) {
        String externalId = localDevice.deviceId();
        Optional<SmartDevice> existing = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, externalId);

        SmartDevice device;
        if (existing.isPresent()) {
            device = existing.get();
            log.debug("Updating existing local-only Tapo device: {}", externalId);
        } else {
            device = new SmartDevice();
            device.setDeviceType(DeviceType.TAPO);
            device.setExternalDeviceId(externalId);
            device.setCapabilities("SWITCH");
            log.debug("Adopting local-only Tapo device with no matching cloud account: {}", externalId);
        }

        device.setDeviceName(
                Optional.ofNullable(localDevice.nickname()).filter(name -> !name.isBlank()).orElse("Tapo Device"));
        device.setModel(localDevice.model());
        device.setIpAddress(localDevice.ipAddress());
        device.setPoweredOn(localDevice.deviceOn());

        Map<String, Object> metadata = new HashMap<>(deserializeMetadata(device.getMetadata()));
        metadata.put("authProtocol", localDevice.authProtocol().name());
        metadata.remove("localDiscoveryError");

        try {
            TapoDeviceState state = tapoDeviceService.getStatus(
                    externalId, localDevice.ipAddress(), localDevice.authProtocol());
            device.setOnline(true);
            device.setPoweredOn(state.poweredOn());
            device.setCapabilities(state.capabilities());
            if (state.nickname() != null && !state.nickname().isBlank()) {
                device.setDeviceName(state.nickname());
            }
            if (state.model() != null && !state.model().isBlank()) {
                device.setModel(state.model());
            }
            applyColorTempRange(metadata, state);
            applyCurrentLightState(metadata, state);
        } catch (Exception ex) {
            device.setOnline(false);
            metadata.put("localDiscoveryError",
                    "Im lokalen Netzwerk gefunden, ist aber beim erneuten Abfragen nicht mehr erreichbar "
                            + "(vermutlich voruebergehend). Letzter Fehler: " + ex.getMessage());
            log.warn("Local-only Tapo device {} ({}) was unreachable on the follow-up probe: {}",
                    externalId, localDevice.ipAddress(), ex.getMessage());
        }

        device.setMetadata(serializeMetadata(metadata));
        return smartDeviceRepository.save(device);
    }

    /**
     * Refreshes online/poweredOn/name/model from a live status read, and — same metadata
     * read/merge/write shape as {@link #setTapoDeviceAddress} — the colour-temp range and current
     * light values via {@link #applyColorTempRange}/{@link #applyCurrentLightState}. Without this,
     * a device that is only ever refreshed (never rescanned or address-set) would keep the
     * 2500-6500 Kelvin fallback range forever and never get its light-control seed values, even
     * though every refresh already fetches the full {@code get_device_info} response that carries
     * both.
     */
    private void refreshTapoDeviceState(SmartDevice device) {
        try {
            String ip = device.getIpAddress();
            TapoAuthProtocol protocol = extractAuthProtocol(device);
            TapoDeviceState status = tapoDeviceService.getStatus(device.getExternalDeviceId(), ip, protocol);
            device.setOnline(status.online());
            device.setPoweredOn(status.poweredOn());
            device.setCapabilities(status.capabilities());
            if (status.nickname() != null && !status.nickname().isBlank()) {
                device.setDeviceName(status.nickname());
            }
            if (status.model() != null && !status.model().isBlank()) {
                device.setModel(status.model());
            }

            Map<String, Object> metadata = new HashMap<>(deserializeMetadata(device.getMetadata()));
            applyColorTempRange(metadata, status);
            applyCurrentLightState(metadata, status);
            device.setMetadata(serializeMetadata(metadata));
        } catch (Exception ex) {
            log.warn("Tapo device {} appears offline: {}", device.getExternalDeviceId(), ex.getMessage());
            device.setOnline(false);
        }
    }

    // ==================== Meross Device Methods ====================

    private List<SmartDevice> scanMerossDevices() {
        List<MerossPlugResponse> discovered = merossDeviceService.discoverPlugs();
        log.info("Discovered {} Meross devices", discovered.size());

        return discovered.stream()
                .map(this::upsertMerossDevice)
                .collect(Collectors.toList());
    }

    private SmartDevice upsertMerossDevice(MerossPlugResponse dto) {
        String externalId = dto.deviceId();
        Optional<SmartDevice> existing = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(DeviceType.MEROSS, externalId);

        SmartDevice device;
        if (existing.isPresent()) {
            device = existing.get();
            log.debug("Updating existing Meross device: {}", dto.name());
        } else {
            device = new SmartDevice();
            device.setDeviceType(DeviceType.MEROSS);
            device.setExternalDeviceId(externalId);
            log.debug("Creating new Meross device: {}", dto.name());
        }

        device.setDeviceName(dto.name() != null ? dto.name() : "Meross Device");
        device.setOnline("online".equalsIgnoreCase(dto.onlineStatus()));
        device.setPoweredOn(dto.on());
        device.setCapabilities("SWITCH");

        // Store additional metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceType", dto.deviceType());
        metadata.put("onlineStatus", dto.onlineStatus());
        device.setMetadata(serializeMetadata(metadata));

        return smartDeviceRepository.save(device);
    }

    private void refreshMerossDeviceState(SmartDevice device) {
        try {
            MerossPlugResponse status = merossDeviceService.getStatus(device.getExternalDeviceId());
            device.setOnline("online".equalsIgnoreCase(status.onlineStatus()));
            device.setPoweredOn(status.on());
            device.setDeviceName(status.name() != null ? status.name() : device.getDeviceName());
        } catch (Exception ex) {
            log.warn("Meross device {} appears offline: {}", device.getExternalDeviceId(), ex.getMessage());
            device.setOnline(false);
        }
    }

    // ==================== Helper Methods ====================

    private SmartDeviceResponse toResponse(SmartDevice entity) {
        Map<String, Object> metadata = deserializeMetadata(entity.getMetadata());
        return SmartDeviceResponse.builder()
                .id(entity.getId())
                .deviceType(entity.getDeviceType().name())
                .externalDeviceId(entity.getExternalDeviceId())
                .deviceName(entity.getDeviceName())
                .model(entity.getModel())
                .ipAddress(entity.getIpAddress())
                .isOnline(entity.isOnline())
                .isPoweredOn(entity.isPoweredOn())
                .capabilities(parseCapabilities(entity.getCapabilities()))
                .confirmRequired(isConfirmRequired(entity))
                .metadata(metadata)
                .brightness(asInteger(metadata.get("lightBrightness")))
                .hue(asInteger(metadata.get("lightHue")))
                .saturation(asInteger(metadata.get("lightSaturation")))
                .colorTemp(asInteger(metadata.get("lightColorTemp")))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private List<String> parseCapabilities(String capabilities) {
        if (capabilities == null || capabilities.isBlank()) {
            return Collections.emptyList();
        }
        // .trim(): defensive, matches the metadata deserialization style elsewhere in this class —
        // a capabilities string ever written with ", " separators would otherwise fail every
        // capability check silently (List.contains("BRIGHTNESS") never matches " BRIGHTNESS").
        return Arrays.stream(capabilities.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    /**
     * Liest das Bestaetigungs-Flag aus der gespiegelten Switch-Entitaet. Die entityId wird mit
     * exakt derselben Konstruktion gebildet wie in {@link SmartDeviceEntityMapper#map} - beide
     * Stellen muessen dieselbe Id ergeben, sonst zeigt die Geraeteseite einen Schutz an, den es
     * an der Entitaet nicht gibt (oder umgekehrt). Ohne gespiegelte Entitaet gilt "kein Schutz".
     */
    private boolean isConfirmRequired(SmartDevice device) {
        try {
            String entityId = EntityIds.build(EntityDomain.SWITCH,
                    EntitySource.valueOf(device.getDeviceType().name()),
                    device.getExternalDeviceId(), null);
            return entityStateService.getByEntityId(entityId)
                    .map(EntityState::isConfirmRequired)
                    .orElse(false);
        } catch (Exception ex) {
            log.debug("Bestaetigungs-Flag fuer {} nicht ermittelbar: {}",
                    device.getExternalDeviceId(), ex.getMessage());
            return false;
        }
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize metadata", ex);
            return null;
        }
    }

    private TapoAuthProtocol extractAuthProtocol(SmartDevice device) {
        Map<String, Object> metadata = deserializeMetadata(device.getMetadata());
        Object protocol = metadata.get("authProtocol");
        if (protocol instanceof String protocolStr) {
            try {
                return TapoAuthProtocol.valueOf(protocolStr);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return null;
    }

    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            log.error("Failed to deserialize metadata", ex);
            return Collections.emptyMap();
        }
    }

    private void reportEntityState(SmartDevice device) {
        try {
            entityStateService.reportState(smartDeviceEntityMapper.map(device));
        } catch (Exception ex) {
            log.warn("Failed to report entity state for device {}: {}",
                    device.getExternalDeviceId(), ex.getMessage());
        }
    }
}
