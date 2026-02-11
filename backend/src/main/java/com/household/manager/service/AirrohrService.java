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

            return AirrohrReadingResponse.builder()
                    .readingTime(LocalDateTime.now())
                    .softwareVersion(softwareVersion)
                    .ageSeconds(ageSeconds)
                    .sdsP1(sdsP1)
                    .sdsP2(sdsP2)
                    .build();
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
}
