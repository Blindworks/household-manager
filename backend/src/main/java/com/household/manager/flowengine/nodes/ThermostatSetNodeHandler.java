package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import com.household.manager.thermostat.ThermostatService;
import com.household.manager.thermostat.ThermostatStateCache;
import com.household.manager.thermostat.ThermostatTargetValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aktions-Node: schaltet ein Thermostat aus, stellt den Sollwert von vor dem Ausschalten wieder
 * her oder setzt einen festen Sollwert — gebaut für „Fenster auf ⇒ Heizung aus, Fenster zu ⇒
 * wie vorher". Geschrieben wird über {@link ThermostatService}, also mit derselben Validierung,
 * demselben Nachlesen und demselben Audit wie die Heizungsansicht des Wandtablets.
 *
 * <p>Der gemerkte Sollwert liegt je Thermostat in diesem (Singleton-)Bean, nicht im
 * {@link NodeContext}: „off" und „restore" sind zwei verschiedene Nodes, und ein Re-Deploy
 * erzeugt neue Kontexte. Er überlebt keinen Backend-Neustart; ein „restore" ohne gemerkten Wert
 * schaltet auf MANUAL, was beim Aus-Zustand den Standard-Sollwert des Services setzt.
 *
 * <p>Nach einem „restore" bleibt der Wert noch {@link #KEEP_AFTER_RESTORE} gemerkt. Das Thermostat
 * meldet einen neuen Sollwert erst verzögert an den Hub; wird das Fenster kurz nach dem Schließen
 * wieder geöffnet, steht im Cache noch das Aus-Ziel, und ohne diese Frist gäbe es beim nächsten
 * Schließen nichts mehr zurückzustellen (real am 2026-11-04: Bad fiel von 21 auf 17 Grad).
 *
 * <p>Gerätefehler werden <b>nicht</b> geschluckt (anders als {@code light-set}): ein nicht
 * abgeschaltetes Thermostat soll im Flow-Debug sichtbar sein. Parallele Zweige desselben
 * Triggers laufen trotzdem weiter.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ThermostatSetNodeHandler implements NodeHandler {

    static final String OFF = "off";
    static final String RESTORE = "restore";
    static final String TARGET = "target";
    private static final List<String> ACTIONS = List.of(OFF, RESTORE, TARGET);
    static final Duration KEEP_AFTER_RESTORE = Duration.ofMinutes(15);

    private final ThermostatService thermostatService;
    private final ThermostatStateCache cache;
    private final Clock clock;

    /** {@code restoredAt} null: noch nicht zurückgestellt (Fenster offen). */
    private record Remembered(double target, Instant restoredAt) {
    }

    private final Map<Long, Remembered> rememberedTargets = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "thermostat-set";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (thermostatId(config).isEmpty()) {
            errors.add("thermostatId fehlt oder ist keine positive ganze Zahl");
        }
        String action = config.string("action").orElse(null);
        if (!ACTIONS.contains(action)) {
            errors.add("action muss 'off', 'restore' oder 'target' sein");
        } else if (TARGET.equals(action)) {
            Optional<Double> target = targetCelsius(config);
            if (target.isEmpty()) {
                errors.add("targetCelsius fehlt oder ist keine Zahl");
            } else {
                try {
                    ThermostatTargetValidator.validate(target.get());
                } catch (IllegalArgumentException ex) {
                    errors.add("targetCelsius: " + ex.getMessage());
                }
            }
        }
        // Ob das Thermostat existiert, prueft erst die Laufzeit (Muster helper-set).
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        long id = thermostatId(config).orElseThrow();
        switch (config.string("action").orElseThrow()) {
            case OFF -> switchOff(id);
            case RESTORE -> restore(id);
            case TARGET -> thermostatService.setTarget(id, targetCelsius(config).orElseThrow());
            default -> throw new IllegalStateException("Unbekannte action");
        }
        return NodeResult.single(message);
    }

    /**
     * Merkt sich nur ein Ziel über dem Aus-Wert: ein zweites „off" bei schon ausgeschaltetem
     * Thermostat darf den echten Sollwert nicht mit 5 Grad überschreiben. Ist kein brauchbarer
     * Wert lesbar, bleibt ein noch offener oder kürzlich zurückgestellter Wert gemerkt. Gemerkt
     * wird erst nach erfolgreichem Schalten.
     */
    private void switchOff(long id) {
        Optional<Double> before = currentReading(id)
                .map(ThermostatReading::targetTemp)
                .filter(target -> target > ThermostatTargetValidator.MIN_TARGET);
        thermostatService.setMode(id, ThermostatMode.OFF);
        if (before.isPresent()) {
            rememberedTargets.put(id, new Remembered(before.get(), null));
        } else {
            rememberedTargets.computeIfPresent(id,
                    (key, remembered) -> isUsable(remembered) ? new Remembered(remembered.target(), null) : null);
        }
    }

    /**
     * Stellt nur ein Thermostat zurück, das noch aus ist; hat es jemand in der Zwischenzeit von
     * Hand eingeschaltet, gewinnt dessen Wert. Ist der Zustand unbekannt (Cache leer), wird der
     * gemerkte Wert trotzdem geschrieben — das ist idempotent.
     */
    private void restore(long id) {
        Remembered remembered = rememberedTargets.get(id);
        if (remembered != null && !isUsable(remembered)) {
            rememberedTargets.remove(id);
            remembered = null;
        }
        Optional<ThermostatReading> current = currentReading(id).filter(reading -> reading.targetTemp() != null);
        if (current.isPresent() && current.get().mode() != ThermostatMode.OFF) {
            log.info("Thermostat {} wurde zwischenzeitlich eingeschaltet, restore laesst es unveraendert", id);
            rememberedTargets.remove(id);
            return;
        }
        if (remembered == null) {
            log.warn("Thermostat {}: kein gemerkter Sollwert (Neustart?), schalte auf MANUAL", id);
            thermostatService.setMode(id, ThermostatMode.MANUAL);
            return;
        }
        thermostatService.setTarget(id, remembered.target());
        rememberedTargets.put(id, new Remembered(remembered.target(), Instant.now(clock)));
    }

    /** Offen gemerkt ist immer brauchbar, zurückgestellt nur innerhalb der Frist. */
    private boolean isUsable(Remembered remembered) {
        return remembered.restoredAt() == null
                || !Instant.now(clock).isAfter(remembered.restoredAt().plus(KEEP_AFTER_RESTORE));
    }

    private Optional<ThermostatReading> currentReading(long id) {
        return cache.find(id).map(ThermostatStateCache.Entry::reading);
    }

    private static Optional<Long> thermostatId(NodeConfig config) {
        return number(config, "thermostatId")
                .filter(value -> value > 0 && value == Math.rint(value))
                .map(Double::longValue);
    }

    private static Optional<Double> targetCelsius(NodeConfig config) {
        return number(config, "targetCelsius");
    }

    /** Über {@code string()} statt {@code integer()}: Letzteres kürzt 1.5 klaglos auf 1. */
    private static Optional<Double> number(NodeConfig config, String key) {
        try {
            return config.string(key).map(String::trim).map(Double::parseDouble).filter(Double::isFinite);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("thermostatId", "Thermostat-ID", NodeFieldType.NUMBER, true),
                NodeFieldDescriptor.enumField("action", "Aktion", true, ACTIONS),
                NodeFieldDescriptor.field("targetCelsius", "Solltemperatur (nur bei 'target')", NodeFieldType.NUMBER, false));
    }
}
