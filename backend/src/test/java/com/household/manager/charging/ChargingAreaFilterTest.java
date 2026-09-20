package com.household.manager.charging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChargingAreaFilterTest {

    private static ChargingStation station(String id, double lat, double lon, Double kw) {
        return new ChargingStation(id, id, "Op", null, lat, lon, kw, 2, 1);
    }

    @Test
    void behaeltNurStationenImKreisUndUeberDerMindestleistung() {
        // 0.09° Breite ≈ 10 km; die Ecke des Rechtecks liegt ~14 km entfernt und faellt raus.
        List<ChargingStation> input = List.of(
                station("nah", 50.01, 8.0, 150.0),
                station("ecke", 50.09, 8.14, 150.0),
                station("schwach", 50.0, 8.01, 22.0),
                station("ohneLeistung", 50.0, 8.02, null));

        List<ChargingStation> result = ChargingAreaFilter.filter(input, 50.0, 8.0, 10.0, 50.0);

        assertThat(result).extracting(ChargingStation::stationId).containsExactly("nah");
    }

    @Test
    void mindestleistungNullLaesstStationenOhneLeistungsangabeDurch() {
        List<ChargingStation> result = ChargingAreaFilter.filter(
                List.of(station("ohneLeistung", 50.0, 8.0, null)), 50.0, 8.0, 10.0, 0.0);

        assertThat(result).hasSize(1);
    }

    @Test
    void distanzIstHaversine() {
        assertThat(ChargingAreaFilter.distanceMeters(50.0, 8.0, 50.0, 8.0)).isEqualTo(0.0);
        assertThat(ChargingAreaFilter.distanceMeters(50.0, 8.0, 50.009, 8.0)).isBetween(990.0, 1010.0);
    }
}
