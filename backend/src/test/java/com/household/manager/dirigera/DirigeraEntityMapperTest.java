package com.household.manager.dirigera;

import com.household.manager.dirigera.DirigeraEntityMapper.Measurement;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DirigeraSensor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DirigeraEntityMapperTest {

    private static final String RELATION_ID = "dcc8d385-781d-4071-adf8-5f403b0cb997";

    private final DirigeraEntityMapper mapper = new DirigeraEntityMapper();

    private DirigeraSensor.DirigeraSensorBuilder sensor() {
        return DirigeraSensor.builder().relationId(RELATION_ID).name("Wohnzimmer")
                .lastTemperature(new BigDecimal("20.50")).lastHumidity(new BigDecimal("45.00"));
    }

    @Test
    void entityIdHaengtAnRelationId() {
        assertThat(DirigeraEntityMapper.entityId(RELATION_ID, Measurement.TEMPERATURE))
                .isEqualTo("sensor.dirigera_dcc8d385781d4071adf85f403b0cb997_temperature");
    }

    @Test
    void updatesNurFuerVorhandeneWerte() {
        List<EntityStateUpdate> updates = mapper.updates(sensor().lastHumidity(null).build());

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).entityId()).endsWith("_temperature");
    }

    @Test
    void stateOhneNachkommaNullen() {
        assertThat(mapper.updates(sensor().build()).get(0).state()).isEqualTo("20.5");
    }

    @Test
    void keinDeviceClass() {
        assertThat(mapper.updates(sensor().build())).allSatisfy(u -> assertThat(u.attributes()).doesNotContainKey("deviceClass"));
    }

    @Test
    void unavailableBehaeltAttribute() {
        DirigeraSensor s = sensor().batteryPercent(87).room("Wohnzimmer").model("TIMMERFLOTTE")
                .lastSeenAt(LocalDateTime.of(2026, 10, 30, 12, 0)).build();

        List<EntityStateUpdate> updates = mapper.unavailable(s);

        assertThat(updates).hasSize(2);
        assertThat(updates).allSatisfy(u -> {
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsEntry("batteryPercent", 87).containsEntry("room", "Wohnzimmer")
                    .containsEntry("model", "TIMMERFLOTTE").containsEntry("lastSeenAt", "2026-10-30T12:00");
        });
    }

    @Test
    void friendlyNameFallbackAufModell() {
        EntityStateUpdate u = mapper.updates(sensor().name(" ").model("TIMMERFLOTTE").build()).get(0);

        assertThat(u.friendlyName()).isEqualTo("TIMMERFLOTTE Temperatur");
    }

    @Test
    void friendlyNameFallbackAufRelationId() {
        EntityStateUpdate u = mapper.updates(sensor().name(null).build()).get(0);

        assertThat(u.friendlyName()).isEqualTo(RELATION_ID + " Temperatur");
    }

    private DirigeraSensor.DirigeraSensorBuilder contact() {
        return DirigeraSensor.builder().relationId(RELATION_ID).name("Badfenster")
                .kind(DirigeraSensorKind.CONTACT).lastOpen(true).batteryPercent(80).room("Bad");
    }

    @Test
    void kontaktIdIstBinarySensorMitContactSuffix() {
        assertThat(DirigeraEntityMapper.contactEntityId(RELATION_ID))
                .isEqualTo("binary_sensor.dirigera_dcc8d385781d4071adf85f403b0cb997_contact");
    }

    @Test
    void offenerKontaktIstOnMitDeviceClassDoor() {
        List<EntityStateUpdate> updates = mapper.updates(contact().build());

        assertThat(updates).hasSize(1);
        EntityStateUpdate u = updates.get(0);
        assertThat(u.entityId()).isEqualTo(DirigeraEntityMapper.contactEntityId(RELATION_ID));
        assertThat(u.state()).isEqualTo("on");
        assertThat(u.friendlyName()).isEqualTo("Badfenster Kontakt");
        assertThat(u.attributes()).containsEntry("deviceClass", "door").containsEntry("batteryPercent", 80)
                .containsEntry("room", "Bad").doesNotContainKey("unit");
    }

    @Test
    void geschlossenerKontaktIstOff() {
        assertThat(mapper.updates(contact().lastOpen(false).build()).get(0).state()).isEqualTo("off");
    }

    @Test
    void kontaktOhneZustandMeldetNichts() {
        assertThat(mapper.updates(contact().lastOpen(null).build())).isEmpty();
    }

    @Test
    void kontaktUnavailableNurFuerDieKontaktEntitaet() {
        List<EntityStateUpdate> updates = mapper.unavailable(contact().build());

        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.entityId()).isEqualTo(DirigeraEntityMapper.contactEntityId(RELATION_ID));
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsEntry("deviceClass", "door").containsEntry("batteryPercent", 80);
        });
    }

    @Test
    void entityIdsJeArt() {
        assertThat(DirigeraEntityMapper.entityIds(contact().build()))
                .containsExactly(DirigeraEntityMapper.contactEntityId(RELATION_ID));
        assertThat(DirigeraEntityMapper.entityIds(sensor().build())).containsExactly(
                DirigeraEntityMapper.entityId(RELATION_ID, Measurement.TEMPERATURE),
                DirigeraEntityMapper.entityId(RELATION_ID, Measurement.HUMIDITY));
    }
}
