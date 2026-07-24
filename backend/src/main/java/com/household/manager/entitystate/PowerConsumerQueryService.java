package com.household.manager.entitystate;

import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Liefert die Stromverbraucher für die Verbraucher-Kachel: alle Power-Sensoren
 * der Entity-State-Schicht (deviceClass "power"), absteigend nach Leistung.
 * <p>
 * Nicht jeder Power-Sensor ist ein Verbraucher: Haus-Bilanz-Quellen
 * (Tasmota-Gesamtverbrauch, Anker-Solix PV/Akku/Netz) und Erzeuger
 * (Shelly an den Balkonkraftwerken) werden ausgeschlossen.
 */
@Service
@RequiredArgsConstructor
public class PowerConsumerQueryService {

    private static final String DEVICE_CLASS_POWER = "power";
    /**
     * Quellen, deren Power-Sensoren keine Einzelverbraucher sind: Haus-Bilanz
     * (TASMOTA, ANKER_SOLIX) und Erzeugung (SHELLY an den Balkonkraftwerken).
     */
    private static final Set<EntitySource> NON_CONSUMER_SOURCES =
            Set.of(EntitySource.TASMOTA, EntitySource.ANKER_SOLIX, EntitySource.SHELLY);

    private final EntityStateRepository entityStateRepository;
    private final EntityStateResponseMapper entityStateResponseMapper;

    /** @param limit maximale Anzahl Einträge; null oder <= 0 liefert alle */
    @Transactional(readOnly = true)
    public List<PowerConsumerResponse> listConsumers(Integer limit) {
        List<PowerConsumerResponse> consumers = entityStateRepository
                .findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR).stream()
                .filter(entity -> !NON_CONSUMER_SOURCES.contains(entity.getSource()))
                .filter(this::isPowerSensor)
                .map(this::toResponse)
                .sorted(byPowerDescending())
                .toList();

        if (limit != null && limit > 0 && limit < consumers.size()) {
            return List.copyOf(consumers.subList(0, limit));
        }
        return consumers;
    }

    /**
     * Sucht eine Entitaet, die als Verbraucher gilt (Power-Sensor, keine
     * Haus-Bilanz-/Erzeuger-Quelle). Einzige Definitionsstelle — die Kachel-Liste
     * und der Historie-Endpoint fragen beide hier.
     *
     * @return die Entitaet, oder leer wenn unbekannt oder kein Verbraucher
     */
    @Transactional(readOnly = true)
    public Optional<EntityState> findConsumer(String entityId) {
        return entityStateRepository.findByEntityId(entityId)
                .filter(entity -> !NON_CONSUMER_SOURCES.contains(entity.getSource()))
                .filter(this::isPowerSensor);
    }

    private boolean isPowerSensor(EntityState entity) {
        Map<String, Object> attributes =
                entityStateResponseMapper.parseAttributes(entity.getAttributes());
        return DEVICE_CLASS_POWER.equals(attributes.get("deviceClass"));
    }

    private PowerConsumerResponse toResponse(EntityState entity) {
        BigDecimal watts = parseWatts(entity.getState());
        return new PowerConsumerResponse(
                entity.getEntityId(),
                entityStateResponseMapper.displayName(entity),
                watts,
                watts == null);
    }

    /** Nicht-numerische States ("unavailable", "unknown") ergeben null. */
    private BigDecimal parseWatts(String state) {
        if (state == null) {
            return null;
        }
        try {
            return new BigDecimal(state.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Größter Verbraucher zuerst; unavailable ans Ende; Gleichstand alphabetisch. */
    private Comparator<PowerConsumerResponse> byPowerDescending() {
        return Comparator.comparing(PowerConsumerResponse::powerWatts,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(consumer -> consumer.displayName().toLowerCase(Locale.ROOT));
    }
}
