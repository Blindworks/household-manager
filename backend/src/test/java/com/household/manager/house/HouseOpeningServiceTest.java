package com.household.manager.house;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.HouseOpening;
import com.household.manager.model.entity.HouseOpeningHinge;
import com.household.manager.model.entity.HouseOpeningKind;
import com.household.manager.model.entity.HouseOpeningSize;
import com.household.manager.model.entity.HouseOpeningSwing;
import com.household.manager.repository.HouseOpeningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
class HouseOpeningServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneId.of("Europe/Berlin"));

    @Mock
    private HouseOpeningRepository repository;
    @Mock
    private AuditService auditService;

    private HouseOpeningService service;

    @BeforeEach
    void setUp() {
        service = new HouseOpeningService(repository, auditService, clock);
    }

    private static HouseOpeningDtos.Request request(String floor, HouseOpeningKind kind, HouseOpeningSize size,
                                                    Double x1, Double y1, Double x2, Double y2, String deviceId) {
        return new HouseOpeningDtos.Request(floor, kind, size, x1, y1, x2, y2,
                HouseOpeningHinge.START, HouseOpeningSwing.POSITIVE, deviceId);
    }

    private static HouseOpeningDtos.Request window(double x1, double y1, double x2, double y2) {
        return request("ground", HouseOpeningKind.WINDOW, HouseOpeningSize.NORMAL, x1, y1, x2, y2, null);
    }

    private void stubSave(long id) {
        lenient().when(repository.saveAndFlush(any(HouseOpening.class))).thenAnswer(i -> {
            HouseOpening o = i.getArgument(0);
            o.setId(id);
            return o;
        });
    }

    @Test
    void createNormalisiertStreckeUndAuditiert() {
        stubSave(7L);

        HouseOpeningDtos.Response response = service.create(window(100, 90, 60, 90));

        ArgumentCaptor<HouseOpening> captor = ArgumentCaptor.forClass(HouseOpening.class);
        verify(repository).saveAndFlush(captor.capture());
        HouseOpening saved = captor.getValue();
        assertThat(saved.getX1()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(saved.getX2()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(saved.getY1()).isEqualByComparingTo(new BigDecimal("90"));
        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.x1()).isEqualTo(60.0);
        verify(auditService).record("house.opening.create", "7 WINDOW NORMAL ground");
    }

    @Test
    void lehntSchraegeStreckeAb() {
        assertThatThrownBy(() -> service.create(window(60, 90, 100, 95)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(auditService);
    }

    @Test
    void lehntFalscheLaengeAb() {
        assertThatThrownBy(() -> service.create(window(60, 90, 99, 90)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void akzeptiertAlleFuenfGroessenMitPassenderLaengeUndLehntFalscheAb() {
        stubSave(1L);
        Object[][] table = {
                {HouseOpeningKind.WINDOW, HouseOpeningSize.SMALL, 24.0},
                {HouseOpeningKind.WINDOW, HouseOpeningSize.NORMAL, 40.0},
                {HouseOpeningKind.WINDOW, HouseOpeningSize.WIDE, 64.0},
                {HouseOpeningKind.DOOR, HouseOpeningSize.NORMAL, 36.0},
                {HouseOpeningKind.DOOR, HouseOpeningSize.TERRACE, 72.0}};
        for (Object[] row : table) {
            HouseOpeningKind kind = (HouseOpeningKind) row[0];
            HouseOpeningSize size = (HouseOpeningSize) row[1];
            double length = (double) row[2];
            // senkrecht, damit auch die y-Achse geprueft ist
            service.create(request("upper", kind, size, 50.0, 10.0, 50.0, 10.0 + length, null));
            assertThatThrownBy(() -> service.create(request("upper", kind, size, 50.0, 10.0, 50.0, 11.0 + length, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void lehntGroesseZurFalschenArtAb() {
        assertThatThrownBy(() -> service.create(request("ground", HouseOpeningKind.DOOR, HouseOpeningSize.SMALL,
                10.0, 10.0, 34.0, 10.0, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request("ground", HouseOpeningKind.WINDOW, HouseOpeningSize.TERRACE,
                10.0, 10.0, 82.0, 10.0, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lehntUngueltigeWerteAb() {
        assertThatThrownBy(() -> service.create(request("attic", HouseOpeningKind.WINDOW, HouseOpeningSize.NORMAL,
                60.0, 90.0, 100.0, 90.0, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(window(Double.NaN, 90, 100, 90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(window(-1, 90, 39, 90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(window(460.1, 90, 500.1, 90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request("ground", HouseOpeningKind.WINDOW, HouseOpeningSize.NORMAL,
                60.0, 90.0, 100.0, 90.0, "Bad Fenster"))).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void vergebeneDeviceIdErgibtKonflikt() {
        HouseOpening other = HouseOpening.builder().id(3L).build();
        when(repository.findByDeviceId("bad-fenster")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.create(request("ground", HouseOpeningKind.WINDOW, HouseOpeningSize.NORMAL,
                60.0, 90.0, 100.0, 90.0, "bad-fenster"))).isInstanceOf(DuplicateEntityException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateMitEigenerDeviceIdIstKeinKonflikt() {
        HouseOpening existing = HouseOpening.builder().id(3L).deviceId("bad-fenster").build();
        when(repository.findById(3L)).thenReturn(Optional.of(existing));
        when(repository.findByDeviceId("bad-fenster")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(HouseOpening.class))).thenAnswer(i -> i.getArgument(0));

        service.update(3L, request("ground", HouseOpeningKind.WINDOW, HouseOpeningSize.NORMAL,
                60.0, 90.0, 100.0, 90.0, "bad-fenster"));

        verify(auditService).record("house.opening.update", "3 WINDOW NORMAL ground");
    }

    @Test
    void updateUnbekannteIdErgibt404() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(9L, window(60, 90, 100, 90)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteUnbekannteIdErgibt404() {
        when(repository.existsById(9L)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(ResourceNotFoundException.class);
        verify(auditService, never()).record(anyString(), anyString());
    }

    @Test
    void deleteLoeschtUndAuditiert() {
        when(repository.existsById(4L)).thenReturn(true);
        service.delete(4L);
        verify(repository).deleteById(4L);
        verify(auditService).record("house.opening.delete", "4");
    }
}
