package com.household.manager.agent.tools;

import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import com.household.manager.calendar.CalendarEventService;
import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.dto.CalendarPersonView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Kommende Termine als Textzeilen; das Fenster wird hier gefiltert, weil der Service nur nach Anzahl fragt. */
@Component
@RequiredArgsConstructor
public class GetCalendarTool implements AgentTool {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 14;
    private static final int FETCH_LIMIT = 20;

    private final CalendarEventService calendarEventService;
    private final Clock clock;

    @Override
    public String name() {
        return "get_calendar";
    }

    @Override
    public String description() {
        return "Termine des Haushaltskalenders der nächsten Tage (heute eingeschlossen).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "days", Map.of("type", "integer", "minimum", MIN_DAYS, "maximum", MAX_DAYS,
                                "description", "Wie viele Tage voraus")),
                "required", List.of("days"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        long days = NoteInputs.integer(input, "days");
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new IllegalArgumentException("days muss zwischen " + MIN_DAYS + " und " + MAX_DAYS + " liegen.");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate end = today.plusDays(days);
        List<String> lines = calendarEventService.getUpcoming(FETCH_LIMIT).stream()
                .filter(o -> !o.getOccurrenceDate().isBefore(today) && o.getOccurrenceDate().isBefore(end))
                .map(GetCalendarTool::line)
                .toList();
        return lines.isEmpty() ? "Keine Termine in den nächsten " + days + " Tagen." : String.join("\n", lines);
    }

    private static String line(CalendarOccurrenceResponse o) {
        String when = o.isAllDay() || o.getStartTime() == null
                ? o.getOccurrenceDate() + " ganztägig"
                : o.getOccurrenceDate() + " " + o.getStartTime();
        List<CalendarPersonView> persons = o.getPersons();
        String who = persons == null || persons.isEmpty() ? ""
                : " (" + persons.stream().map(CalendarPersonView::displayName).collect(Collectors.joining(", ")) + ")";
        return when + " " + o.getTitle() + who;
    }
}
