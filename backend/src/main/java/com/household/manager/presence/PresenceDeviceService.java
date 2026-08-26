package com.household.manager.presence;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Pflegt die Handys der Anwesenheitserkennung (Stammdaten). Status/Auswertung
 * liegen in {@link PresenceEvaluator}/{@link PresenceStatusService}.
 */
@Service
@RequiredArgsConstructor
public class PresenceDeviceService {

    /** Muss zur Spaltenlaenge von {@code PresenceDevice#name} passen. */
    private static final int MAX_NAME_LENGTH = 100;
    /** Muss zur Spaltenlaenge von {@code PresenceDevice#host} passen. */
    private static final int MAX_HOST_LENGTH = 255;

    private final PresenceDeviceRepository repository;
    private final AppUserRepository userRepository;
    private final PresenceMonitor monitor;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<PresenceDtos.DeviceAdminResponse> list() {
        return repository.findAllByOrderByIdAsc().stream()
                .map(PresenceDtos.DeviceAdminResponse::from)
                .toList();
    }

    @Transactional
    public PresenceDtos.DeviceAdminResponse create(PresenceDtos.DeviceRequest request) {
        validate(request);
        PresenceDevice device = PresenceDevice.builder()
                .userId(request.userId())
                .name(request.name().trim())
                .host(request.host().trim())
                .active(activeOrDefault(request))
                .build();
        PresenceDevice saved = repository.save(device);
        // MariaDB berechnet AUTO_INCREMENT nach einem Neustart neu (siehe
        // PresenceMonitor.remove Javadoc): eine wiedervergebene Id duerfte einen
        // Waisen-Eintrag des Monitors sonst als falsches PRESENT erben.
        monitor.remove(saved.getId());
        auditService.record("presence.device.create", auditDetail(saved));
        return PresenceDtos.DeviceAdminResponse.from(saved);
    }

    @Transactional
    public PresenceDtos.DeviceAdminResponse update(Long id, PresenceDtos.DeviceRequest request) {
        PresenceDevice device = findOrThrow(id);
        validate(request);
        String previousHost = trimOrNull(device.getHost());
        device.setUserId(request.userId());
        device.setName(request.name().trim());
        device.setHost(request.host().trim());
        device.setActive(activeOrDefault(request));
        PresenceDevice saved = repository.save(device);
        if (!Objects.equals(previousHost, trimOrNull(saved.getHost()))) {
            // Der Monitor ist per Id geschluesselt, nicht per Adresse: ohne diesen
            // Reset erbte eine korrigierte IP das firstCheckedAt/lastSeenAt der alten
            // Adresse und die Karenz waere fuer die neue Adresse bereits verbraucht.
            monitor.remove(id);
        }
        auditService.record("presence.device.update", auditDetail(saved));
        return PresenceDtos.DeviceAdminResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        PresenceDevice device = findOrThrow(id);
        repository.delete(device);
        monitor.remove(id);
        auditService.record("presence.device.delete", auditDetail(device));
    }

    private PresenceDevice findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PresenceDevice", "id", id));
    }

    /** Null-sicher, damit der Host-Vergleich beim Update Gleiches mit Gleichem vergleicht. */
    private String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }

    private String auditDetail(PresenceDevice device) {
        return "%s (%s, userId=%d)".formatted(device.getName(), device.getHost(), device.getUserId());
    }

    /** Fehlendes Feld heisst "aktiv" — wie der Default in Entity und Spalte (Muster Netzwerk-Geraete). */
    private boolean activeOrDefault(PresenceDtos.DeviceRequest request) {
        return request.active() == null || request.active();
    }

    private void validate(PresenceDtos.DeviceRequest request) {
        if (request.userId() == null) {
            throw new IllegalArgumentException("Es ist keine Person ausgewaehlt.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Der Name darf nicht leer sein.");
        }
        if (request.name().trim().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Der Name darf hoechstens %d Zeichen lang sein.".formatted(MAX_NAME_LENGTH));
        }
        if (request.host() == null || request.host().isBlank()) {
            throw new IllegalArgumentException("Die IP-Adresse darf nicht leer sein.");
        }
        String trimmedHost = request.host().trim();
        if (trimmedHost.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException(
                    "Die IP-Adresse darf hoechstens %d Zeichen lang sein.".formatted(MAX_HOST_LENGTH));
        }
        // trim() entfernt nur Rand-Whitespace. Anders als beim Netzwerk-Monitoring
        // (dort zeigt ein unerreichbares Geraet nur einen roten Punkt) fuehrt ein
        // unaufloesbarer Host hier zu STILLE ueber die Karenzzeit hinweg - und
        // Stille wird als "abwesend" gedeutet und loest Flows aus. Keine
        // IP-Format-Pruefung: Hostnamen sind eine legitime Eingabe.
        if (containsWhitespace(trimmedHost)) {
            throw new IllegalArgumentException("Die IP-Adresse darf keine Leerzeichen enthalten.");
        }
        if (!userRepository.existsById(request.userId())) {
            throw new IllegalArgumentException("Der Benutzer existiert nicht.");
        }
    }

    private boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }
}
