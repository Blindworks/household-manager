package com.household.manager.entitystate.mapper;

import com.household.manager.charging.ChargePoint;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import org.junit.jupiter.api.Test;

import com.household.manager.charging.forecast.ChargingForecast;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChargingEntityMapperTest {

    private final ChargingEntityMapper mapper = new ChargingEntityMapper(
            Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneId.of("Europe/Berlin")));
    private final ChargingFavorite favorite = ChargingFavorite.builder()
            .stationId("DE*LDL*E123").displayName("Lidl Hauptstr.").operator("Lidl")
            .lat(50).lon(8).createdAt(Instant.EPOCH).build();

    @Test
    void bildetFreieLadepunkteAlsStateUndDenAeltestenBeginnAlsAttributAb() {
        Instant since = Instant.parse("2026-09-19T09:00:00Z");
        ChargingStationDetails details = new ChargingStationDetails("DE*LDL*E123", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, 150.0, "CCS", null, 0.34),
                new ChargePoint("P2", ChargePointStatus.FREE, 150.0, "CCS", null, 0.39)));
        Map<String, ChargingPointOccupancy> occupancy = Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("DE*LDL*E123").occupiedSince(since).firstSeenOccupiedAt(since).build());

        EntityStateUpdate update = mapper.map(favorite, details, occupancy, Optional.empty());

        assertThat(update.entityId()).isEqualTo("sensor.charging_de_ldl_e123_free");
        assertThat(update.domain()).isEqualTo(EntityDomain.SENSOR);
        assertThat(update.source()).isEqualTo(EntitySource.CHARGING);
        assertThat(update.state()).isEqualTo("1");
        assertThat(update.attributes()).containsEntry("total", 2)
                .containsEntry("maxPowerKw", 150.0)
                .containsEntry("pricePerKwh", 0.34)
                .containsEntry("stationName", "Lidl Hauptstr.")
                .containsEntry("operator", "Lidl")
                .containsEntry("occupiedSince", since.toString())
                .doesNotContainKey("deviceClass");
    }

    @Test
    void ohneBekanntenBeginnFehltDerSchluesselOccupiedSince() {
        ChargingStationDetails details = new ChargingStationDetails("DE*LDL*E123", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, null, null, null, null)));
        Map<String, ChargingPointOccupancy> occupancy = Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("DE*LDL*E123").occupiedSince(null)
                .firstSeenOccupiedAt(Instant.EPOCH).build());

        EntityStateUpdate update = mapper.map(favorite, details, occupancy, Optional.empty());

        assertThat(update.state()).isEqualTo("0");
        assertThat(update.attributes()).doesNotContainKey("occupiedSince").doesNotContainKey("maxPowerKw")
                .doesNotContainKey("pricePerKwh");
    }

    @Test
    void entityIdIstAusDerStationIdAbleitbar() {
        assertThat(ChargingEntityMapper.entityId("DE*LDL*E123")).isEqualTo("sensor.charging_de_ldl_e123_free");
    }

    private static final ChargingStationDetails ONE_OCCUPIED = new ChargingStationDetails("DE*LDL*E123", List.of(
            new ChargePoint("P1", ChargePointStatus.OCCUPIED, 150.0, "CCS", null, null)));

    @Test
    void okSetztAlleAttribute() {
        ChargingForecast forecast = new ChargingForecast(ChargingForecast.Status.OK, 20,
                Map.of(15, 30, 30, 55, 60, 80), List.of(),
                Instant.parse("2026-11-02T10:25:30Z"), Instant.parse("2026-11-02T10:45:59Z"), null);

        EntityStateUpdate update = mapper.map(favorite, ONE_OCCUPIED, Map.of(), Optional.of(forecast));

        assertThat(update.attributes()).containsEntry("forecastStatus", "ok")
                .containsEntry("freeProbability15min", 30)
                .containsEntry("freeProbability30min", 55)
                .containsEntry("freeProbability60min", 80)
                .containsEntry("expectedFreeAt", "2026-11-02T11:25:30")
                .containsEntry("expectedFreeLatest", "2026-11-02T11:45:59")
                .containsEntry("minutesUntilFree", 25L)
                .containsEntry("minutesUntilFreeLatest", 45L)
                .doesNotContainKey("deviceClass");
    }

    @Test
    void zuWenigDatenNurStatus() {
        ChargingForecast forecast = new ChargingForecast(ChargingForecast.Status.INSUFFICIENT_DATA, 3,
                Map.of(), List.of(), null, null, null);

        EntityStateUpdate update = mapper.map(favorite, ONE_OCCUPIED, Map.of(), Optional.of(forecast));

        assertThat(update.attributes()).containsEntry("forecastStatus", "insufficient_data")
                .doesNotContainKey("freeProbability30min").doesNotContainKey("expectedFreeAt")
                .doesNotContainKey("minutesUntilFree");
    }

    @Test
    void ohneVorhersageKeinStatus() {
        EntityStateUpdate update = mapper.map(favorite, ONE_OCCUPIED, Map.of(), Optional.empty());

        assertThat(update.attributes()).doesNotContainKey("forecastStatus");
    }

    @Test
    void okOhneRestdauerLaesstZeitSchluesselWeg() {
        ChargingForecast forecast = new ChargingForecast(ChargingForecast.Status.OK, 20,
                Map.of(15, 100, 30, 100, 60, 100), List.of(), null, null, null);

        EntityStateUpdate update = mapper.map(favorite, ONE_OCCUPIED, Map.of(), Optional.of(forecast));

        assertThat(update.attributes()).containsEntry("freeProbability15min", 100)
                .doesNotContainKey("expectedFreeAt").doesNotContainKey("minutesUntilFree");
    }
}
