package com.household.manager.house;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.HouseDevicePosition;
import com.household.manager.repository.HouseDevicePositionRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseDevicePositionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZONE);

    @Mock
    private HouseDevicePositionRepository repository;
    @Mock
    private AuditService auditService;

    private HouseDevicePositionService service;

    @BeforeEach
    void setUp() {
        service = new HouseDevicePositionService(repository, auditService, clock);
    }

    @Test
    void setSpeichertGerundetUndAuditiert() {
        when(repository.findById("wohnzimmer-climate")).thenReturn(Optional.empty());
        when(repository.save(any(HouseDevicePosition.class))).thenAnswer(i -> i.getArgument(0));

        HouseDevicePositionDtos.Response response = service.set("wohnzimmer-climate", 123.46, 200.04, null);

        ArgumentCaptor<HouseDevicePosition> captor = ArgumentCaptor.forClass(HouseDevicePosition.class);
        verify(repository).save(captor.capture());
        HouseDevicePosition saved = captor.getValue();
        assertThat(saved.getDeviceId()).isEqualTo("wohnzimmer-climate");
        assertThat(saved.getPlanX()).isEqualByComparingTo(new BigDecimal("123.5"));
        assertThat(saved.getPlanY()).isEqualByComparingTo(new BigDecimal("200.0"));
        assertThat(saved.getUpdatedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(response.deviceId()).isEqualTo("wohnzimmer-climate");
        assertThat(response.x()).isEqualTo(123.5);
        assertThat(response.y()).isEqualTo(200.0);
        verify(auditService).record("house.device-position.update", "wohnzimmer-climate x=123.5 y=200.0 rotation=auto");
    }

    @Test
    void setUeberschreibtVorhandeneZeile() {
        HouseDevicePosition existing = HouseDevicePosition.builder().deviceId("a")
                .planX(new BigDecimal("1.0")).planY(new BigDecimal("2.0"))
                .updatedAt(LocalDateTime.of(2020, 1, 1, 0, 0)).build();
        when(repository.findById("a")).thenReturn(Optional.of(existing));
        when(repository.save(any(HouseDevicePosition.class))).thenAnswer(i -> i.getArgument(0));

        service.set("a", 10.0, 20.0, null);

        ArgumentCaptor<HouseDevicePosition> captor = ArgumentCaptor.forClass(HouseDevicePosition.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(existing.getPlanX()).isEqualByComparingTo("10.0");
        assertThat(existing.getPlanY()).isEqualByComparingTo("20.0");
        assertThat(existing.getUpdatedAt()).isEqualTo(LocalDateTime.now(clock));
    }

    @Test
    void setLehntUngueltigeIdAb() {
        for (String id : new String[]{"", "Wohnzimmer", "a b", "x/y", "a".repeat(101), null}) {
            assertThatThrownBy(() -> service.set(id, 1.0, 1.0, null)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void setLehntUngueltigeKoordinatenAb() {
        Double[] bad = {null, Double.NaN, Double.POSITIVE_INFINITY, -0.1, 500.1};
        for (Double value : bad) {
            assertThatThrownBy(() -> service.set("a", value, 1.0, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.set("a", 1.0, value, null)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void setErlaubtGrenzwerte() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(HouseDevicePosition.class))).thenAnswer(i -> i.getArgument(0));

        HouseDevicePositionDtos.Response low = service.set("a", 0.0, 0.0, null);
        HouseDevicePositionDtos.Response high = service.set("a".repeat(100), 500.0, 500.0, null);

        assertThat(low.x()).isEqualTo(0.0);
        assertThat(high.y()).isEqualTo(500.0);
    }

    @Test
    void deleteLoeschtUndAuditiert() {
        when(repository.existsById("kueche-motion")).thenReturn(true);

        service.delete("kueche-motion");

        verify(repository).deleteById("kueche-motion");
        verify(auditService).record("house.device-position.delete", "kueche-motion");
    }

    @Test
    void deleteOhneZeileWirftNicht() {
        when(repository.existsById("weg")).thenReturn(false);

        service.delete("weg");

        verify(repository, never()).deleteById(anyString());
        verifyNoInteractions(auditService);
    }

    @Test
    void listLiefertAlleSortiert() {
        when(repository.findAllByOrderByDeviceIdAsc()).thenReturn(List.of(
                HouseDevicePosition.builder().deviceId("a").planX(new BigDecimal("1.5")).planY(new BigDecimal("2.5")).build(),
                HouseDevicePosition.builder().deviceId("b").planX(new BigDecimal("3.0")).planY(new BigDecimal("4.0")).build()));

        List<HouseDevicePositionDtos.Response> result = service.list();

        assertThat(result).extracting(HouseDevicePositionDtos.Response::deviceId).containsExactly("a", "b");
        assertThat(result.get(0).x()).isEqualTo(1.5);
    }

    @Test
    void setSpeichertDrehungUndAuditiertSie() {
        when(repository.findById("kueche-fridge")).thenReturn(Optional.empty());
        when(repository.save(any(HouseDevicePosition.class))).thenAnswer(i -> i.getArgument(0));

        HouseDevicePositionDtos.Response response = service.set("kueche-fridge", 10.0, 20.0, 270);

        assertThat(response.rotation()).isEqualTo(270);
        verify(auditService).record("house.device-position.update", "kueche-fridge x=10.0 y=20.0 rotation=270");
    }

    @Test
    void setOhneDrehungSetztSieZurueckAufAutomatisch() {
        HouseDevicePosition existing = HouseDevicePosition.builder().deviceId("a")
                .planX(new BigDecimal("1.0")).planY(new BigDecimal("2.0")).rotationDeg(90)
                .updatedAt(LocalDateTime.of(2020, 1, 1, 0, 0)).build();
        when(repository.findById("a")).thenReturn(Optional.of(existing));
        when(repository.save(any(HouseDevicePosition.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.set("a", 1.0, 2.0, null).rotation()).isNull();
    }

    @Test
    void ungueltigeDrehungErgibtFehlerOhneSpeichern() {
        for (Integer rotation : List.of(45, -90, 360)) {
            assertThatThrownBy(() -> service.set("a", 1.0, 1.0, rotation)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(repository, never()).save(any());
    }
}
