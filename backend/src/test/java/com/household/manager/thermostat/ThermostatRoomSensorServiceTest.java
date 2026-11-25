package com.household.manager.thermostat;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.service.TemperatureSeriesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThermostatRoomSensorServiceTest {

    @Mock
    private ThermostatRepository repository;
    @Mock
    private TemperatureSeriesService temperatureSeriesService;
    @Mock
    private AuditService auditService;
    @Mock
    private ThermostatRoomRegulator regulator;

    private ThermostatRoomSensorService service;
    private Thermostat living;

    @BeforeEach
    void setUp() {
        service = new ThermostatRoomSensorService(repository, new ThermostatStateCache(),
                temperatureSeriesService, auditService, regulator);
        living = Thermostat.builder().id(1L).childDeviceId("KE-1").hubDeviceId("HUB")
                .name("Wohnzimmerfenster").active(true).build();
        lenient().when(repository.findById(1L)).thenReturn(Optional.of(living));
        lenient().when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("dirigera:3", "Temperatur Wohnzimmer"),
                reading("weather:outdoor", "Außen")));
    }

    private static CurrentTemperatureReading reading(String sensorId, String name) {
        return CurrentTemperatureReading.builder().sensorId(sensorId).name(name)
                .temperature(new BigDecimal("20.3")).measuredAt(LocalDateTime.now()).build();
    }

    @Test
    void ordnetBekanntenFuehlerZuUndAuditiert() {
        ThermostatDto dto = service.setRoomSensor(1L, " dirigera:3 ");

        assertEquals("dirigera:3", living.getRoomSensorId());
        assertEquals("dirigera:3", dto.roomSensorId());
        verify(repository).save(living);
        verify(auditService).record("thermostat.room-sensor",
                "Wohnzimmerfenster (1) -> Temperatur Wohnzimmer (dirigera:3)");
    }

    @Test
    void leereIdEntferntDieZuordnungOhneFuehlerabfrage() {
        living.setRoomSensorId("dirigera:3");

        ThermostatDto dto = service.setRoomSensor(1L, "  ");

        assertNull(living.getRoomSensorId());
        assertNull(dto.roomSensorId());
        verifyNoInteractions(temperatureSeriesService);
        verify(auditService).record("thermostat.room-sensor", "Wohnzimmerfenster (1) -> kein Raumfuehler");
    }

    @Test
    void unbekannterFuehlerWirdAbgelehntOhneSpeichern() {
        assertThrows(IllegalArgumentException.class, () -> service.setRoomSensor(1L, "ble:99"));

        verify(repository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void wettervorhersageIstKeinRaumfuehler() {
        assertThrows(IllegalArgumentException.class, () -> service.setRoomSensor(1L, "weather:outdoor"));

        verify(repository, never()).save(any());
    }

    @Test
    void unbekanntesThermostatIst404() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.setRoomSensor(7L, "dirigera:3"));
    }

    @Test
    void setztRaumSollMitFuehlerVergisstRegelstandUndAuditiert() {
        living.setRoomSensorId("dirigera:3");

        ThermostatDto dto = service.setRoomTarget(1L, 21.5);

        assertEquals(21.5, living.getRoomTarget());
        assertEquals(21.5, dto.roomTarget());
        verify(regulator).forget(1L);
        verify(auditService).record("thermostat.room-target", "Wohnzimmerfenster (1) -> 21.5");
    }

    @Test
    void raumSollOhneFuehlerWirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class, () -> service.setRoomTarget(1L, 21.0));
        verify(repository, never()).save(any());
    }

    @Test
    void raumSollAusserhalbDesRastersWirdAbgelehnt() {
        living.setRoomSensorId("dirigera:3");
        assertThrows(IllegalArgumentException.class, () -> service.setRoomTarget(1L, 21.3));
        assertThrows(IllegalArgumentException.class, () -> service.setRoomTarget(1L, 9.5));
        assertThrows(IllegalArgumentException.class, () -> service.setRoomTarget(1L, 28.5));
        assertThrows(IllegalArgumentException.class, () -> service.setRoomTarget(1L, Double.NaN));
        verify(repository, never()).save(any());
    }

    @Test
    void nullBeendetDieRaumregelungAuchOhneFuehler() {
        living.setRoomTarget(21.0);

        service.setRoomTarget(1L, null);

        assertNull(living.getRoomTarget());
        verify(auditService).record("thermostat.room-target", "Wohnzimmerfenster (1) -> keine Raumregelung");
    }
}
