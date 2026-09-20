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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChargingEntityMapperTest {

    private final ChargingEntityMapper mapper = new ChargingEntityMapper();
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

        EntityStateUpdate update = mapper.map(favorite, details, occupancy);

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

        EntityStateUpdate update = mapper.map(favorite, details, occupancy);

        assertThat(update.state()).isEqualTo("0");
        assertThat(update.attributes()).doesNotContainKey("occupiedSince").doesNotContainKey("maxPowerKw")
                .doesNotContainKey("pricePerKwh");
    }

    @Test
    void entityIdIstAusDerStationIdAbleitbar() {
        assertThat(ChargingEntityMapper.entityId("DE*LDL*E123")).isEqualTo("sensor.charging_de_ldl_e123_free");
    }
}
