package com.household.manager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.dto.SmartDeviceUpdateRequest;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.SmartDeviceEntityMapper;
import com.household.manager.kasa.KasaDiscoveryService;
import com.household.manager.kasa.KasaService;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.meross.dto.MerossPlugResponse;
import com.household.manager.meross.service.MerossDeviceService;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
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
                case KASA -> kasaService.turnOn(device.getIpAddress());
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
                case KASA -> kasaService.turnOff(device.getIpAddress());
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
     *
     * @param id the device's database ID
     * @param ip the IPv4 address to probe and persist
     * @return the updated device
     * @throws IllegalArgumentException if no device exists with this ID, or it is not a Tapo device
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
        device.setMetadata(serializeMetadata(metadata));

        SmartDevice saved = smartDeviceRepository.save(device);
        reportEntityState(saved);
        log.info("Successfully set Tapo device address: {} -> {}", saved.getDeviceName(), ip);
        return toResponse(saved);
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

        device.setDeviceName(dto.getAlias() != null ? dto.getAlias() : "Kasa Device");
        device.setModel(dto.getModel());
        device.setIpAddress(dto.getIp());
        device.setOnline(true);  // If discovered, it's online
        device.setPoweredOn(dto.isRelayState());
        device.setCapabilities("SWITCH");

        // Store additional metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceId", dto.getDeviceId());
        device.setMetadata(serializeMetadata(metadata));

        return smartDeviceRepository.save(device);
    }

    private void refreshKasaDeviceState(SmartDevice device) {
        try {
            KasaStatusDto status = kasaService.getStatus(device.getIpAddress());
            device.setOnline(true);
            device.setPoweredOn(status.relayState());
            device.setDeviceName(status.alias() != null ? status.alias() : device.getDeviceName());
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
     */
    List<SmartDevice> scanTapoDevices() {
        List<TapoCloudDevice> discovered = tapoDeviceService.discoverCloudDevices();
        log.info("Discovered {} Tapo cloud devices", discovered.size());

        Map<String, TapoDiscoveryDevice> localDeviceMap = discoverLocalTapoDevices();
        Set<String> cloudDeviceIds = discovered.stream()
                .map(TapoCloudDevice::deviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<SmartDevice> result = new ArrayList<>(discovered.stream()
                .map(cloudDevice -> upsertTapoDevice(cloudDevice, localDeviceMap.get(cloudDevice.deviceId())))
                .collect(Collectors.toList()));

        for (Map.Entry<String, TapoDiscoveryDevice> entry : localDeviceMap.entrySet()) {
            if (cloudDeviceIds.contains(entry.getKey())) {
                continue; // already merged into its cloud counterpart above
            }
            try {
                result.add(upsertLocalOnlyTapoDevice(entry.getValue()));
            } catch (Exception ex) {
                // A single device's persistence failing (e.g. a DB hiccup) must not abort the
                // scan for the rest. A failed *handshake* is handled inside
                // upsertLocalOnlyTapoDevice itself and never reaches this catch.
                log.warn("Failed to adopt local-only Tapo device {} ({}): {}",
                        entry.getKey(), entry.getValue().ipAddress(), ex.getMessage());
            }
        }

        return result;
    }

    private Map<String, TapoDiscoveryDevice> discoverLocalTapoDevices() {
        try {
            List<TapoDiscoveryDevice> localDevices = tapoDeviceService.discoverLocalDevices();
            log.info("Discovered {} Tapo local devices", localDevices.size());
            return localDevices.stream()
                    .filter(d -> d.deviceId() != null)
                    .collect(Collectors.toMap(TapoDiscoveryDevice::deviceId, Function.identity(), (a, b) -> a));
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
        // nothing about a previously stored authProtocol. Read the old value before it is
        // overwritten below, so a manually-set (or previously discovered) protocol survives a
        // scan round in which local discovery finds nothing.
        TapoAuthProtocol previouslyKnownProtocol = extractAuthProtocol(device);
        Map<String, Object> metadata = tapoDeviceService.buildMetadata(dto);
        if (localDevice != null) {
            device.setIpAddress(localDevice.ipAddress());
            metadata.put("authProtocol", localDevice.authProtocol().name());
            log.debug("Tapo device {} found locally at {}", externalId, localDevice.ipAddress());
        } else if (previouslyKnownProtocol != null) {
            metadata.put("authProtocol", previouslyKnownProtocol.name());
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
     * Adopts a Tapo device that local discovery found but which is registered in no TP-Link
     * cloud account reachable with the configured credentials (e.g. it was set up under a
     * different household member's account). Without this, {@link #scanTapoDevices()} used to
     * silently drop it, since it only ever iterated the cloud device list.
     * <p>
     * The device is always created/kept — a visible-but-offline record beats a device that is
     * simply missing. A failed live probe (wrong/foreign account credentials, device gone
     * offline between UDP discovery and this call) marks it offline with a plain-German hint in
     * its metadata instead of throwing, so one bad device cannot abort the scan for the rest of
     * {@link #scanTapoDevices()}.
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
        } catch (Exception ex) {
            device.setOnline(false);
            metadata.put("localDiscoveryError",
                    "Im lokalen Netzwerk gefunden, aber die Anmeldung ist fehlgeschlagen "
                            + "(moeglicherweise ein anderes TP-Link-Konto). Letzter Fehler: " + ex.getMessage());
            log.warn("Local-only Tapo device {} ({}) failed the live handshake: {}",
                    externalId, localDevice.ipAddress(), ex.getMessage());
        }

        device.setMetadata(serializeMetadata(metadata));
        return smartDeviceRepository.save(device);
    }

    private void refreshTapoDeviceState(SmartDevice device) {
        try {
            String ip = device.getIpAddress();
            TapoAuthProtocol protocol = extractAuthProtocol(device);
            TapoDeviceState status = tapoDeviceService.getStatus(device.getExternalDeviceId(), ip, protocol);
            device.setOnline(status.online());
            device.setPoweredOn(status.poweredOn());
            if (status.nickname() != null && !status.nickname().isBlank()) {
                device.setDeviceName(status.nickname());
            }
            if (status.model() != null && !status.model().isBlank()) {
                device.setModel(status.model());
            }
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
                .metadata(deserializeMetadata(entity.getMetadata()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private List<String> parseCapabilities(String capabilities) {
        if (capabilities == null || capabilities.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(capabilities.split(","));
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
