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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerossElectricityPollingServiceTest {

    private static final String DEVICE_ID = "2112156531504590863548e1e9817420";
    private static final String SECOND_DEVICE_ID = "aabbccddeeff00112233445566778899";

    @Mock
    private MerossDeviceService merossDeviceService;
    @Mock
    private EntityStateService entityStateService;

    private MerossElectricityPollingProperties properties;
    private MerossElectricityPollingService service;

    @BeforeEach
    void setUp() {
        properties = new MerossElectricityPollingProperties();
        service = new MerossElectricityPollingService(properties, merossDeviceService, entityStateService);
    }

    private MerossElectricityReading reading(String deviceId, String name, String watts) {
        return new MerossElectricityReading(deviceId, name, new BigDecimal(watts),
                new BigDecimal("230.1"), new BigDecimal("5.432"));
    }

    @Test
    void meldetLeistungAlsSensorEntitaet() {
        when(merossDeviceService.readElectricityOfAllPlugs())
                .thenReturn(List.of(reading(DEVICE_ID, "Waschmaschine", "1234.6")));

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
    void meldetJedeMessfaehigeSteckdose() {
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(List.of(
                reading(DEVICE_ID, "Waschmaschine", "1234.6"),
                reading(SECOND_DEVICE_ID, "Spuelmaschine", "2.0")));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).extracting(EntityStateUpdate::entityId).containsExactly(
                "sensor.meross_" + DEVICE_ID + "_power",
                "sensor.meross_" + SECOND_DEVICE_ID + "_power");
    }

    @Test
    void lesefehlerFuehrtNichtZuUnavailableReport() {
        when(merossDeviceService.readElectricityOfAllPlugs())
                .thenThrow(new MerossException("Cloud antwortet nicht"));

        service.poll();

        verifyNoInteractions(entityStateService);
    }

    @Test
    void ohneMessfaehigeSteckdoseWirdNichtsGemeldet() {
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(List.of());

        service.poll();

        verifyNoInteractions(entityStateService);
    }

    @Test
    void deaktiviertesPollingMeldetNichts() {
        properties.setEnabled(false);

        service.poll();

        verifyNoInteractions(merossDeviceService, entityStateService);
    }
}
