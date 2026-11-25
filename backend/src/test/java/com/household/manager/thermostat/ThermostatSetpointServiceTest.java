package com.household.manager.thermostat;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.tapo.hub.ThermostatMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThermostatSetpointServiceTest {

    @Mock
    private ThermostatRepository repository;
    @Mock
    private ThermostatService thermostatService;
    @Mock
    private ThermostatRoomRegulator regulator;
    @Mock
    private AuditService auditService;

    private ThermostatSetpointService service;
    private Thermostat bath;
    private Thermostat kitchen;

    @BeforeEach
    void setUp() {
        service = new ThermostatSetpointService(repository, thermostatService, regulator, auditService);
        bath = Thermostat.builder().id(7L).name("Bad").roomSensorId("dirigera:3").roomTarget(21.0).active(true).build();
        kitchen = Thermostat.builder().id(8L).name("Kueche").active(true).build();
        lenient().when(repository.findById(7L)).thenReturn(Optional.of(bath));
        lenient().when(repository.findById(8L)).thenReturn(Optional.of(kitchen));
    }

    @Test
    void mitRaumfuehlerWirdDerWertZumRaumSollUndSofortGeregelt() {
        service.setTarget(7L, 22.5);

        assertEquals(22.5, bath.getRoomTarget());
        verify(repository).save(bath);
        verify(auditService).record("thermostat.room-target", "Bad (7) -> 22.5");
        verify(regulator).regulateNow(bath);
        verify(thermostatService, never()).setTarget(any(), any(Double.class));
    }

    @Test
    void ohneRaumfuehlerGehtDerWertDirektAnsThermostat() {
        service.setTarget(8L, 22.5);

        verify(thermostatService).setTarget(8L, 22.5);
        assertNull(kitchen.getRoomTarget());
        verify(regulator, never()).regulateNow(any());
    }

    @Test
    void ausGehtAuchMitRaumfuehlerDirektAnsThermostatUndLaesstDasRaumSollStehen() {
        service.setTarget(7L, 5.0);

        verify(thermostatService).setTarget(7L, 5.0);
        assertEquals(21.0, bath.getRoomTarget());
    }

    @Test
    void ungueltigerWertWirdAbgelehntOhneZuSchreiben() {
        assertThrows(IllegalArgumentException.class, () -> service.setTarget(7L, 21.3));

        verify(repository, never()).save(any());
        verify(regulator, never()).regulateNow(any());
    }

    @Test
    void manualMitRaumSollRegeltSofort() {
        service.setMode(7L, ThermostatMode.MANUAL);

        verify(thermostatService).setMode(7L, ThermostatMode.MANUAL);
        verify(regulator).regulateNow(bath);
    }

    @Test
    void ausschaltenVergisstDenRegelstand() {
        service.setMode(7L, ThermostatMode.OFF);

        verify(regulator).forget(7L);
        verify(regulator, never()).regulateNow(any());
    }

    private static ThermostatDto dto(long id) {
        return new ThermostatDto(id, "x", true, 20.0, 21.0, ThermostatMode.MANUAL, false, false, null, null, null);
    }

    @Test
    void sammelaktionSetztRaumSollNurBeiThermostatenMitFuehler() {
        ThermostatDto bathDto = dto(7L);
        ThermostatDto kitchenDto = dto(8L);
        when(thermostatService.bulk(null, 21.0)).thenReturn(List.of(bathDto, kitchenDto));

        service.bulk(null, 21.0);

        assertEquals(21.0, bath.getRoomTarget());
        verify(regulator).regulateNow(bath);
        assertNull(kitchen.getRoomTarget());
    }
}
