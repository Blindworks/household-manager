package com.household.manager.service;

import com.household.manager.alexa.AlexaException;
import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaSidecarClient.SidecarAirQualityState;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlexaAirQualityPollingServiceTest {

    @Mock
    private AlexaSidecarClient sidecarClient;
    @Mock
    private AlexaAirQualityReadingRepository repository;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private EntityStateService entityStateService;

    private AlexaAirQualityPollingService service;

    @BeforeEach
    void setUp() {
        service = new AlexaAirQualityPollingService(
                sidecarClient, repository, taskScheduler, entityStateService);
    }

    private SidecarAirQualityState wohnzimmerState() {
        return new SidecarAirQualityState(
                "AAA_Sonar_1", "Luftsensor Wohnzimmer", 52,
                new BigDecimal("3.0"), new BigDecimal("128.5"), new BigDecimal("0.0"),
                new BigDecimal("22.5"), new BigDecimal("48.2"));
    }

    @Test
    void scheduledPollPersistsOneReadingPerDevice() {
        SidecarAirQualityState schlafzimmer = new SidecarAirQualityState(
                "AAA_Sonar_2", "Luftsensor Schlafzimmer", 30,
                new BigDecimal("1.0"), new BigDecimal("50.0"), new BigDecimal("0.0"),
                new BigDecimal("20.0"), new BigDecimal("55.0"));
        when(sidecarClient.getAirQualityStates()).thenReturn(List.of(wohnzimmerState(), schlafzimmer));

        service.scheduledPoll();

        ArgumentCaptor<AlexaAirQualityReading> captor = ArgumentCaptor.forClass(AlexaAirQualityReading.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        AlexaAirQualityReading first = captor.getAllValues().get(0);
        assertThat(first.getApplianceId()).isEqualTo("AAA_Sonar_1");
        assertThat(first.getDeviceName()).isEqualTo("Luftsensor Wohnzimmer");
        assertThat(first.getIaq()).isEqualTo(52);
        assertThat(first.getPm25()).isEqualByComparingTo("3.0");
        assertThat(first.getReadingTime()).isNotNull();
        assertThat(service.getStatus().getLastError()).isNull();
    }

    @Test
    void scheduledPollReportsEntityStatesForEachSensor() {
        when(sidecarClient.getAirQualityStates()).thenReturn(List.of(wohnzimmerState()));

        service.scheduledPoll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, org.mockito.Mockito.times(6)).reportState(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(EntityStateUpdate::entityId)
                .contains("sensor.alexa_aaa_sonar_1_pm25", "sensor.alexa_aaa_sonar_1_iaq");
    }

    @Test
    void scheduledPollSkipsNullSensorsInEntityStates() {
        SidecarAirQualityState partial = new SidecarAirQualityState(
                "AAA_Sonar_1", "Luftsensor Wohnzimmer", 52,
                null, null, null, new BigDecimal("22.5"), null);
        when(sidecarClient.getAirQualityStates()).thenReturn(List.of(partial));

        service.scheduledPoll();

        verify(entityStateService, org.mockito.Mockito.times(2)).reportState(any());
    }

    @Test
    void scheduledPollRecordsErrorWithoutThrowing() {
        when(sidecarClient.getAirQualityStates())
                .thenThrow(new AlexaException("Sidecar ist nicht erreichbar"));

        service.scheduledPoll();

        verify(repository, never()).save(any());
        assertThat(service.getStatus().getLastError()).contains("Sidecar ist nicht erreichbar");
        assertThat(service.getStatus().getLastPollTime()).isNotNull();
    }

    @Test
    void entityStateFailureDoesNotPreventPersistence() {
        when(sidecarClient.getAirQualityStates()).thenReturn(List.of(wohnzimmerState()));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(entityStateService).reportState(any());

        service.scheduledPoll();

        verify(repository, atLeastOnce()).save(any());
        assertThat(service.getStatus().getLastError()).isNull();
    }
}
