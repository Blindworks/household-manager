package com.household.manager.agent.tools;

import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.dto.TimeValue;
import com.household.manager.service.SeriesRange;
import com.household.manager.service.TemperatureSeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Temperaturverlauf der letzten 24 Stunden als Text. Auf die jüngsten 48 Punkte gekappt, damit
 * eine lange Reihe nicht das Kontextbudget des Modells frisst; die neuesten Werte zählen am meisten.
 */
@Component
@RequiredArgsConstructor
public class GetTemperatureHistoryTool implements AgentTool {

    static final int MAX_POINTS = 48;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final TemperatureSeriesService temperatureSeriesService;

    @Override
    public String name() {
        return "get_temperature_history";
    }

    @Override
    public String description() {
        return "Temperaturverlauf eines Sensors der letzten 24 Stunden (höchstens 48 Punkte).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "sensorId", Map.of("type", "string", "description", "Sensor-Id aus dem Snapshot, z. B. zigbee:12")),
                "required", List.of("sensorId"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String sensorId = ActionInputs.required(input, "sensorId");
        TemperatureSensorSeries series = temperatureSeriesService.getSensorSeries(sensorId, SeriesRange.DAY);
        List<TimeValue> points = series.getTemperature();
        if (points == null || points.isEmpty()) {
            return "Keine Messwerte für " + series.getName() + " in den letzten 24 Stunden.";
        }
        List<TimeValue> newest = points.subList(Math.max(0, points.size() - MAX_POINTS), points.size());
        StringBuilder out = new StringBuilder(series.getName()).append(" (letzte 24 Stunden):\n");
        for (TimeValue p : newest) {
            out.append(p.getTime().format(HH_MM)).append(": ")
                    .append(String.format(Locale.GERMAN, "%.1f", p.getValue().doubleValue())).append(" °C\n");
        }
        return out.toString();
    }
}
