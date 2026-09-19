package com.household.manager.charging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die fixture-basierten Tests (aufgezeichnete Umkreis-/Detailantwort) kommen erst dazu, sobald
 * {@code scripts/probe-enbw-charging.sh} echte EnBW-Antworten aufgezeichnet hat (Task 1, noch
 * offen). Bis dahin uebt Inline-JSON dieselben Parser end-to-end.
 */
class EnbwChargingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void httpClientIstAufHttp11Festgelegt() {
        assertThat(EnbwChargingClient.createHttpClient(5000).version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void parstUmkreisAntwortUndUeberspringtUnbrauchbareStationen() throws IOException {
        String json = "["
                + "{\"stationId\":\"DE*A*1\",\"stationName\":\"Station A\",\"operator\":\"EnBW\","
                + "\"lon\":8.0,\"maxPowerInKw\":150,\"numberOfChargePoints\":2,\"availableChargePoints\":1,"
                + "\"address\":{\"street\":\"Weg 1\",\"postalCode\":\"11111\",\"city\":\"Ort\"}},"
                + "{\"stationId\":\"DE*B*2\",\"stationName\":\"Station B\",\"operator\":\"EnBW\","
                + "\"lat\":49.0,\"lon\":9.0,\"maxPowerInKw\":50,\"numberOfChargePoints\":2,"
                + "\"availableChargePoints\":5,"
                + "\"address\":{\"street\":\"Weg 2\",\"postalCode\":\"22222\",\"city\":\"Ort\"}}"
                + "]";

        List<ChargingStation> stations = EnbwChargingClient.parseStations(mapper, json);

        assertThat(stations).hasSize(1);
        ChargingStation station = stations.get(0);
        assertThat(station.stationId()).isEqualTo("DE*B*2");
        assertThat(station.total()).isEqualTo(2);
        assertThat(station.free()).isEqualTo(2);
    }

    @Test
    void parstDetailAntwortMitMaxLeistungUndFehlendenConnectors() throws IOException {
        String json = "{\"stationId\":\"DE*B*2\",\"chargePoints\":["
                + "{\"evseId\":\"DE*B*2*1\",\"status\":\"AVAILABLE\",\"connectors\":["
                + "{\"plugTypeName\":\"Typ2\",\"maxPowerInKw\":22},"
                + "{\"plugTypeName\":\"CCS\",\"maxPowerInKw\":150}]},"
                + "{\"evseId\":\"DE*B*2*2\",\"status\":\"OCCUPIED\"}"
                + "]}";

        ChargingStationDetails details = EnbwChargingClient.parseDetails(mapper, json);

        assertThat(details.stationId()).isEqualTo("DE*B*2");
        assertThat(details.chargePoints()).hasSize(2);

        ChargePoint first = details.chargePoints().get(0);
        assertThat(first.chargePointId()).isEqualTo("DE*B*2*1");
        assertThat(first.status()).isEqualTo(ChargePointStatus.FREE);
        assertThat(first.maxPowerKw()).isEqualTo(150.0);
        assertThat(first.connector()).isEqualTo("Typ2");

        ChargePoint second = details.chargePoints().get(1);
        assertThat(second.chargePointId()).isEqualTo("DE*B*2*2");
        assertThat(second.status()).isEqualTo(ChargePointStatus.OCCUPIED);
        assertThat(second.maxPowerKw()).isNull();
        assertThat(second.connector()).isNull();
    }

    @Test
    void unbekannterStatusWirdUnknownNieFree() {
        assertThat(EnbwChargingClient.mapStatus("AVAILABLE")).isEqualTo(ChargePointStatus.FREE);
        assertThat(EnbwChargingClient.mapStatus("OCCUPIED")).isEqualTo(ChargePointStatus.OCCUPIED);
        assertThat(EnbwChargingClient.mapStatus("OUT_OF_SERVICE")).isEqualTo(ChargePointStatus.OUT_OF_SERVICE);
        assertThat(EnbwChargingClient.mapStatus("RESERVED")).isEqualTo(ChargePointStatus.UNKNOWN);
        assertThat(EnbwChargingClient.mapStatus(null)).isEqualTo(ChargePointStatus.UNKNOWN);
    }

    @Test
    void stationOhneNamenBekommtBetreiberUndStrasse() throws IOException {
        String json = "[{\"stationId\":\"DE*X*1\",\"operator\":\"Lidl\",\"lat\":50.0,\"lon\":8.0,"
                + "\"maxPowerInKw\":150,\"numberOfChargePoints\":2,\"availableChargePoints\":1,"
                + "\"address\":{\"street\":\"Hauptstr. 1\",\"postalCode\":\"12345\",\"city\":\"Stadt\"}}]";

        ChargingStation station = EnbwChargingClient.parseStations(mapper, json).get(0);

        assertThat(station.name()).isEqualTo("Lidl Hauptstr. 1");
        assertThat(station.address()).isEqualTo("Hauptstr. 1, 12345 Stadt");
    }
}
