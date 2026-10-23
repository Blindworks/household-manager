package com.household.manager.audit;

import com.household.manager.model.entity.AuditLog;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.Flow;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.FlowRepository;
import com.household.manager.repository.SmartDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Macht Audit-Einträge lesbar: Ids von Flows, Geräten und Entitäten werden beim Lesen durch
 * Namen ersetzt. Bewusst beim Lesen statt beim Schreiben, damit auch der Bestand lesbar wird;
 * die Rohwerte bleiben in der Antwort erhalten. Wirft nie — eine scheiternde Namensauflösung
 * fällt auf die Id zurück.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEntryDescriber {

    public record DescribedEntry(AuditLog log, String actorLabel, String actionLabel, String detailLabel) {
    }

    private static final Pattern FLOW_ACTOR = Pattern.compile("FLOW:(\\d+)");
    private static final Pattern TELEGRAM_ACTOR = Pattern.compile("TELEGRAM:(-?\\d+)");
    private static final Pattern ID_ARROW_STATE = Pattern.compile("(\\S+) -> (\\S+)");
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    private static final Pattern DEVICE_ID_PARAM = Pattern.compile("deviceId=(\\d+)");
    private static final Pattern FLOW_REF = Pattern.compile("Flow (\\d+)");

    private static final Map<String, String> ACTION_LABELS = Map.ofEntries(
            Map.entry("auth.login", "Anmeldung"),
            Map.entry("auth.logout", "Abmeldung"),
            Map.entry("auth.change-password", "Passwort geändert"),
            Map.entry("switch.device", "Gerät geschaltet"),
            Map.entry("switch.toggle", "Schalter umgeschaltet"),
            Map.entry("entity.toggle", "Helfer umgeschaltet"),
            Map.entry("helper.set", "Helfer gesetzt"),
            Map.entry("device.on", "Gerät eingeschaltet"),
            Map.entry("device.off", "Gerät ausgeschaltet"),
            Map.entry("device.light.set", "Licht eingestellt"),
            Map.entry("device.tapo.address.set", "Tapo-IP gesetzt"),
            Map.entry("device.kasa.add-manual", "Kasa-Gerät hinzugefügt"),
            Map.entry("flow.create", "Flow angelegt"),
            Map.entry("flow.update", "Flow geändert"),
            Map.entry("flow.import", "Flow importiert"),
            Map.entry("flow.deploy", "Flow deployt"),
            Map.entry("flow.delete", "Flow gelöscht"),
            Map.entry("nuki.lock", "Tür verriegelt"),
            Map.entry("nuki.unlock", "Tür entriegelt"),
            Map.entry("nuki.unlatch", "Tür geöffnet"),
            Map.entry("thermostat.target", "Heizung: Sollwert"),
            Map.entry("thermostat.mode", "Heizung: Modus"),
            Map.entry("thermostat.bulk", "Heizung: alle"),
            Map.entry("agent.action.execute", "Hausverstand: Aktion"),
            Map.entry("agent.proposal.decide", "Hausverstand: Entscheidung"),
            Map.entry("system.reboot", "Neustart"));

    private final SmartDeviceRepository deviceRepository;
    private final FlowRepository flowRepository;
    private final EntityStateRepository entityStateRepository;

    public List<DescribedEntry> describe(List<AuditLog> logs) {
        Names names = new Names();
        return logs.stream().map(log -> describe(log, names)).toList();
    }

    private DescribedEntry describe(AuditLog log, Names names) {
        return new DescribedEntry(log, actorLabel(log.getActor(), names),
                ACTION_LABELS.getOrDefault(log.getAction(), log.getAction()),
                detailLabel(log.getAction(), log.getDetail(), names));
    }

    private String actorLabel(String actor, Names names) {
        if (actor == null) {
            return null;
        }
        Matcher flow = FLOW_ACTOR.matcher(actor);
        if (flow.matches()) {
            return names.flow(Long.parseLong(flow.group(1)))
                    .map(name -> "Flow „" + name + "“")
                    .orElse("Flow " + flow.group(1) + " (gelöscht)");
        }
        Matcher telegram = TELEGRAM_ACTOR.matcher(actor);
        if (telegram.matches()) {
            return "Telegram (Chat " + telegram.group(1) + ")";
        }
        return switch (actor) {
            case "AGENT" -> "Hausverstand";
            case "system" -> "System";
            default -> actor;
        };
    }

    private String detailLabel(String action, String detail, Names names) {
        if (detail == null || action == null) {
            return detail;
        }
        return switch (action) {
            case "switch.device" -> idArrowState(detail, id -> deviceName(id, names));
            case "helper.set" -> idArrowState(detail, names::entity);
            case "switch.toggle", "entity.toggle" -> names.entity(detail);
            case "device.on", "device.off" -> NUMERIC.matcher(detail).matches() ? deviceName(detail, names) : detail;
            case "device.light.set", "device.tapo.address.set" -> DEVICE_ID_PARAM.matcher(detail)
                    .replaceAll(match -> Matcher.quoteReplacement(deviceName(match.group(1), names)));
            case "flow.update", "flow.deploy", "flow.delete" -> FLOW_REF.matcher(detail).matches()
                    ? names.flow(Long.parseLong(detail.substring(5))).map(name -> "„" + name + "“").orElse(detail)
                    : detail;
            case "nuki.lock", "nuki.unlock", "nuki.unlatch" -> names.entity("lock.nuki_" + detail, detail);
            default -> detail;
        };
    }

    private static String idArrowState(String detail, Function<String, String> nameOf) {
        Matcher matcher = ID_ARROW_STATE.matcher(detail);
        if (!matcher.matches()) {
            return detail;
        }
        return nameOf.apply(matcher.group(1)) + " → " + germanState(matcher.group(2));
    }

    private static String germanState(String state) {
        return switch (state) {
            case "on" -> "an";
            case "off" -> "aus";
            default -> state;
        };
    }

    private static String deviceName(String id, Names names) {
        return names.device(Long.parseLong(id)).orElse("Gerät " + id);
    }

    /** Namensquellen eines Abrufs: je Aufruf einmal geladen, Entitäten bei Bedarf einzeln. */
    private final class Names {
        private Map<Long, String> devices;
        private Map<Long, String> flows;
        private final Map<String, String> entities = new HashMap<>();

        Optional<String> device(long id) {
            if (devices == null) {
                devices = load("Geräte", () -> deviceRepository.findAll().stream()
                        .collect(Collectors.toMap(SmartDevice::getId, SmartDevice::getDeviceName, (a, b) -> a)));
            }
            return Optional.ofNullable(devices.get(id));
        }

        Optional<String> flow(long id) {
            if (flows == null) {
                flows = load("Flows", () -> flowRepository.findAll().stream()
                        .collect(Collectors.toMap(Flow::getId, Flow::getName, (a, b) -> a)));
            }
            return Optional.ofNullable(flows.get(id));
        }

        String entity(String entityId) {
            return entity(entityId, entityId);
        }

        String entity(String entityId, String fallback) {
            return entities.computeIfAbsent(entityId, id -> {
                try {
                    return entityStateRepository.findByEntityId(id).map(EntityState::getFriendlyName).orElse(fallback);
                } catch (Exception ex) {
                    log.debug("Audit: Entität {} nicht auflösbar: {}", id, ex.getMessage());
                    return fallback;
                }
            });
        }

        private <K> Map<K, String> load(String what, Supplier<Map<K, String>> loader) {
            try {
                return loader.get();
            } catch (Exception ex) {
                log.debug("Audit: {} nicht auflösbar: {}", what, ex.getMessage());
                return Map.of();
            }
        }
    }
}
