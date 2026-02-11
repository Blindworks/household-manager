package com.household.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.AirrohrReadingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Service to fetch current values from Airrohr sensor.
 */
@Service
@RequiredArgsConstructor
public class AirrohrService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${airrohr.url}")
    private String airrohrUrl;

    public AirrohrReadingResponse getCurrentReading() {
        String json = restTemplate.getForObject(airrohrUrl, String.class);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Airrohr returned an empty response.");
        }

        ParsedAirrohrData parsedData = parseResponse(json);
        LocalDateTime now = LocalDateTime.now();

        return AirrohrReadingResponse.builder()
                .readingTime(now)
                .softwareVersion(parsedData.softwareVersion())
                .ageSeconds(parsedData.ageSeconds())
                .sdsP1(parsedData.sdsP1())
                .sdsP2(parsedData.sdsP2())
                .build();
    }

    private ParsedAirrohrData parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String softwareVersion = root.path("software_version").asText(null);
            Long ageSeconds = parseLongOrNull(root.path("age").asText(null));

            JsonNode sensorValues = root.path("sensordatavalues");
            if (!sensorValues.isArray()) {
                throw new IllegalStateException("Airrohr response does not contain sensordatavalues array.");
            }

            BigDecimal sdsP1 = null;
            BigDecimal sdsP2 = null;

            for (JsonNode sensorValue : sensorValues) {
                String valueType = sensorValue.path("value_type").asText("");
                String value = sensorValue.path("value").asText(null);

                if ("SDS_P1".equals(valueType)) {
                    sdsP1 = parseDecimalOrNull(value);
                } else if ("SDS_P2".equals(valueType)) {
                    sdsP2 = parseDecimalOrNull(value);
                }
            }

            if (sdsP1 == null && sdsP2 == null) {
                throw new IllegalStateException("Airrohr response does not contain SDS_P1 or SDS_P2.");
            }

            return new ParsedAirrohrData(softwareVersion, ageSeconds, sdsP1, sdsP2);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Airrohr response.", ex);
        }
    }

    private BigDecimal parseDecimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value);
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.valueOf(value);
    }

    private record ParsedAirrohrData(
            String softwareVersion,
            Long ageSeconds,
            BigDecimal sdsP1,
            BigDecimal sdsP2
    ) {
    }
}
