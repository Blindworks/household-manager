package com.household.manager.agent;

import com.household.manager.calendar.CalendarEventService;
import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.dto.CalendarPersonView;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.HouseModes;
import com.household.manager.entitystate.PowerConsumerQueryService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.model.entity.EntityState;
import com.household.manager.network.NetworkDtos;
import com.household.manager.network.NetworkStatusService;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.petsupply.PetSupplyService;
import com.household.manager.presence.PresenceDtos;
import com.household.manager.presence.PresenceStatusService;
import com.household.manager.service.TemperatureSeriesService;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import com.household.manager.zigbee.service.ZigbeeStreamMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Baut den kompakten Hauszustand, der dem Hausverstand im Systemprompt mitgegeben wird.
 * <p>
 * Der Snapshot liest ausschliesslich bestehende Abfrage-Services und schreibt nichts. Jeder
 * Abschnitt ist einzeln abgesichert: faellt eine Quelle aus, steht dort "nicht abrufbar" und
 * der Rest bleibt erhalten — ein halber Zustand ist besser als gar keiner, und der Agent soll
 * die Luecke sehen statt einen geratenen Wert. Ein ausgefallener Sensor wird nie als
 * "geschlossen"/"aus" dargestellt, sondern als "unbekannt".
 * Die Gesamtlaenge ist gedeckelt, damit ein ausufernder Abschnitt (Notizen, Vorschlaege) den
 * Prompt nicht sprengt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HouseSnapshotBuilder {

    static final int MAX_LENGTH = 12_000;
    private static final String TRUNCATION_MARK = "\n… [gekürzt]";
    private static final long STALE_MINUTES = 60;
    private static final int TOP_CONSUMERS = 10;
    private static final int UPCOMING_EVENTS = 5;
    private static final String SUN_ENTITY_ID = "sensor.sun";
    private static final String INDENT = "  ";
    private static final String NO_DATA = "keine Daten";

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy HH:mm", Locale.GERMAN);
    private static final DateTimeFormatter EVENT_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMAN);
    private static final DateTimeFormatter EVENT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN);

    private final Clock clock;
    private final PresenceStatusService presenceStatusService;
    private final HouseModeQueryService houseModeQueryService;
    private final ThermostatService thermostatService;
    private final TemperatureSeriesService temperatureSeriesService;
    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper entityMapper;
    private final PowerConsumerQueryService powerConsumerQueryService;
    private final PetSupplyService petSupplyService;
    private final CalendarEventService calendarEventService;
    private final NetworkStatusService networkStatusService;
    private final ZigbeeStreamMonitor zigbeeStreamMonitor;
    private final NukiLockService nukiLockService;
    private final AgentProposalService proposalService;
    private final AgentNoteService noteService;
    private final AgentPolicyService policyService;

    /** Wirft nie: jeder Abschnitt faengt seine eigenen Fehler. */
    public String build() {
        List<String> sections = List.of(
                section("Zeit", this::timeSection),
                section("Anwesenheit", this::presenceSection),
                section("Modi", this::modesSection),
                section("Heizung", this::heatingSection),
                section("Temperaturen", this::temperatureSection),
                section("Kontakte offen", "keine", this::contactsSection),
                section("Verbraucher", this::consumersSection),
                section("Helfer", this::helpersSection),
                section("Vorräte", this::suppliesSection),
                section("Termine", this::eventsSection),
                section("Netzwerk", this::networkSection),
                section("Zigbee", this::zigbeeSection),
                section("Haustür", this::locksSection),
                section("Offene und letzte Vorschläge (24 h)", "keine", this::proposalsSection),
                section("Freigaben", this::policiesSection),
                section("Notizen", "keine", this::notesSection));
        String snapshot = String.join("\n\n", sections);
        return snapshot.length() <= MAX_LENGTH
                ? snapshot
                : snapshot.substring(0, MAX_LENGTH) + TRUNCATION_MARK;
    }

    private String section(String title, Supplier<String> body) {
        return section(title, NO_DATA, body);
    }

    private String section(String title, String emptyText, Supplier<String> body) {
        try {
            String text = body.get();
            if (text == null || text.isBlank()) {
                return title + ": " + emptyText;
            }
            String indented = text.lines().map(line -> INDENT + line).collect(Collectors.joining("\n"));
            return title + ":\n" + indented;
        } catch (Exception ex) {
            log.warn("Snapshot-Abschnitt '{}' nicht abrufbar: {}", title, ex.toString());
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return title + ": nicht abrufbar (" + reason + ")";
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // --- Abschnitte ---------------------------------------------------------------------

    private String timeSection() {
        String line = now().format(DATE_TIME);
        EntityState sun = entityStateService.getByEntityId(SUN_ENTITY_ID).orElse(null);
        if (sun == null || sun.getState() == null) {
            return line;
        }
        Map<String, Object> attributes = entityMapper.parseAttributes(sun.getAttributes());
        String sunset = String.valueOf(attributes.getOrDefault("sunset", ""));
        String sunrise = String.valueOf(attributes.getOrDefault("sunrise", ""));
        StringBuilder phase = new StringBuilder(line).append(", Sonnenphase: ").append(sun.getState());
        if (!sunset.isBlank() && !sunrise.isBlank()) {
            phase.append(" (sonnenaufgang ").append(sunrise).append(", sonnenuntergang ").append(sunset).append(')');
        }
        return phase.toString();
    }

    private String presenceSection() {
        PresenceDtos.StatusResponse status = presenceStatusService.getStatus();
        List<String> lines = new ArrayList<>();
        for (PresenceDtos.PersonStatus person : status.persons()) {
            lines.add(person.displayName() + ": " + presenceText(person));
        }
        if (lines.isEmpty()) {
            return null;
        }
        lines.add("Haushalt gesamt: " + householdText(status.householdState()));
        return String.join("\n", lines);
    }

    private String presenceText(PresenceDtos.PersonStatus person) {
        String seen = person.lastSeenAt() == null ? null : formatTimeOrDate(person.lastSeenAt());
        return switch (person.state() == null ? "unknown" : person.state()) {
            case "on" -> "zu Hause" + (seen != null ? " (zuletzt " + seen + ")" : "");
            case "off" -> "abwesend" + (seen != null ? " seit " + seen : "");
            default -> "unbekannt";
        };
    }

    private static String householdText(String state) {
        return switch (state == null ? "unknown" : state) {
            case "on" -> "jemand zu Hause";
            case "off" -> "niemand zu Hause";
            default -> "unbekannt";
        };
    }

    /** Heutige Zeitpunkte nur als Uhrzeit, aeltere mit Datum — "seit 13:40" waere sonst mehrdeutig. */
    private String formatTimeOrDate(LocalDateTime time) {
        return time.toLocalDate().equals(LocalDate.now(clock))
                ? time.format(TIME)
                : time.format(EVENT_DATE_TIME);
    }

    private String modesSection() {
        return houseModeQueryService.listModes().stream()
                .map(mode -> mode.displayName() + ": " + onOff(mode.state()))
                .collect(Collectors.joining(", "));
    }

    private static String onOff(String state) {
        return switch (state == null ? "" : state) {
            case "on" -> "an";
            case "off" -> "aus";
            default -> state == null || state.isBlank() ? "unbekannt" : state;
        };
    }

    private String heatingSection() {
        return thermostatService.list().stream().map(this::thermostatLine).collect(Collectors.joining("\n"));
    }

    private String thermostatLine(ThermostatDto t) {
        String head = t.name() + " (id " + t.id() + "): ";
        if (!t.online()) {
            return head + "offline";
        }
        return head + "ist " + degrees(t.currentTemp()) + ", soll " + degrees(t.targetTemp()) + ", " + t.mode()
                + ", " + (t.heating() ? "heizt" : "heizt nicht")
                + (t.batteryLow() ? ", Batterie schwach" : "")
                + ", Stand " + (t.lastUpdated() == null ? "unbekannt" : t.lastUpdated().format(TIME));
    }

    private static String degrees(Double value) {
        return value == null ? "?" : String.format(Locale.GERMAN, "%.1f °C", value);
    }

    private String temperatureSection() {
        return temperatureSeriesService.getCurrent().stream().map(this::temperatureLine)
                .collect(Collectors.joining("\n"));
    }

    private String temperatureLine(CurrentTemperatureReading r) {
        StringBuilder line = new StringBuilder(r.getName()).append(" (sensorId ").append(r.getSensorId()).append("): ")
                .append(r.getTemperature() == null ? "?"
                        : String.format(Locale.GERMAN, "%.1f °C", r.getTemperature().doubleValue()));
        if (r.getHumidity() != null) {
            line.append(String.format(Locale.GERMAN, " / %.0f %%", r.getHumidity().doubleValue()));
        }
        if (r.getMeasuredAt() == null) {
            return line.append(", Messzeit unbekannt").toString();
        }
        long ageMinutes = Math.max(0, Duration.between(r.getMeasuredAt(), now()).toMinutes());
        line.append(", vor ").append(ageMinutes).append(" min");
        if (ageMinutes > STALE_MINUTES) {
            line.append(" VERALTET");
        }
        return line.toString();
    }

    private String contactsSection() {
        List<String> lines = new ArrayList<>();
        for (EntityState contact : entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.ZIGBEE)) {
            if (!"door".equals(entityMapper.parseAttributes(contact.getAttributes()).get("deviceClass"))) {
                continue;
            }
            if ("on".equals(contact.getState())) {
                lines.add(displayName(contact));
            } else if ("unavailable".equals(contact.getState())) {
                lines.add(displayName(contact) + ": unbekannt");
            }
        }
        return String.join("\n", lines);
    }

    private static String displayName(EntityState entity) {
        if (entity.getCustomName() != null && !entity.getCustomName().isBlank()) {
            return entity.getCustomName();
        }
        if (entity.getFriendlyName() != null && !entity.getFriendlyName().isBlank()) {
            return entity.getFriendlyName();
        }
        return entity.getEntityId();
    }

    private String consumersSection() {
        return powerConsumerQueryService.listConsumers(TOP_CONSUMERS).stream()
                .map(c -> c.displayName() + ": " + (c.unavailable() || c.powerWatts() == null
                        ? "nicht erreichbar"
                        : c.powerWatts().setScale(0, RoundingMode.HALF_UP).toPlainString() + " W"))
                .collect(Collectors.joining("\n"));
    }

    private String helpersSection() {
        List<String> lines = new ArrayList<>();
        for (EntityState helper : entityStateService.find(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)) {
            if (HouseModes.isMode(entityMapper.parseAttributes(helper.getAttributes()))) {
                continue;
            }
            lines.add(displayName(helper) + ": " + onOff(helper.getState())
                    + (helper.getLastChanged() == null ? "" : " (seit " + formatTimeOrDate(helper.getLastChanged()) + ")"));
        }
        return String.join("\n", lines);
    }

    private String suppliesSection() {
        return petSupplyService.getSupplies().stream()
                .map(s -> s.name() + ": " + plain(s.amountRemaining()) + " " + s.unit()
                        + ", reicht " + s.daysRemaining() + " Tage")
                .collect(Collectors.joining("\n"));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "?" : value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private String eventsSection() {
        return calendarEventService.getUpcoming(UPCOMING_EVENTS).stream().map(this::eventLine)
                .collect(Collectors.joining("\n"));
    }

    private String eventLine(CalendarOccurrenceResponse e) {
        String when = e.getStartTime() != null && !e.isAllDay()
                ? e.getOccurrenceDate().atTime(e.getStartTime()).format(EVENT_DATE_TIME)
                : e.getOccurrenceDate().format(EVENT_DATE) + " ganztägig";
        String persons = e.getPersons() == null || e.getPersons().isEmpty() ? ""
                : " (" + e.getPersons().stream().map(CalendarPersonView::displayName)
                .collect(Collectors.joining(", ")) + ")";
        return when + " " + e.getTitle() + persons;
    }

    private String networkSection() {
        NetworkDtos.StatusResponse status = networkStatusService.getStatus();
        if (status.lastCheckedAt() == null) {
            return "noch keine Messung";
        }
        if (!status.online()) {
            return "offline" + (status.gatewayReachable() ? " (Router erreichbar)" : " (Router nicht erreichbar)");
        }
        return "online" + (status.latencyMs() != null ? ", Latenz " + status.latencyMs() + " ms" : "")
                + (status.gatewayReachable() ? ", Router erreichbar" : ", Router nicht erreichbar");
    }

    private String zigbeeSection() {
        ZigbeeStreamStatus status = zigbeeStreamMonitor.status();
        String line = status.health().name();
        if (status.offlineDevices() != null && !status.offlineDevices().isEmpty()) {
            line += ", offline: " + String.join(", ", status.offlineDevices());
        }
        return line;
    }

    private String locksSection() {
        return nukiLockService.listLocks().stream()
                .map(l -> l.name() + ": " + l.state()
                        + (l.doorState() == null ? "" : ", Tür " + l.doorState())
                        + (l.batteryCritical() ? ", Batterie kritisch" : ""))
                .collect(Collectors.joining("\n"));
    }

    private String proposalsSection() {
        return proposalService.recentForPrompt().stream().map(this::proposalLine).collect(Collectors.joining("\n"));
    }

    private String proposalLine(AgentProposal p) {
        String description;
        try {
            description = proposalService.actionOf(p).describe();
        } catch (Exception ex) {
            description = p.getActionClass() + " " + p.getTarget();
        }
        return "#" + p.getId() + " " + p.getStatus() + " " + description + " — " + p.getReason();
    }

    private String policiesSection() {
        return new TreeMap<>(policyService.all()).entrySet().stream()
                .map(e -> e.getKey() + ": " + (e.getValue() == ActionPolicy.PROPOSE ? "nur vorschlagen" : "direkt"))
                .collect(Collectors.joining("; "));
    }

    private String notesSection() {
        return noteService.renderForPrompt();
    }
}
