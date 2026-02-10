package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * DTO for Tasmota Status 10 response.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TasmotaStatusResponse {

    @JsonProperty("StatusSNS")
    private StatusSNS statusSNS;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatusSNS {

        @JsonProperty("Time")
        private String time;

        private final Map<String, Telemetry> telemetry = new HashMap<>();

        @JsonAnySetter
        public void addTelemetry(String key, Telemetry value) {
            telemetry.put(key, value);
        }
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Telemetry {

        @JsonProperty("pos_wirk_tariflos")
        private BigDecimal posWirkenergieTariflos;

        @JsonProperty("momentanwirkleistung")
        private BigDecimal momentaneWirkleistung;
    }
}
