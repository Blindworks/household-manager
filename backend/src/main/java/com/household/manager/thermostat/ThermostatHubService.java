package com.household.manager.thermostat;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.tapo.TapoException;
import com.household.manager.tapo.hub.HubProbeResult;
import com.household.manager.tapo.hub.TapoHubClient;
import com.household.manager.tapo.hub.ThermostatReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pflege des Hubs: die IP wird von Hand gesetzt (die UDP-Discovery findet im Docker-Bridge-Netz
 * nichts), sofort geprobt und die gefundenen Thermostate werden angelegt bzw. aktualisiert.
 *
 * <p>Identitaetspruefung: gehoert die IP laut DB bereits zu einem anderen Hub (abweichende
 * {@code device_id}), wird abgelehnt, bevor irgendetwas geschrieben wird. Sonst wuerde eine
 * vertippte-aber-gueltige IP die Zeilen eines Hubs mit den Kindgeraeten eines anderen ueberschreiben.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThermostatHubService {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9.:-]{1,45}");

    private final ThermostatRepository repository;
    private final TapoHubClient hubClient;
    private final ThermostatStateCache cache;
    private final AuditService auditService;
    private final Clock clock;

    public List<ThermostatDto> registerHub(String rawIp) {
        String ip = rawIp == null ? "" : rawIp.trim();
        if (!HOST_PATTERN.matcher(ip).matches()) {
            throw new IllegalArgumentException("Die Hub-Adresse ist ungueltig.");
        }
        HubProbeResult probe = hubClient.probeHub(ip);
        if (probe.hubDeviceId() == null || probe.hubDeviceId().isBlank()) {
            throw new TapoException("Der Hub unter " + ip + " meldet keine device_id.");
        }
        assertSameHub(ip, probe.hubDeviceId());

        List<Thermostat> saved = new ArrayList<>();
        for (ThermostatReading reading : probe.thermostats()) {
            saved.add(upsert(ip, probe.hubDeviceId(), reading));
        }
        cache.applyHubReadings(saved, probe.thermostats(), LocalDateTime.now(clock));
        auditService.record("thermostat.hub", ip + " (" + probe.hubDeviceId() + "): "
                + saved.size() + " Thermostate");
        return saved.stream()
                .map(thermostat -> ThermostatDto.from(thermostat, cache.find(thermostat.getId()).orElse(null)))
                .toList();
    }

    private void assertSameHub(String ip, String reportedHubId) {
        boolean foreign = repository.findByHubIp(ip).stream()
                .anyMatch(existing -> !reportedHubId.equals(existing.getHubDeviceId()));
        if (foreign) {
            throw new IllegalArgumentException("Unter " + ip + " antwortet ein anderer Hub als der bisher "
                    + "hinterlegte. Adresse pruefen.");
        }
    }

    /** Der Name kommt nur beim Anlegen aus dem Nickname; eine spaetere Umbenennung bleibt erhalten. */
    private Thermostat upsert(String ip, String hubDeviceId, ThermostatReading reading) {
        Thermostat thermostat = repository.findByChildDeviceId(reading.childDeviceId())
                .orElseGet(() -> Thermostat.builder()
                        .childDeviceId(reading.childDeviceId())
                        .name(initialName(reading))
                        .active(true)
                        .build());
        thermostat.setHubDeviceId(hubDeviceId);
        thermostat.setHubIp(ip);
        return repository.save(thermostat);
    }

    private String initialName(ThermostatReading reading) {
        String nickname = reading.nickname();
        return nickname == null || nickname.isBlank() ? reading.childDeviceId() : nickname.trim();
    }
}
