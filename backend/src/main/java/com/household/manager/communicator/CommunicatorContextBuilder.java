package com.household.manager.communicator;

import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.service.TemperatureSeriesService;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Kompakter Hauszustand fuer den Systemprompt des Communicators: alles, was eine Karte braucht, samt IDs.
 * Damit kann das Modell die meisten Fragen in EINEM Aufruf beantworten, statt erst Listen abzufragen —
 * bei einer Sprach-App zaehlt jede Sekunde. Wirft nie; eine ausgefallene Quelle steht als "nicht abrufbar" da.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommunicatorContextBuilder {

    static final int MAX_LENGTH = 8_000;
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy HH:mm", Locale.GERMAN);

    private final Clock clock;
    private final ThermostatService thermostatService;
    private final TemperatureSeriesService temperatureSeriesService;
    private final SwitchQueryService switchQueryService;
    private final HouseModeQueryService houseModeQueryService;
    private final NukiLockService nukiLockService;

    public String build() {
        String context = String.join("\n\n", List.of(
                "Zeit: " + LocalDateTime.now(clock).format(DATE_TIME),
                section("Thermostate", this::thermostats),
                section("Temperaturfühler", this::temperatures),
                section("Schalter", this::switches),
                section("Modi", this::modes),
                section("Haustür", this::locks)));
        return context.length() <= MAX_LENGTH ? context : context.substring(0, MAX_LENGTH) + "\n… [gekürzt]";
    }

    private String section(String title, Supplier<String> body) {
        try {
            String text = body.get();
            return title + ":\n" + (text == null || text.isBlank() ? "  keine" : text);
        } catch (Exception ex) {
            log.warn("Communicator-Kontext '{}' nicht abrufbar: {}", title, ex.toString());
            return title + ": nicht abrufbar";
        }
    }

    private String thermostats() {
        return thermostatService.list().stream().map(this::thermostatLine).collect(Collectors.joining("\n"));
    }

    private String thermostatLine(ThermostatDto t) {
        String head = "  " + t.name() + " (thermostatId " + t.id()
                + (t.roomSensorId() == null ? "" : ", Raumfühler sensorId " + t.roomSensorId()) + "): ";
        return t.online()
                ? head + "ist " + degrees(t.currentTemp()) + ", soll " + degrees(t.targetTemp()) + ", " + t.mode()
                : head + "offline";
    }

    private String temperatures() {
        return temperatureSeriesService.getCurrent().stream().map(this::temperatureLine)
                .collect(Collectors.joining("\n"));
    }

    private String temperatureLine(CurrentTemperatureReading r) {
        String humidity = r.getHumidity() == null ? ""
                : String.format(Locale.GERMAN, " / %.0f %%", r.getHumidity().doubleValue());
        return "  " + r.getName() + " (sensorId " + r.getSensorId() + "): "
                + degrees(r.getTemperature() == null ? null : r.getTemperature().doubleValue()) + humidity;
    }

    private String switches() {
        return switchQueryService.listSwitches(null).stream()
                .map(s -> "  " + s.displayName() + " (" + s.entityId() + "): " + onOff(s.state()))
                .collect(Collectors.joining("\n"));
    }

    private String modes() {
        return houseModeQueryService.listModes().stream()
                .map(m -> "  " + m.displayName() + " (" + m.entityId() + "): " + onOff(m.state()))
                .collect(Collectors.joining("\n"));
    }

    private String locks() {
        return nukiLockService.listLocks().stream()
                .map(l -> "  " + l.name() + ": " + l.state() + (l.doorState() == null ? "" : ", Tür " + l.doorState()))
                .collect(Collectors.joining("\n"));
    }

    private static String degrees(Double value) {
        return value == null ? "?" : String.format(Locale.GERMAN, "%.1f °C", value);
    }

    /** Alles ausser on/off ist "unbekannt" — nie als "aus" darstellen. */
    private static String onOff(String state) {
        return switch (state == null ? "" : state) {
            case "on" -> "an";
            case "off" -> "aus";
            default -> "unbekannt";
        };
    }
}
