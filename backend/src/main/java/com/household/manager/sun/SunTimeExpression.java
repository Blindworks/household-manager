package com.household.manager.sun;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein Zeitpunkt im Tagesverlauf — entweder fest ({@code HH:mm}) oder relativ zu
 * einem Sonnenereignis ({@code sunset-30}, {@code dawn}). Grammatik:
 *
 * <pre>
 * HH:mm  |  dawn | sunrise | sunset | dusk  [ +N | -N ]     (N Minuten, |N| ≤ 240)
 * </pre>
 *
 * Leerzeichen um den Ausdruck werden toleriert, innerhalb nicht ({@code sunset -30}
 * ist ein Fehler). Genutzt von {@code time-condition}.
 */
public sealed interface SunTimeExpression permits SunTimeExpression.Fixed, SunTimeExpression.Relative {

    Pattern RELATIVE = Pattern.compile("^(dawn|sunrise|sunset|dusk)([+-]\\d{1,4})?$");

    record Fixed(LocalTime time) implements SunTimeExpression {
        @Override
        public Optional<LocalTime> resolve(LocalDate date, SunTimesService sunTimes) {
            return Optional.of(time);
        }
    }

    record Relative(SunEvent event, int offsetMinutes) implements SunTimeExpression {
        /**
         * Ein Versatz ueber Mitternacht landet auf dem Zifferblatt: {@code dawn-240} im Juni ergibt
         * ca. 23:50, der Kalendertag wird verworfen — als Grenze im zyklischen {@code TimeWindow}
         * ist genau das gewollt.
         */
        @Override
        public Optional<LocalTime> resolve(LocalDate date, SunTimesService sunTimes) {
            return sunTimes.timesFor(date)
                    .map(times -> times.timeOf(event).plusMinutes(offsetMinutes).toLocalTime());
        }
    }

    /** Die Uhrzeit dieses Ausdrucks am Tag {@code date}; leer, wenn keine Sonnenzeiten vorliegen. */
    Optional<LocalTime> resolve(LocalDate date, SunTimesService sunTimes);

    /** @throws IllegalArgumentException mit lesbarem Text, wenn der Ausdruck nicht der Grammatik entspricht */
    static SunTimeExpression parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Zeitausdruck fehlt");
        }
        String text = raw.trim();
        Matcher m = RELATIVE.matcher(text.toLowerCase(Locale.ROOT));
        if (m.matches()) {
            SunEvent event = SunEvent.fromKey(m.group(1)).orElseThrow();
            int offset = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (Math.abs(offset) > SunEvent.MAX_OFFSET_MINUTES) {
                throw new IllegalArgumentException("Versatz muss zwischen -" + SunEvent.MAX_OFFSET_MINUTES
                        + " und " + SunEvent.MAX_OFFSET_MINUTES + " Minuten liegen: '" + text + "'");
            }
            return new Relative(event, offset);
        }
        try {
            return new Fixed(LocalTime.parse(text));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("'" + text + "' ist weder eine Uhrzeit HH:mm noch "
                    + "dawn/sunrise/sunset/dusk mit optionalem Versatz (z. B. sunset-30)");
        }
    }
}
