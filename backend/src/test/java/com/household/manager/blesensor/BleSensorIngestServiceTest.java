package com.household.manager.blesensor;

import com.household.manager.blesensor.BleSensorDtos.ReadingItem;
import com.household.manager.blesensor.BleSensorDtos.ReadingsRequest;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.repository.BleSensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BleSensorIngestServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T07:00:00Z"), BERLIN);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0);

    @Mock private BleSensorRepository repository;
    @Mock private BleSensorReadingRecorder recorder;
    @Mock private EntityStateService entityStateService;

    private BleSensorIngestService service;

    @BeforeEach
    void setUp() {
        service = new BleSensorIngestService(repository, recorder, new BleSensorEntityMapper(), entityStateService, CLOCK);
        // lenient: der Strukturfehler-Test ruft save() nie auf (strikte Stubs wuerfen sonst)
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ReadingItem item(String mac, String temp, String hum, long age) {
        return new ReadingItem(mac, new BigDecimal(temp), new BigDecimal(hum), 3030, -60, age);
    }

    @Test
    void neueMacWirdInaktivUndOhneNamenAngelegt() {
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("AA:BB:CC:DD:EE:FF", "21.5", "50", 10))));

        ArgumentCaptor<BleSensor> captor = ArgumentCaptor.forClass(BleSensor.class);
        verify(repository).save(captor.capture());
        BleSensor saved = captor.getValue();
        assertThat(saved.getMac()).isEqualTo("aa:bb:cc:dd:ee:ff");   // Review Focus 1: Grossbuchstaben treffen dieselbe Zeile
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getName()).isNull();
        assertThat(saved.getTabletId()).isEqualTo("wandtablet");
        assertThat(saved.getLastSeenAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(saved.getLastTemperature()).isEqualByComparingTo("21.5");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        verifyNoInteractions(recorder, entityStateService);
    }

    @Test
    void werteWerdenAufSpaltenmassGerundet() {
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb:cc:dd:ee:ff", "21.5625", "53.5625", 0))));

        ArgumentCaptor<BleSensor> captor = ArgumentCaptor.forClass(BleSensor.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLastTemperature()).isEqualByComparingTo("21.56");
        assertThat(captor.getValue().getLastHumidity()).isEqualByComparingTo("53.56");
    }

    @Test
    void aktiverSensorBekommtHistorieUndEntitaeten() {
        BleSensor active = BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").name("Küche").active(true)
                .createdAt(NOW.minusDays(1)).build();
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.of(active));

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        verify(recorder).record(active, NOW);
        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, org.mockito.Mockito.times(2)).reportState(updates.capture());
        assertThat(updates.getAllValues()).extracting(EntityStateUpdate::entityId)
                .containsExactly("sensor.ble_aabbccddeeff_temperature", "sensor.ble_aabbccddeeff_humidity");
    }

    @Test
    void inaktiverBestandssensorBekommtNurLetztwerte() {
        BleSensor inactive = BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").active(false).createdAt(NOW.minusDays(1)).build();
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.of(inactive));

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        verify(repository).save(inactive);
        verifyNoInteractions(recorder, entityStateService);   // Review Focus 2
    }

    @Test
    void negativesAlterWirdAufNullGeklemmt() {
        when(repository.findByMac(anyString())).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb:cc:dd:ee:ff", "21.5", "50", -30))));

        ArgumentCaptor<BleSensor> captor = ArgumentCaptor.forClass(BleSensor.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLastSeenAt()).isEqualTo(NOW);   // Review Focus 4
    }

    @Test
    void unplausiblerWertWirdUebersprungenAndereLaufenWeiter() {
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(
                item("11:22:33:44:55:66", "250", "50", 0),      // Temperatur > 100
                item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        ArgumentCaptor<BleSensor> captor = ArgumentCaptor.forClass(BleSensor.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(captor.getValue().getMac()).isEqualTo("aa:bb:cc:dd:ee:ff");
    }

    @Test
    void exponentenBombeWirdUebersprungenOhneZuRechnen() {
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.empty());
        ReadingItem bomb = new ReadingItem("11:22:33:44:55:66", new BigDecimal("1e999999999"), new BigDecimal("50"), 3030, -60, 0L);

        service.ingest(new ReadingsRequest("wandtablet", List.of(bomb, item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        ArgumentCaptor<BleSensor> captor = ArgumentCaptor.forClass(BleSensor.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(captor.getValue().getMac()).isEqualTo("aa:bb:cc:dd:ee:ff");
    }

    @Test
    void fehlerBeiEinemSensorReisstDieAnderenNichtMit() {
        when(repository.findByMac("11:22:33:44:55:66")).thenThrow(new RuntimeException("db"));
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(
                item("11:22:33:44:55:66", "20", "50", 0),
                item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        verify(repository).save(any());
    }

    @Test
    void strukturfehlerSindVierhundert() {
        assertThatThrownBy(() -> service.ingest(new ReadingsRequest(null, List.of(item("aa:bb:cc:dd:ee:ff", "20", "50", 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(new ReadingsRequest("wandtablet", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb", "20", "50", 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(new ReadingsRequest("wandtablet",
                Collections.nCopies(51, item("aa:bb:cc:dd:ee:ff", "20", "50", 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }
}
