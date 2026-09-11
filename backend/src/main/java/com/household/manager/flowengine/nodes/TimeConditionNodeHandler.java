package com.household.manager.flowengine.nodes;

import com.household.manager.common.TimeWindow;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bedingungs-Node: liegt die aktuelle Uhrzeit im Fenster {@code [from, to)}?
 * Port 0 = wahr, Port 1 = falsch. Die Fensterregel (Mitternacht ueberspannend,
 * leeres Fenster bei {@code from == to}) ist {@link TimeWindow} — dieselbe wie
 * beim Modus-Schnellzugriff.
 *
 * <p>Gerechnet wird mit dem {@link Clock}-Bean (Haushaltszeit), nicht mit der
 * Systemzone: die Cron-Trigger haengen noch an {@code systemDefault}, dieser Node
 * soll die UTC-Falle nicht wiederholen.
 *
 * <p>Zur Laufzeit wirft der Node nie — Format und Nicht-Leere des Fensters prueft
 * {@link #validate(NodeConfig)} beim Deploy.
 */
@Component
public class TimeConditionNodeHandler implements NodeHandler {

    static final String FROM = "from";
    static final String TO = "to";

    private final Clock clock;

    public TimeConditionNodeHandler(Clock clock) {
        this.clock = clock;
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
        Optional<LocalTime> from = parseTime(config, FROM, errors);
        Optional<LocalTime> to = parseTime(config, TO, errors);
        if (from.isPresent() && to.isPresent() && from.get().equals(to.get())) {
            errors.add("from und to duerfen nicht gleich sein (leeres Fenster)");
        }
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        TimeWindow window = new TimeWindow(requireTime(config, FROM), requireTime(config, TO));
        boolean inside = window.contains(LocalTime.now(clock));
        return NodeResult.port(inside ? 0 : 1, message);
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field(FROM, "Von (HH:mm, inklusive)", NodeFieldType.STRING, true),
                NodeFieldDescriptor.field(TO, "Bis (HH:mm, exklusive)", NodeFieldType.STRING, true));
    }

    @Override
    public List<String> portLabels() {
        return List.of("wahr", "falsch");
    }

    private static Optional<LocalTime> parseTime(NodeConfig config, String key, List<String> errors) {
        Optional<String> raw = config.string(key).map(String::trim).filter(s -> !s.isEmpty());
        if (raw.isEmpty()) {
            errors.add(key + " fehlt");
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(raw.get()));
        } catch (DateTimeParseException ex) {
            errors.add(key + " ist keine Uhrzeit im Format HH:mm: '" + raw.get() + "'");
            return Optional.empty();
        }
    }

    private static LocalTime requireTime(NodeConfig config, String key) {
        return LocalTime.parse(config.string(key).orElseThrow().trim());
    }
}
