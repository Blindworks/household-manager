package com.household.manager.thermostat;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorContext;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.service.TemperatureSeriesService;
import com.household.manager.systemlog.SystemJobMonitor;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThermostatRoomRegulatorTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Mock
    private ThermostatRepository repository;
    @Mock
    private ThermostatService thermostatService;
    @Mock
    private TemperatureSeriesService temperatureSeriesService;
    @Mock
    private SystemJobMonitor jobMonitor;

    private final ThermostatStateCache cache = new ThermostatStateCache();
    private MutableClock clock;
    private ThermostatRoomRegulator regulator;
    private Thermostat bath;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-25T15:00:00Z"));
        regulator = new ThermostatRoomRegulator(repository, cache, thermostatService,
                temperatureSeriesService, jobMonitor, clock);
        bath = Thermostat.builder().id(7L).childDeviceId("KE-7").hubDeviceId("HUB").hubIp("192.168.1.60")
                .name("Bad").roomSensorId("dirigera:3").roomTarget(22.0).active(true).build();
        lenient().when(repository.findByActiveTrue()).thenReturn(List.of(bath));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void thermostat(double current, double target, ThermostatMode mode, boolean online) {
        cache.applyHubReadings(List.of(bath),
                List.of(new ThermostatReading("KE-7", "Bad", online, current, target, mode, false, false)), now());
    }

    private void room(String temperature, LocalDateTime measuredAt) {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(CurrentTemperatureReading.builder()
                .sensorId("dirigera:3").name("Bad").temperature(new BigDecimal(temperature))
                .measuredAt(measuredAt).build()));
    }

    @Test
    void stelltDenSollwertNachDemRaumwertNach() {
        thermostat(23.0, 22.0, ThermostatMode.MANUAL, true);
        room("20.5", now().minusMinutes(5));

        regulator.regulate();

        // Versatz 2,5 => 22 + 2,5
        verify(thermostatService).setTarget(7L, 24.5);
    }

    @Test
    void schreibtUnterDemAuditAktorDerRaumregelung() {
        thermostat(23.0, 22.0, ThermostatMode.MANUAL, true);
        room("20.5", now());
        AtomicReference<AuditActor> actor = new AtomicReference<>();
        doAnswer(inv -> {
            actor.set(AuditActorContext.get());
            return null;
        }).when(thermostatService).setTarget(anyLong(), anyDouble());

        regulator.regulate();

        assertEquals(AuditActor.roomRegulation(), actor.get());
        assertNull(AuditActorContext.get());
    }

    @Test
    void schreibtNichtBeiKleinerAbweichung() {
        thermostat(22.2, 22.0, ThermostatMode.MANUAL, true);
        room("22.0", now());

        regulator.regulate();

        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
    }

    @Test
    void greiftNichtEinWennDasThermostatAusIst() {
        thermostat(15.0, 5.0, ThermostatMode.OFF, true);
        room("15.0", now());

        regulator.regulate();

        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
    }

    @Test
    void greiftNichtEinBeiFrostschutzOderOffline() {
        thermostat(15.0, 17.0, ThermostatMode.FROST_PROTECTION, true);
        lenient().when(temperatureSeriesService.getCurrent()).thenReturn(List.of());
        regulator.regulate();
        thermostat(15.0, 17.0, ThermostatMode.MANUAL, false);
        regulator.regulate();

        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
    }

    @Test
    void greiftNichtEinBeiVeraltetemOderFehlendemRaumwert() {
        thermostat(23.0, 22.0, ThermostatMode.MANUAL, true);
        room("18.0", now().minusMinutes(31));
        regulator.regulate();

        when(temperatureSeriesService.getCurrent()).thenReturn(List.of());
        regulator.regulate();

        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
    }

    @Test
    void ohneRaumSollIstDieRegelungInaktiv() {
        bath.setRoomTarget(null);

        regulator.regulate();

        verify(jobMonitor).reportInactive(anyString());
        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
    }

    @Test
    void verzoegerteHubMeldungGiltNichtAlsHandaenderung() {
        thermostat(23.0, 22.0, ThermostatMode.MANUAL, true);
        room("20.5", now());
        regulator.regulate();
        verify(thermostatService).setTarget(7L, 24.5);

        // Hub meldet noch den alten Wert, der Raum ist inzwischen kaelter
        clock.plusMinutes(5);
        thermostat(23.0, 22.0, ThermostatMode.MANUAL, true);
        room("19.5", now());
        regulator.regulate();

        verify(thermostatService).setTarget(7L, 25.5);
    }

    @Test
    void handaenderungPausiertDieRegelungZweiStunden() {
        thermostat(23.0, 22.0, ThermostatMode.MANUAL, true);
        room("20.5", now());
        regulator.regulate();

        // Jemand dreht am Tablet auf 19
        clock.plusMinutes(5);
        thermostat(23.0, 19.0, ThermostatMode.MANUAL, true);
        room("20.5", now());
        regulator.regulate();
        clock.plusMinutes(115);
        regulator.regulate();
        // waehrend der Pause bleibt es beim ersten Schreiben
        verify(thermostatService, org.mockito.Mockito.times(1)).setTarget(7L, 24.5);

        clock.plusMinutes(6);
        room("20.5", now());
        regulator.regulate();
        // nach der Pause regelt sie wieder; der erste Aufruf (24,5) plus dieser
        verify(thermostatService, org.mockito.Mockito.times(2)).setTarget(7L, 24.5);
    }

    @Test
    void meldetStoerungWennKeinThermostatGeregeltWerdenKann() {
        thermostat(23.0, 22.0, ThermostatMode.MANUAL, true);
        room("20.5", now());
        doAnswer(inv -> {
            throw new IllegalStateException("Hub weg");
        }).when(thermostatService).setTarget(anyLong(), anyDouble());

        regulator.regulate();

        verify(jobMonitor).reportFailure(anyString());
    }

    /** Uhr, die der Test vorstellen kann. */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void plusMinutes(long minutes) {
            instant = instant.plusSeconds(minutes * 60);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void regulateNowSchreibtSofortMitVersatzUndHebtDiePauseAuf() {
        thermostat(24.0, 22.0, ThermostatMode.MANUAL, true);
        room("20.0", now());

        regulator.regulateNow(bath);

        // 22 + (24 - 20) = 26
        verify(thermostatService).setTarget(7L, 26.0);
    }

    @Test
    void regulateNowOhneFrischenRaumwertSchreibtDasRaumSoll() {
        thermostat(24.0, 5.0, ThermostatMode.OFF, true);
        room("20.0", now().minusHours(2));

        regulator.regulateNow(bath);

        verify(thermostatService).setTarget(7L, 22.0);
    }

    @Test
    void nachRegulateNowZaehltDerGeschriebeneWertNichtAlsHandaenderung() {
        thermostat(24.0, 22.0, ThermostatMode.MANUAL, true);
        room("20.0", now());
        regulator.regulateNow(bath);
        thermostat(24.0, 26.0, ThermostatMode.MANUAL, true);
        room("21.0", now());

        regulator.regulate();

        // 22 + (24 - 21) = 25: regelt weiter, keine Pause
        verify(thermostatService).setTarget(7L, 25.0);
    }
}

