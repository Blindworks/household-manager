package com.household.manager.entitystate.mapper;

import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlinkEntityMapperTest {

    private final BlinkEntityMapper mapper = new BlinkEntityMapper();

    private static final SidecarCamera DOOR =
            new SidecarCamera("123", "Haustuer", "doorbell", true, "ok", "Zuhause", true);
    private static final SidecarCamera INDOOR =
            new SidecarCamera("456", "Wohnzimmer", "", false, "ok", "Zuhause", true);

    @Test
    void kameraWirdZurArmedEntitaet() {
        List<EntityStateUpdate> updates = mapper.map(List.of(DOOR));

        EntityStateUpdate camera = updates.stream()
                .filter(u -> u.entityId().equals("binary_sensor.blink_123_armed"))
                .findFirst().orElseThrow();
        assertThat(camera.domain()).isEqualTo(EntityDomain.BINARY_SENSOR);
        assertThat(camera.source()).isEqualTo(EntitySource.BLINK);
        assertThat(camera.sourceRef()).isEqualTo("123");
        assertThat(camera.state()).isEqualTo("on");
        assertThat(camera.friendlyName()).isEqualTo("Haustuer scharf");
        assertThat(camera.attributes())
                .containsEntry("name", "Haustuer")
                .containsEntry("type", "doorbell")
                .containsEntry("battery", "ok")
                .containsEntry("syncName", "Zuhause");
    }

    @Test
    void jedesSyncModulErgibtGenauEineSystemEntitaet() {
        List<EntityStateUpdate> updates = mapper.map(List.of(DOOR, INDOOR));

        List<EntityStateUpdate> syncs = updates.stream()
                .filter(u -> u.entityId().startsWith("binary_sensor.blink_sync_")).toList();
        assertThat(syncs).hasSize(1);
        assertThat(syncs.get(0).entityId()).isEqualTo("binary_sensor.blink_sync_zuhause_armed");
        assertThat(syncs.get(0).state()).isEqualTo("on");
        assertThat(syncs.get(0).friendlyName()).isEqualTo("Blink Zuhause scharf");
    }

    @Test
    void unscharfeKameraMeldetOff() {
        List<EntityStateUpdate> updates = mapper.map(List.of(INDOOR));
        assertThat(updates.stream()
                .filter(u -> u.entityId().equals("binary_sensor.blink_456_armed"))
                .findFirst().orElseThrow().state()).isEqualTo("off");
    }

    @Test
    void syncNameMitSonderzeichenWirdZumSlug() {
        var cam = new SidecarCamera("9", "K", "", true, null, "Büro Süd", false);
        assertThat(mapper.map(List.of(cam)).stream()
                .map(EntityStateUpdate::entityId))
                .contains("binary_sensor.blink_sync_buero_sued_armed");
    }
}
