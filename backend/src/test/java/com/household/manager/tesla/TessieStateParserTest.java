package com.household.manager.tesla;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Fixture ist nach der Tessie-Dokumentation gebaut, keine aufgezeichnete Antwort
 * (kein Tessie-Zugang beim Bau).
 */
class TessieStateParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void liestAkkuReichweiteUndLadezustand() throws Exception {
        TeslaVehicleSnapshot snapshot = TessieStateParser.parse(fixture(), "FALLBACK");
        assertThat(snapshot.optionCodes()).isEqualTo("AD15,MDL3,PPSW,W40B");
        assertThat(snapshot.wheelType()).isEqualTo("Pinwheel18");

        assertThat(snapshot.vin()).isEqualTo("5YJ3E7EB2NF000001");
        assertThat(snapshot.displayName()).isEqualTo("Blitz");
        assertThat(snapshot.batteryLevel()).isEqualTo(55);
        assertThat(snapshot.rangeKm()).isEqualTo(275.7);
        assertThat(snapshot.chargingState()).isEqualTo("Charging");
        assertThat(snapshot.isCharging()).isTrue();
        assertThat(snapshot.pluggedIn()).isTrue();
        assertThat(snapshot.chargePortOpen()).isTrue();
        assertThat(snapshot.locked()).isTrue();
        assertThat(snapshot.climateOn()).isFalse();
        assertThat(snapshot.defrostOn()).isFalse();
        assertThat(snapshot.shiftState()).isNull();
        assertThat(snapshot.measuredAt()).isEqualTo(Instant.ofEpochMilli(1764228000000L));
    }

    @Test
    void geplantesLadenNurWennEsAnsteht() throws Exception {
        JsonNode state = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("charge_state"))
                .put("scheduled_charging_start_time", 1764291600L)
                .put("scheduled_charging_pending", true);

        TeslaVehicleSnapshot snapshot = TessieStateParser.parse(state, "FALLBACK");

        assertThat(snapshot.scheduledChargeAt()).isEqualTo(Instant.ofEpochSecond(1764291600L));
    }

    @Test
    void fehlendeFelderBleibenNullStattNull() throws Exception {
        TeslaVehicleSnapshot snapshot = TessieStateParser.parse(mapper.readTree("{}"), "VIN1");

        assertThat(snapshot.vin()).isEqualTo("VIN1");
        assertThat(snapshot.batteryLevel()).isNull();
        assertThat(snapshot.rangeKm()).isNull();
        assertThat(snapshot.pluggedIn()).isNull();
        assertThat(snapshot.defrostOn()).isNull();
        assertThat(snapshot.scheduledChargeAt()).isNull();
    }

    @Test
    void maxDefrostZaehltAlsEnteisen() throws Exception {
        JsonNode state = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("climate_state")).put("defrost_mode", 2);

        assertThat(TessieStateParser.parse(state, "X").defrostOn()).isTrue();
    }

    @Test
    void abgestecktIstNichtEingesteckt() throws Exception {
        JsonNode state = fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("charge_state"))
                .put("charging_state", "Disconnected");

        TeslaVehicleSnapshot snapshot = TessieStateParser.parse(state, "X");

        assertThat(snapshot.pluggedIn()).isFalse();
        assertThat(snapshot.isCharging()).isFalse();
    }

    private JsonNode fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tesla/state.json")) {
            return mapper.readTree(in);
        }
    }
}
