package com.household.manager.flowengine.nodes;

import com.household.manager.common.TimeWindow;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.sun.SunTimeExpression;
import com.household.manager.sun.SunTimesService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bedingungs-Node: liegt die aktuelle Uhrzeit im Fenster {@code [from, to)}?
 * Port 0 = wahr, Port 1 = falsch. Die Fensterregel (Mitternacht ueberspannend,
 * leeres Fenster bei {@code from == to}) ist {@link TimeWindow} — dieselbe wie
 * beim Modus-Schnellzugriff.
 *
 * <p>{@code from}/{@code to} sind {@link SunTimeExpression}s: feste Uhrzeit
 * ({@code HH:mm}) oder Sonnenereignis mit Versatz ({@code sunset-30}, {@code dawn}).
 * Beide werden fuer den <b>heutigen</b> Tag aufgeloest. Ist ein Sonnenausdruck nicht
 * aufloesbar (kein Zuhause konfiguriert), gilt die Bedingung als <b>falsch</b> —
 * „nicht pruefbar" darf nicht als „erfuellt" gelten, sonst schaltete „Licht wenn
 * dunkel" mittags.
 *
 * <p>Gerechnet wird mit dem {@link Clock}-Bean (Haushaltszeit), nicht mit der
 * Systemzone: die Cron-Trigger haengen noch an {@code systemDefault}, dieser Node
 * soll die UTC-Falle nicht wiederholen.
 *
 * <p>Zur Laufzeit wirft der Node nie — Grammatik und Nicht-Leere des Fensters prueft
 * {@link #validate(NodeConfig)} beim Deploy.
 */
@Component
public class TimeConditionNodeHandler implements NodeHandler {

    static final String FROM = "from";
    static final String TO = "to";

    private final Clock clock;
    private final SunTimesService sunTimes;

    public TimeConditionNodeHandler(Clock clock, SunTimesService sunTimes) {
        this.clock = clock;
        this.sunTimes = sunTimes;
    }

    @Override
    public String type() {
        return "time-condition";
    }

    @Override
    public int outputPorts() {
        return 2;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        Optional<SunTimeExpression> from = parseExpression(config, FROM, errors);
        Optional<SunTimeExpression> to = parseExpression(config, TO, errors);
        if (from.isPresent() && to.isPresent() && from.get().equals(to.get())) {
            errors.add("from und to duerfen nicht gleich sein (leeres Fenster)");
        }
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        LocalDate today = LocalDate.now(clock);
        Optional<LocalTime> from = requireExpression(config, FROM).resolve(today, sunTimes);
        Optional<LocalTime> to = requireExpression(config, TO).resolve(today, sunTimes);
        if (from.isEmpty() || to.isEmpty()) {
            ctx.debug("time-condition: Sonnenzeit nicht bestimmbar (kein Zuhause konfiguriert?) – gilt als falsch", message);
            return NodeResult.port(1, message);
        }
        TimeWindow window = new TimeWindow(from.get(), to.get());
        boolean inside = window.contains(LocalTime.now(clock));
        return NodeResult.port(inside ? 0 : 1, message);
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field(FROM, "Von (HH:mm oder dawn/sunrise/sunset/dusk±Min, inklusive)", NodeFieldType.STRING, true),
                NodeFieldDescriptor.field(TO, "Bis (HH:mm oder dawn/sunrise/sunset/dusk±Min, exklusive)", NodeFieldType.STRING, true));
    }

    @Override
    public List<String> portLabels() {
        return List.of("wahr", "falsch");
    }

    private static Optional<SunTimeExpression> parseExpression(NodeConfig config, String key, List<String> errors) {
        Optional<String> raw = config.string(key).map(String::trim).filter(s -> !s.isEmpty());
        if (raw.isEmpty()) {
            errors.add(key + " fehlt");
            return Optional.empty();
        }
        try {
            return Optional.of(SunTimeExpression.parse(raw.get()));
        } catch (IllegalArgumentException ex) {
            errors.add(key + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    private static SunTimeExpression requireExpression(NodeConfig config, String key) {
        return SunTimeExpression.parse(config.string(key).orElseThrow());
    }
}
