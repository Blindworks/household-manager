package com.household.manager.blesensor;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BleSensorEntityMapperTest {

    private final BleSensorEntityMapper mapper = new BleSensorEntityMapper();

    private BleSensor sensor() {
        return BleSensor.builder()
                .id(7L).mac("aa:bb:cc:dd:ee:ff").name("Küche").active(true).tabletId("wandtablet")
                .lastTemperature(new BigDecimal("21.56")).lastHumidity(new BigDecimal("53.56"))
                .lastBatteryMv(3030).lastRssi(-61).lastSeenAt(LocalDateTime.of(2026, 10, 13, 9, 0))
                .createdAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .build();
    }

    @Test
    void entityIdsHaengenAnDerMacNichtAmNamen() {
        assertThat(BleSensorEntityMapper.entityId("aa:bb:cc:dd:ee:ff", BleSensorEntityMapper.Measurement.TEMPERATURE))
                .isEqualTo("sensor.ble_aabbccddeeff_temperature");
        assertThat(BleSensorEntityMapper.entityId("aa:bb:cc:dd:ee:ff", BleSensorEntityMapper.Measurement.HUMIDITY))
                .isEqualTo("sensor.ble_aabbccddeeff_humidity");
    }

    @Test
    void updatesTragenWerteEinheitBatterieUndTablet() {
        List<EntityStateUpdate> updates = mapper.updates(sensor());

        assertThat(updates).hasSize(2);
        EntityStateUpdate temp = updates.get(0);
        assertThat(temp.entityId()).isEqualTo("sensor.ble_aabbccddeeff_temperature");
        assertThat(temp.domain()).isEqualTo(EntityDomain.SENSOR);
        assertThat(temp.source()).isEqualTo(EntitySource.BLE);
        assertThat(temp.sourceRef()).isEqualTo("aabbccddeeff");
        assertThat(temp.friendlyName()).isEqualTo("Küche Temperatur");
        assertThat(temp.state()).isEqualTo("21.56");
        assertThat(temp.attributes()).containsEntry("unit", "°C")
                .containsEntry("batteryPercent", 100)
                .containsEntry("batteryMillivolts", 3030)
                .containsEntry("rssi", -61)
                .containsEntry("tabletId", "wandtablet")
                .doesNotContainKey("deviceClass");

        EntityStateUpdate hum = updates.get(1);
        assertThat(hum.entityId()).isEqualTo("sensor.ble_aabbccddeeff_humidity");
        assertThat(hum.friendlyName()).isEqualTo("Küche Luftfeuchte");
        assertThat(hum.state()).isEqualTo("53.56");
        assertThat(hum.attributes()).containsEntry("unit", "%");
    }

    @Test
    void stateIstOhneNachlaufendeNullen() {
        BleSensor s = sensor();
        s.setLastTemperature(new BigDecimal("21.00"));
        assertThat(mapper.updates(s).get(0).state()).isEqualTo("21");
    }

    @Test
    void fehlendeWerteLiefernKeinUpdateFuerDieseMessgroesse() {
        BleSensor s = sensor();
        s.setLastHumidity(null);
        assertThat(mapper.updates(s)).hasSize(1);
        assertThat(mapper.updates(s).get(0).entityId()).endsWith("_temperature");
    }

    @Test
    void unavailableBehaeltAttribute() {
        List<EntityStateUpdate> updates = mapper.unavailable(sensor());
        assertThat(updates).hasSize(2);
        assertThat(updates).allSatisfy(u -> {
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsKey("unit").containsEntry("batteryPercent", 100);
        });
    }

    @Test
    void ohneNamenFaelltFriendlyNameAufDieMacZurueck() {
        BleSensor s = sensor();
        s.setName(null);
        assertThat(mapper.updates(s).get(0).friendlyName()).isEqualTo("aa:bb:cc:dd:ee:ff Temperatur");
    }
}
