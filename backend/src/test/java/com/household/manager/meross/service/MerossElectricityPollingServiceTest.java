package com.household.manager.meross.service;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.meross.config.MerossElectricityPollingProperties;
import com.household.manager.meross.dto.MerossElectricityReading;
import com.household.manager.meross.exception.MerossException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerossElectricityPollingServiceTest {

    private static final String DEVICE_ID = "2112156531504590863548e1e9817420";

    @Mock
    private MerossDeviceService merossDeviceService;
    @Mock
    private EntityStateService entityStateService;

    private MerossElectricityPollingProperties properties;
    private MerossElectricityPollingService service;

    @BeforeEach
    void setUp() {
        properties = new MerossElectricityPollingProperties();
        properties.setDeviceIds(List.of(DEVICE_ID));
        service = new MerossElectricityPollingService(properties, merossDeviceService, entityStateService);
    }

    @Test
    void meldetLeistungAlsSensorEntitaet() {
        when(merossDeviceService.readElectricity(DEVICE_ID)).thenReturn(new MerossElectricityReading(
                DEVICE_ID, "Waschmaschine",
                new BigDecimal("1234.6"), new BigDecimal("230.1"), new BigDecimal("5.432")));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("sensor.meross_" + DEVICE_ID + "_power");
        assertThat(update.state()).isEqualTo("1234.6");
        assertThat(update.friendlyName()).isEqualTo("Waschmaschine Leistung");
        assertThat(update.attributes()).containsEntry("unit", "W");
    }

    @Test
    void lesefehlerFuehrtNichtZuUnavailableReport() {
        when(merossDeviceService.readElectricity(DEVICE_ID))
                .thenThrow(new MerossException("Geraet antwortet nicht"));

        service.poll();

        verifyNoInteractions(entityStateService);
    }

    @Test
    void deaktiviertesPollingMeldetNichts() {
        properties.setEnabled(false);

        service.poll();

        verifyNoInteractions(merossDeviceService, entityStateService);
    }

    @Test
    void ohneKonfigurierteGeraeteKeinPolling() {
        properties.setDeviceIds(List.of());

        service.poll();

        verifyNoInteractions(merossDeviceService, entityStateService);
    }

    @Test
    void fehlerBeiEinemGeraetStopptNichtDieAnderen() {
        String second = "aabbccddeeff00112233445566778899";
        properties.setDeviceIds(List.of(DEVICE_ID, second));
        when(merossDeviceService.readElectricity(DEVICE_ID))
                .thenThrow(new MerossException("Geraet antwortet nicht"));
        when(merossDeviceService.readElectricity(second)).thenReturn(new MerossElectricityReading(
                second, "Spuelmaschine", new BigDecimal("2.0"), null, null));

        service.poll();

        verify(entityStateService).reportState(any(EntityStateUpdate.class));
    }
}
