package com.household.manager.charging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnbwChargingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String fixture(String name) {
        try (var in = EnbwChargingClientTest.class.getResourceAsStream("/charging/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture nicht gefunden: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void httpClientIstAufHttp11Festgelegt() {
        assertThat(EnbwChargingClient.createHttpClient(5000).version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void parstDieAufgezeichneteUmkreisAntwort() {
        List<ChargingStation> stations = EnbwChargingClient.parseStations(mapper, fixture("enbw-area.json"));

        assertThat(stations).hasSize(3);
        ChargingStation first = stations.get(0);
        assertThat(first.name()).isNotBlank();
        assertThat(first.address()).isNotBlank();
        assertThat(first.total()).isGreaterThanOrEqualTo(first.free());
    }

    @Test
    void parstDieAufgezeichneteDetailAntwort() {
        ChargingStationDetails details = EnbwChargingClient.parseDetails(mapper, fixture("enbw-station.json"));

        assertThat(details.stationId()).isEqualTo("1156477");
        assertThat(details.chargePoints()).hasSize(12);
        assertThat(details.chargePoints()).allSatisfy(point -> assertThat(point.statusSince()).isNotNull());
    }

    @Test
    void parstDieAufgezeichneteGruppierteUmkreisAntwort() {
        EnbwChargingClient.AreaPage page = EnbwChargingClient.parseArea(mapper, fixture("enbw-area-grouped.json"));

        assertThat(page.stations()).hasSize(3);
        assertThat(page.groups()).hasSize(2);
    }

    @Test
    void parstUmkreisAntwortUndUeberspringtUnbrauchbareStationen() throws IOException {
        String json = "["
                + "{\"grouped\":false,\"stationId\":\"1\",\"operator\":\"EnBW\","
                + "\"lon\":8.0,\"maxPowerInKw\":150,\"numberOfChargePoints\":2,\"availableChargePoints\":1,"
                + "\"shortAddress\":\"Weg 1, 11111 Ort, DE\"},"
                + "{\"grouped\":false,\"stationId\":\"2\",\"operator\":\"EnBW\","
                + "\"lat\":49.0,\"lon\":9.0,\"maxPowerInKw\":50,\"numberOfChargePoints\":2,"
                + "\"availableChargePoints\":5,"
                + "\"shortAddress\":\"Weg 2, 22222 Ort, DE\"}"
                + "]";

        List<ChargingStation> stations = EnbwChargingClient.parseStations(mapper, json);

        assertThat(stations).hasSize(1);
        ChargingStation station = stations.get(0);
        assertThat(station.stationId()).isEqualTo("2");
        assertThat(station.total()).isEqualTo(2);
        assertThat(station.free()).isEqualTo(2);
    }

    @Test
    void parstDetailAntwortMitMaxLeistungUndFehlendenConnectors() throws IOException {
        String json = "{\"stationId\":\"DE*B*2\",\"chargePoints\":["
                + "{\"evseId\":\"DE*B*2*1\",\"status\":\"AVAILABLE\","
                + "\"state\":{\"updatedAt\":1700000000000,\"value\":\"AVAILABLE\"},\"connectors\":["
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
        assertThat(first.statusSince()).isEqualTo(java.time.Instant.ofEpochMilli(1700000000000L));

        ChargePoint second = details.chargePoints().get(1);
        assertThat(second.chargePointId()).isEqualTo("DE*B*2*2");
        assertThat(second.status()).isEqualTo(ChargePointStatus.OCCUPIED);
        assertThat(second.maxPowerKw()).isNull();
        assertThat(second.connector()).isNull();
        assertThat(second.statusSince()).isNull();
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
        String json = "[{\"grouped\":false,\"stationId\":\"DE*X*1\",\"operator\":\"Lidl - (DELDL)\","
                + "\"lat\":50.0,\"lon\":8.0,"
                + "\"maxPowerInKw\":150,\"numberOfChargePoints\":2,\"availableChargePoints\":1,"
                + "\"shortAddress\":\"Hauptstr. 1, 12345 Stadt, DE\"}]";

        ChargingStation station = EnbwChargingClient.parseStations(mapper, json).get(0);

        assertThat(station.name()).isEqualTo("Hauptstr. 1");
        assertThat(station.operator()).isEqualTo("Lidl");
        assertThat(station.address()).isEqualTo("Hauptstr. 1, 12345 Stadt, DE");
    }

    @Test
    void ohneAdresseFaelltDerNameAufDenBetreiberZurueck() throws IOException {
        String json = "[{\"grouped\":false,\"stationId\":\"DE*X*1\",\"operator\":\"Lidl - (DELDL)\","
                + "\"lat\":50.0,\"lon\":8.0,"
                + "\"maxPowerInKw\":150,\"numberOfChargePoints\":2,\"availableChargePoints\":1}]";

        ChargingStation station = EnbwChargingClient.parseStations(mapper, json).get(0);

        assertThat(station.name()).isEqualTo("Lidl");
        assertThat(station.address()).isNull();
    }

    @Test
    void sammelzeilenWerdenNieAlsStationGezaehlt() throws IOException {
        String json = "[{\"grouped\":true,\"operator\":\"*\",\"stationId\":null,\"lat\":50.0,\"lon\":8.0,"
                + "\"numberOfChargePoints\":4,\"availableChargePoints\":2,"
                + "\"viewPort\":{\"lowerLeftLat\":49.9,\"lowerLeftLon\":7.9,"
                + "\"upperRightLat\":50.1,\"upperRightLon\":8.1}}]";

        List<ChargingStation> stations = EnbwChargingClient.parseStations(mapper, json);

        assertThat(stations).isEmpty();
    }

    // --- Drill-down ---------------------------------------------------------------------

    /** Testdouble: ueberschreibt den HTTP-Abruf, zaehlt die Aufrufe und antwortet nach Muster. */
    private static class FakeClient extends EnbwChargingClient {

        interface Responder {
            String respond(String pathAndQuery, int callNumber);
        }

        private final Responder responder;
        private final List<String> queries = new java.util.ArrayList<>();

        FakeClient(Responder responder) {
            super(properties(), new ObjectMapper());
            this.responder = responder;
        }

        private static ChargingProperties properties() {
            ChargingProperties properties = new ChargingProperties();
            properties.setHttpTimeoutMs(1000);
            return properties;
        }

        @Override
        String fetch(String pathAndQuery) {
            queries.add(pathAndQuery);
            return responder.respond(pathAndQuery, queries.size());
        }
    }

    @Test
    void loestSammelzeilenPerDrillDownAufUndDedupliziertStationen() {
        String grouped = fixture("enbw-area-grouped.json");
        String plain = fixture("enbw-area.json");
        FakeClient client = new FakeClient((query, callNumber) -> callNumber == 1 ? grouped : plain);

        List<ChargingStation> stations = client.searchArea(new BoundingBox(48.7, 48.9, 8.9, 9.2), 50.0);

        // 3 Einzelstationen direkt aus der gruppierten Antwort + 3 aus der aufgeloesten Plain-Fixture,
        // die fuer BEIDE Sammelzeilen geliefert wird - dedupliziert auf 3 statt 6 fuer diesen Teil.
        assertThat(stations).extracting(ChargingStation::stationId).containsExactlyInAnyOrder(
                "868700", "1563087", "2161003", "1156477", "1157026", "1158388");
        assertThat(client.queries.get(0)).contains("minPower=50");
        assertThat(client.queries).hasSize(3);
    }

    @Test
    void bricht429SofortDurch() {
        FakeClient client = new FakeClient((query, callNumber) -> {
            throw new ChargingRateLimitException("429");
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.searchArea(new BoundingBox(48.7, 48.9, 8.9, 9.2), 0))
                .isInstanceOf(ChargingRateLimitException.class);
        assertThat(client.queries).hasSize(1);
    }

    @Test
    void anfragelimitStopptDasDrillDownBeiGenau150Anfragen() {
        // Die Wurzelantwort liefert weit mehr als 150 Sammelzeilen (nicht-degenerierte Boxen);
        // jede Folgeantwort liefert nichts mehr - so treibt allein die Breite (nicht die Tiefe)
        // die Anfragenzahl exakt bis ans Limit.
        String manyGroups = groupedResponseWith(200);
        FakeClient client = new FakeClient((query, callNumber) -> callNumber == 1 ? manyGroups : "[]");

        List<ChargingStation> stations = client.searchArea(new BoundingBox(48.0, 52.0, 6.0, 10.0), 0);

        assertThat(stations).isEmpty();
        assertThat(client.queries).hasSize(150);
    }

    private static String groupedResponseWith(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"grouped\":true,\"operator\":\"*\",\"stationId\":null,")
                    .append("\"lat\":50.0,\"lon\":8.0,\"numberOfChargePoints\":4,\"availableChargePoints\":2,")
                    .append("\"viewPort\":{\"lowerLeftLat\":49.0,\"lowerLeftLon\":8.0,")
                    .append("\"upperRightLat\":49.01,\"upperRightLon\":8.01}}");
        }
        return json.append(']').toString();
    }
}
