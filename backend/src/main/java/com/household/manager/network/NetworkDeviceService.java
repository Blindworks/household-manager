package com.household.manager.network;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.NetworkDevice;
import com.household.manager.repository.NetworkDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pflegt die Stammdaten der ueberwachten LAN-Geraete (Geraeteliste des
 * Netzwerk-Monitorings). Status/Historie sind nicht Teil dieses Services.
 */
@Service
@RequiredArgsConstructor
public class NetworkDeviceService {

    private static final int MIN_TCP_PORT = 1;
    private static final int MAX_TCP_PORT = 65535;

    private final NetworkDeviceRepository repository;
    private final NetworkDeviceStatusMonitor monitor;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<NetworkDtos.DeviceAdminResponse> list() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(NetworkDtos.DeviceAdminResponse::from)
                .toList();
    }

    @Transactional
    public NetworkDtos.DeviceAdminResponse create(NetworkDtos.DeviceRequest request) {
        validate(request);
        NetworkDevice device = NetworkDevice.builder()
                .name(request.name().trim())
                .host(request.host().trim())
                .tcpPort(request.tcpPort())
                .sortOrder(sortOrderOrDefault(request))
                .active(activeOrDefault(request))
                .build();
        NetworkDevice saved = repository.save(device);
        auditService.record("network.device.create", auditDetail(saved));
        return NetworkDtos.DeviceAdminResponse.from(saved);
    }

    @Transactional
    public NetworkDtos.DeviceAdminResponse update(Long id, NetworkDtos.DeviceRequest request) {
        validate(request);
        NetworkDevice device = findOrThrow(id);
        device.setName(request.name().trim());
        device.setHost(request.host().trim());
        device.setTcpPort(request.tcpPort());
        device.setSortOrder(sortOrderOrDefault(request));
        device.setActive(activeOrDefault(request));
        NetworkDevice saved = repository.save(device);
        auditService.record("network.device.update", auditDetail(saved));
        return NetworkDtos.DeviceAdminResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        NetworkDevice device = findOrThrow(id);
        repository.delete(device);
        monitor.remove(id);
        auditService.record("network.device.delete", auditDetail(device));
    }

    private NetworkDevice findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("NetworkDevice", "id", id));
    }

    private String auditDetail(NetworkDevice device) {
        return "%s (%s)".formatted(device.getName(), device.getHost());
    }

    /** Fehlendes Feld heisst "aktiv" — wie der Default in Entity und Spalte (Muster Kalender-Kategorien). */
    private boolean activeOrDefault(NetworkDtos.DeviceRequest request) {
        return request.active() == null || request.active();
    }

    private int sortOrderOrDefault(NetworkDtos.DeviceRequest request) {
        return request.sortOrder() == null ? 0 : request.sortOrder();
    }

    private void validate(NetworkDtos.DeviceRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Der Name darf nicht leer sein.");
        }
        if (request.host() == null || request.host().isBlank()) {
            throw new IllegalArgumentException("Der Host darf nicht leer sein.");
        }
        Integer tcpPort = request.tcpPort();
        if (tcpPort != null && (tcpPort < MIN_TCP_PORT || tcpPort > MAX_TCP_PORT)) {
            throw new IllegalArgumentException(
                    "Der Port muss zwischen %d und %d liegen.".formatted(MIN_TCP_PORT, MAX_TCP_PORT));
        }
    }
}
