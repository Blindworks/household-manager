package com.household.manager.entitystate;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Liefert die schaltbaren Entitäten für Schalter-Kachel und -Dialog.
 * <p>
 * Die Dialog-Sicht zeigt alle Schalter nutzungsbasiert sortiert. Die
 * Kachel-Sicht wendet zusätzlich die benutzergepflegten Sichtbarkeitsregeln
 * an: NEVER und inaktive WHEN_ON werden gefiltert, sortiert wird in Gruppen
 * (aktive WHEN_ON, dann ALWAYS, dann Rest) — innerhalb jeder Gruppe nach Nutzung.
 */
@Service
@RequiredArgsConstructor
public class SwitchQueryService {

    private static final String STATE_ON = "on";

    private final EntityStateRepository entityStateRepository;
    private final EntityUsageService entityUsageService;
    private final EntityTileVisibilityService tileVisibilityService;
    private final SwitchResponseMapper switchResponseMapper;
    private final EntityStateResponseMapper entityStateResponseMapper;

    /** Dialog-Sicht ohne Sichtbarkeitsregeln (Kompatibilitäts-Überladung). */
    @Transactional(readOnly = true)
    public List<SwitchResponse> listSwitches(Integer limit) {
        return listSwitches(limit, false);
    }

    /**
     * @param limit    maximale Anzahl Einträge; null oder <= 0 liefert alle
     * @param tileView true wendet die Kachel-Sichtbarkeitsregeln an
     */
    @Transactional(readOnly = true)
    public List<SwitchResponse> listSwitches(Integer limit, boolean tileView) {
        Map<String, TileVisibility> rules = tileView
                ? tileVisibilityService.tileRules(DashboardTiles.SWITCHES)
                : Map.of();

        List<EntityState> switchable = entityStateRepository
                .findByDomainInOrderByEntityIdAsc(SwitchableEntities.SWITCHABLE_DOMAINS).stream()
                .filter(SwitchableEntities::isSwitchable)
                // Haus-Modi haben eine eigene Leiste im Dashboard und die Modus-API.
                .filter(entity -> !HouseModes.isMode(
                        entityStateResponseMapper.parseAttributes(entity.getAttributes())))
                .filter(entity -> !tileView || visibleOnTile(entity, rules))
                .toList();

        Map<String, EntityUsage> usage = entityUsageService.usageFor(
                switchable.stream().map(EntityState::getEntityId).toList());

        Map<String, EntityState> powerSensors = powerSensorsBySwitchId(switchable);

        record Ranked(SwitchResponse response, int rank) {
        }
        List<SwitchResponse> switches = switchable.stream()
                .map(entity -> new Ranked(
                        switchResponseMapper.toResponse(entity, usage.get(entity.getEntityId()),
                                powerSensors.get(entity.getEntityId())),
                        tileRank(entity, rules)))
                .sorted(Comparator.comparingInt(Ranked::rank)
                        .thenComparing(Ranked::response, byUsage()))
                .map(Ranked::response)
                .toList();

        if (limit != null && limit > 0 && limit < switches.size()) {
            return List.copyOf(switches.subList(0, limit));
        }
        return switches;
    }

    /**
     * Lädt zu jedem Schalter den Power-Sensor gleicher Quelle über die
     * entityId-Konvention {@code sensor.<source>_<slug(ref)>_power} — ein Query für alle.
     */
    private Map<String, EntityState> powerSensorsBySwitchId(List<EntityState> switches) {
        if (switches.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sensorIdBySwitchId = new HashMap<>();
        for (EntityState sw : switches) {
            sensorIdBySwitchId.put(sw.getEntityId(),
                    EntityIds.build(EntityDomain.SENSOR, sw.getSource(), sw.getSourceRef(), "power"));
        }
        Map<String, EntityState> sensorsById = entityStateRepository
                .findByEntityIdIn(sensorIdBySwitchId.values()).stream()
                .collect(Collectors.toMap(EntityState::getEntityId, Function.identity()));
        Map<String, EntityState> bySwitchId = new HashMap<>();
        sensorIdBySwitchId.forEach((switchId, sensorId) -> {
            EntityState sensor = sensorsById.get(sensorId);
            if (sensor != null) {
                bySwitchId.put(switchId, sensor);
            }
        });
        return bySwitchId;
    }

    /** Kachel-Filter: NEVER nie, WHEN_ON nur solange der Zustand "on" ist. */
    private boolean visibleOnTile(EntityState entity, Map<String, TileVisibility> rules) {
        return switch (rules.getOrDefault(entity.getEntityId(), TileVisibility.AUTO)) {
            case NEVER -> false;
            case WHEN_ON -> STATE_ON.equals(entity.getState());
            case ALWAYS, AUTO -> true;
        };
    }

    /** Gruppen-Rang der Kachel: aktive WHEN_ON (0) vor ALWAYS (1) vor Rest (2). */
    private int tileRank(EntityState entity, Map<String, TileVisibility> rules) {
        return switch (rules.getOrDefault(entity.getEntityId(), TileVisibility.AUTO)) {
            case WHEN_ON -> 0;
            case ALWAYS -> 1;
            case AUTO, NEVER -> 2;
        };
    }

    /** Meistgenutzt zuerst; bei Gleichstand zuletzt geschaltet, dann alphabetisch. */
    private Comparator<SwitchResponse> byUsage() {
        Comparator<SwitchResponse> byCount = Comparator.comparingLong(SwitchResponse::toggleCount);
        return byCount.reversed()
                .thenComparing(SwitchResponse::lastToggledAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(response -> response.displayName().toLowerCase(Locale.ROOT));
    }
}
