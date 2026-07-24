package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.MeterReadingResponse;
import com.household.manager.model.entity.MeterType;
import com.household.manager.service.MeterReadingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Letzte Zählerstände je Zählertyp (Strom, Gas, Wasser). */
@Component
@RequiredArgsConstructor
public class GetMeterReadingsTool implements AgentTool {

    private final MeterReadingService meterReadingService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "get_meter_readings";
    }

    @Override
    public String description() {
        return "Liefert den letzten Zaehlerstand je Zaehlertyp (ELECTRICITY, GAS, WATER).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        List<Map<String, Object>> readings = new ArrayList<>();
        for (MeterType type : MeterType.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("meterType", type.name());
            try {
                MeterReadingResponse latest = meterReadingService.getLatestReading(type);
                entry.put("value", latest.getReadingValue());
                entry.put("date", String.valueOf(latest.getReadingDate()));
            } catch (Exception ex) {
                entry.put("value", null);
                entry.put("info", "keine Daten");
            }
            readings.add(entry);
        }
        return json.writeValueAsString(readings);
    }
}
