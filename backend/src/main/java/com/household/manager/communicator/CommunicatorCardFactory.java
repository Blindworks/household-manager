package com.household.manager.communicator;

import com.household.manager.communicator.CommunicatorDtos.Card;
import com.household.manager.communicator.CommunicatorDtos.Suggestion;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.ModeResponse;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.service.TemperatureSeriesService;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import com.household.manager.thermostat.ThermostatTargetValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Die Karten-Werkzeuge des Communicators ({@code show_*}). Das Modell waehlt nur aus, WAS gezeigt wird
 * (IDs, Titel, Vorschlaege); alle Werte liest diese Klasse frisch aus den Diensten, damit auf einer Karte
 * nie eine vom Modell erfundene Zahl steht. Eine unbekannte ID ist ein Fehler fuers Modell, keine Karte.
 */
@Component
@RequiredArgsConstructor
public class CommunicatorCardFactory {

    static final String SHOW_CLIMATE = "show_climate";
    static final String SHOW_SWITCH = "show_switch";
    static final String SHOW_MODE = "show_mode";
    static final String SHOW_LOCK = "show_lock";
    static final String SHOW_INFO = "show_info";
    private static final Set<String> TOOL_NAMES = Set.of(SHOW_CLIMATE, SHOW_SWITCH, SHOW_MODE, SHOW_LOCK, SHOW_INFO);

    static final int MAX_SUGGESTIONS = 4;
    private static final int MAX_LABEL_LENGTH = 30;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final int MAX_TEXT_LENGTH = 400;
    private static final int MAX_QUESTION_LENGTH = 200;
    private static final long STALE_MINUTES = 60;
    private static final Set<String> ON_OFF = Set.of("on", "off");

    private final ThermostatService thermostatService;
    private final TemperatureSeriesService temperatureSeriesService;
    private final SwitchQueryService switchQueryService;
    private final HouseModeQueryService houseModeQueryService;
    private final NukiLockService nukiLockService;
    private final Clock clock;

    public boolean handles(String toolName) {
        return TOOL_NAMES.contains(toolName);
    }

    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
                tool(SHOW_CLIMATE, "Zeigt eine grosse Temperatur-Karte fuer einen Raum: Ist-Temperatur, Feuchte und, "
                                + "wenn der Raum ein Thermostat hat, den Sollwert zum Verstellen. Mindestens sensorId "
                                + "oder thermostatId angeben; gehoeren beide zum selben Raum, beide angeben.",
                        Map.of("sensorId", string("sensorId eines Temperaturfuehlers aus dem Hauszustand"),
                                "thermostatId", Map.of("type", "integer", "description", "Id eines Thermostats"),
                                "title", string("Kurzer Raumname, z. B. Wohnzimmer"),
                                "suggestions", suggestionsSchema()),
                        List.of()),
                tool(SHOW_SWITCH, "Zeigt eine Karte fuer ein schaltbares Geraet (Licht, Steckdose) mit grossem Schalter.",
                        Map.of("entityId", string("Entity-ID des Schalters aus dem Hauszustand"),
                                "suggestions", suggestionsSchema()),
                        List.of("entityId")),
                tool(SHOW_MODE, "Zeigt eine Karte fuer einen Haus-Modus (z. B. Nachtmodus) mit grossem Schalter.",
                        Map.of("entityId", string("Entity-ID des Modus aus dem Hauszustand"),
                                "suggestions", suggestionsSchema()),
                        List.of("entityId")),
                tool(SHOW_LOCK, "Zeigt den Zustand der Haustuer (verriegelt, offen) mit Knopf zum Verriegeln.",
                        Map.of("suggestions", suggestionsSchema()),
                        List.of()),
                tool(SHOW_INFO, "Zeigt eine Text-Karte fuer alles ohne eigene Karte: grosses Symbol, Titel, hoechstens "
                                + "zwei kurze Saetze.",
                        Map.of("icon", string("Material-Symbols-Name, z. B. info, bolt, pets, calendar_month"),
                                "title", string("Kurzer Titel"),
                                "text", string("Hoechstens zwei kurze Saetze"),
                                "suggestions", suggestionsSchema()),
                        List.of("title", "text")));
    }

    /** @throws IllegalArgumentException bei unbekannter ID oder fehlender Angabe (Fehler fuers Modell). */
    public Card build(String toolName, Map<String, Object> input) {
        return switch (toolName) {
            case SHOW_CLIMATE -> climate(input);
            case SHOW_SWITCH -> switchCard(input);
            case SHOW_MODE -> mode(input);
            case SHOW_LOCK -> lock(input);
            case SHOW_INFO -> info(input);
            default -> throw new IllegalArgumentException("Unbekannte Karte: " + toolName);
        };
    }

    /** Text-Karte ohne Modell-Eingabe, fuer Rueckfall und Fehlermeldungen. */
    public static Card infoCard(String icon, String title, String text) {
        return Card.builder().type(CommunicatorDtos.CARD_INFO).icon(icon).title(title).text(text)
                .suggestions(List.of()).build();
    }

    // --- Karten ---------------------------------------------------------------------------

    private Card climate(Map<String, Object> input) {
        String sensorId = optionalString(input, "sensorId");
        Long thermostatId = optionalLong(input, "thermostatId");
        if (sensorId == null && thermostatId == null) {
            throw new IllegalArgumentException("sensorId oder thermostatId angeben");
        }
        List<ThermostatDto> thermostats = thermostatService.list();
        ThermostatDto thermostat = thermostatId == null
                ? thermostats.stream().filter(t -> sensorId.equals(t.roomSensorId())).findFirst().orElse(null)
                : thermostats.stream().filter(t -> thermostatId.equals(t.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Thermostat: " + thermostatId));

        String effectiveSensorId = sensorId != null ? sensorId
                : thermostat != null ? thermostat.roomSensorId() : null;
        CurrentTemperatureReading reading = effectiveSensorId == null ? null : findReading(effectiveSensorId);
        if (sensorId != null && reading == null) {
            throw new IllegalArgumentException("Unbekannter Temperaturfuehler: " + sensorId);
        }

        Card.CardBuilder card = Card.builder().type(CommunicatorDtos.CARD_CLIMATE).icon("device_thermostat");
        card.title(title(input, reading != null ? reading.getName() : thermostat.name()));
        if (reading != null && reading.getTemperature() != null) {
            card.temperature(reading.getTemperature().doubleValue());
            card.humidity(reading.getHumidity() == null ? null : reading.getHumidity().doubleValue());
            card.stale(isStale(reading.getMeasuredAt()));
        } else if (thermostat != null) {
            card.temperature(thermostat.currentTemp());
            card.stale(!thermostat.online() || isStale(thermostat.lastUpdated()));
        }
        if (thermostat != null && thermostat.online()) {
            card.thermostatId(thermostat.id()).targetTemp(thermostat.targetTemp()).heating(thermostat.heating())
                    .thermostatMode(thermostat.mode() == null ? null : thermostat.mode().name());
        }
        return card.suggestions(suggestions(input)).build();
    }

    private CurrentTemperatureReading findReading(String sensorId) {
        return temperatureSeriesService.getCurrent().stream()
                .filter(r -> sensorId.equals(r.getSensorId())).findFirst().orElse(null);
    }

    private boolean isStale(LocalDateTime measuredAt) {
        return measuredAt == null
                || Duration.between(measuredAt, LocalDateTime.now(clock)).toMinutes() > STALE_MINUTES;
    }

    private Card switchCard(Map<String, Object> input) {
        String entityId = requireString(input, "entityId");
        SwitchResponse sw = findSwitch(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Schalter: " + entityId));
        return Card.builder().type(CommunicatorDtos.CARD_SWITCH)
                .title(sw.displayName()).icon(sw.icon() == null ? "power_settings_new" : sw.icon())
                .entityId(sw.entityId()).state(sw.state()).powerWatts(sw.powerWatts())
                .suggestions(suggestions(input)).build();
    }

    private Card mode(Map<String, Object> input) {
        String entityId = requireString(input, "entityId");
        ModeResponse mode = findMode(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Modus: " + entityId));
        return Card.builder().type(CommunicatorDtos.CARD_MODE)
                .title(mode.displayName()).icon(mode.icon() == null ? "tune" : mode.icon())
                .entityId(mode.entityId()).state(mode.state())
                .suggestions(suggestions(input)).build();
    }

    private Card lock(Map<String, Object> input) {
        NukiLockResponse lock = nukiLockService.listLocks().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Kein Tuerschloss vorhanden"));
        return Card.builder().type(CommunicatorDtos.CARD_LOCK)
                .title(lock.name()).icon("lock")
                .smartlockId(lock.smartlockId()).state(lock.state()).doorState(lock.doorState())
                .suggestions(suggestions(input)).build();
    }

    private Card info(Map<String, Object> input) {
        String icon = optionalString(input, "icon");
        return Card.builder().type(CommunicatorDtos.CARD_INFO)
                .icon(icon == null || !icon.matches("[a-z0-9_]{1,40}") ? "info" : icon)
                .title(cut(requireString(input, "title"), MAX_TITLE_LENGTH))
                .text(cut(requireString(input, "text"), MAX_TEXT_LENGTH))
                .suggestions(suggestions(input)).build();
    }

    // --- Vorschlaege ------------------------------------------------------------------------

    /**
     * Uebernimmt nur Vorschlaege, die auf ein echtes Ziel zeigen und einen erlaubten Wert tragen; alles
     * andere faellt still weg — ein kaputter Vorschlag soll die Karte nicht kosten.
     */
    private List<Suggestion> suggestions(Map<String, Object> input) {
        if (!(input.get("suggestions") instanceof List<?> raw)) {
            return List.of();
        }
        List<Suggestion> result = new ArrayList<>();
        for (Object entry : raw) {
            if (result.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (entry instanceof Map<?, ?> map) {
                Suggestion suggestion = toSuggestion(map);
                if (suggestion != null) {
                    result.add(suggestion);
                }
            }
        }
        return result;
    }

    private Suggestion toSuggestion(Map<?, ?> map) {
        String label = text(map.get("label"));
        String kind = text(map.get("kind"));
        if (label == null || kind == null) {
            return null;
        }
        label = cut(label, MAX_LABEL_LENGTH);
        try {
            return switch (kind) {
                case CommunicatorDtos.SUGGEST_THERMOSTAT_TARGET -> thermostatSuggestion(label, map);
                case CommunicatorDtos.SUGGEST_SWITCH -> onOffSuggestion(label, kind, map,
                        findSwitch(text(map.get("entityId"))).isPresent());
                case CommunicatorDtos.SUGGEST_MODE -> onOffSuggestion(label, kind, map,
                        findMode(text(map.get("entityId"))).isPresent());
                case CommunicatorDtos.SUGGEST_ASK -> askSuggestion(label, map);
                default -> null;
            };
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Suggestion thermostatSuggestion(String label, Map<?, ?> map) {
        Long thermostatId = toLong(map.get("thermostatId"));
        Double value = toDouble(map.get("value"));
        if (thermostatId == null || value == null) {
            return null;
        }
        ThermostatTargetValidator.validate(value);
        boolean known = thermostatService.list().stream().anyMatch(t -> thermostatId.equals(t.id()));
        return known ? new Suggestion(label, CommunicatorDtos.SUGGEST_THERMOSTAT_TARGET, thermostatId, value,
                null, null, null) : null;
    }

    private static Suggestion onOffSuggestion(String label, String kind, Map<?, ?> map, boolean known) {
        String state = text(map.get("state"));
        if (!known || state == null || !ON_OFF.contains(state)) {
            return null;
        }
        return new Suggestion(label, kind, null, null, text(map.get("entityId")), state, null);
    }

    private static Suggestion askSuggestion(String label, Map<?, ?> map) {
        String question = text(map.get("question"));
        return question == null ? null
                : new Suggestion(label, CommunicatorDtos.SUGGEST_ASK, null, null, null, null,
                cut(question, MAX_QUESTION_LENGTH));
    }

    private Optional<SwitchResponse> findSwitch(String entityId) {
        return entityId == null ? Optional.empty()
                : switchQueryService.listSwitches(null).stream()
                .filter(s -> entityId.equals(s.entityId())).findFirst();
    }

    private Optional<ModeResponse> findMode(String entityId) {
        return entityId == null ? Optional.empty()
                : houseModeQueryService.listModes().stream()
                .filter(m -> entityId.equals(m.entityId())).findFirst();
    }

    // --- Schema und Eingaben ------------------------------------------------------------------

    private static Map<String, Object> suggestionsSchema() {
        return Map.of(
                "type", "array",
                "description", "2 bis 4 sinnvolle naechste Schritte als Knoepfe unter der Karte.",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "label", string("Knopftext, hoechstens drei Woerter, z. B. 'Auf 21°'"),
                                "kind", Map.of("type", "string", "enum", List.of(
                                        CommunicatorDtos.SUGGEST_THERMOSTAT_TARGET, CommunicatorDtos.SUGGEST_SWITCH,
                                        CommunicatorDtos.SUGGEST_MODE, CommunicatorDtos.SUGGEST_ASK)),
                                "thermostatId", Map.of("type", "integer",
                                        "description", "nur thermostat-target"),
                                "value", Map.of("type", "number",
                                        "description", "nur thermostat-target: Sollwert 5 bis 30 in 0,5-Schritten"),
                                "entityId", string("nur switch/mode: Entity-ID aus dem Hauszustand"),
                                "state", Map.of("type", "string", "enum", List.of("on", "off"),
                                        "description", "nur switch/mode: Zielzustand"),
                                "question", string("nur ask: Folgefrage, die der Knopf stellt")),
                        "required", List.of("label", "kind")));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties,
                                            List<String> required) {
        return Map.of("name", name, "description", description,
                "input_schema", Map.of("type", "object", "properties", properties, "required", required));
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static String title(Map<String, Object> input, String fallback) {
        String title = optionalString(input, "title");
        return cut(title != null ? title : Objects.requireNonNullElse(fallback, "Temperatur"), MAX_TITLE_LENGTH);
    }

    private static String requireString(Map<String, Object> input, String key) {
        String value = optionalString(input, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " fehlt");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> input, String key) {
        return text(input.get(key));
    }

    private static Long optionalLong(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        Long parsed = toLong(value);
        if (parsed == null) {
            throw new IllegalArgumentException(key + " ist keine Zahl");
        }
        return parsed;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
