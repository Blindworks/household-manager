package com.household.manager.service;

import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Der Zustands-Poller ist die einzige Stelle, die extern (Tapo-/Kasa-App, Wandschalter)
 * ausgeloeste An/Aus-Aenderungen ins Dashboard bringt: der Schalter-Kachel-Layer liest
 * gespiegelte Entity-States, und ausser diesem Poller aktualisiert die nur ein
 * Seitenaufruf, ein Scan oder ein Schaltbefehl.
 */
class SmartDeviceStatePollingServiceTest {

    private final SmartDeviceRepository repository = mock(SmartDeviceRepository.class);
    private final SmartDeviceService smartDeviceService = mock(SmartDeviceService.class);

    private SmartDeviceStatePollingService service(boolean enabled) {
        return new SmartDeviceStatePollingService(repository, smartDeviceService, enabled);
    }

    private static SmartDevice device(long id, DeviceType type) {
        SmartDevice d = new SmartDevice();
        d.setId(id);
        d.setDeviceType(type);
        d.setDeviceName("Geraet " + id);
        return d;
    }

    @Test
    @DisplayName("Pollt nur Kasa- und Tapo-Geraete, nicht Meross (eigene, teurere Entscheidung)")
    void queriesOnlyKasaAndTapoDevices() {
        when(repository.findByDeviceTypeInOrderByDeviceNameAsc(List.of(DeviceType.KASA, DeviceType.TAPO)))
                .thenReturn(List.of());

        service(true).poll();

        ArgumentCaptor<List<DeviceType>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).findByDeviceTypeInOrderByDeviceNameAsc(captor.capture());
        assertThat(captor.getValue()).containsExactly(DeviceType.KASA, DeviceType.TAPO);
    }

    @Test
    @DisplayName("Aktualisiert den Zustand jedes gefundenen Geraets")
    void refreshesEveryDevice() {
        when(repository.findByDeviceTypeInOrderByDeviceNameAsc(List.of(DeviceType.KASA, DeviceType.TAPO)))
                .thenReturn(List.of(device(29, DeviceType.KASA), device(21, DeviceType.TAPO), device(25, DeviceType.TAPO)));

        service(true).poll();

        verify(smartDeviceService).refreshDeviceState(29L);
        verify(smartDeviceService).refreshDeviceState(21L);
        verify(smartDeviceService).refreshDeviceState(25L);
    }

    @Test
    @DisplayName("Ein fehlerhaftes Geraet stoppt die anderen nicht")
    void isolatesPerDeviceFailure() {
        when(repository.findByDeviceTypeInOrderByDeviceNameAsc(List.of(DeviceType.KASA, DeviceType.TAPO)))
                .thenReturn(List.of(device(21, DeviceType.TAPO), device(25, DeviceType.TAPO), device(29, DeviceType.KASA)));
        doThrow(new RuntimeException("Geraet nicht erreichbar")).when(smartDeviceService).refreshDeviceState(25L);

        service(true).poll();

        verify(smartDeviceService).refreshDeviceState(21L);
        verify(smartDeviceService).refreshDeviceState(25L);
        verify(smartDeviceService).refreshDeviceState(29L);
    }

    @Test
    @DisplayName("Deaktiviert: pollt nichts und fragt die Datenbank nicht ab")
    void doesNothingWhenDisabled() {
        service(false).poll();

        verifyNoInteractions(repository);
        verify(smartDeviceService, never()).refreshDeviceState(anyLong());
    }
}
