package com.household.manager.service;

import com.household.manager.systemlog.SystemJobMonitor;
import com.household.manager.dto.WeatherConditions;
import com.household.manager.dto.WeatherOverviewResponse;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.WeatherReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeatherPollingServiceTest {

    private static final LocalDateTime HOUR = LocalDateTime.of(2026, 10, 14, 8, 0);

    @Mock private SystemJobMonitor jobMonitor;
    @Mock private DwdWeatherService dwdWeatherService;
    @Mock private WeatherReadingRepository repository;
    @Mock private TaskScheduler taskScheduler;
    @Mock private EntityStateService entityStateService;

    @InjectMocks private WeatherPollingService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "stationId", "10637");
    }

    @Test
    void ueberschreibtDieZeileDerselbenVorhersagestunde() {
        WeatherReading existing = WeatherReading.builder()
                .id(7L).readingTime(HOUR).temperature(new BigDecimal("10.8")).build();
        when(dwdWeatherService.getOverview()).thenReturn(overview("8.8"));
        when(repository.findByReadingTime(HOUR)).thenReturn(Optional.of(existing));

        service.scheduledPoll();

        WeatherReading saved = captureSaved();
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getTemperature()).isEqualByComparingTo("8.8");
        assertThat(saved.getHumidity()).isEqualTo(85);
    }

    @Test
    void legtFuerEineNeueStundeEineNeueZeileAn() {
        when(dwdWeatherService.getOverview()).thenReturn(overview("11.5"));
        when(repository.findByReadingTime(HOUR)).thenReturn(Optional.empty());

        service.scheduledPoll();

        WeatherReading saved = captureSaved();
        assertThat(saved.getId()).isNull();
        assertThat(saved.getReadingTime()).isEqualTo(HOUR);
        assertThat(saved.getTemperature()).isEqualByComparingTo("11.5");
    }

    private WeatherReading captureSaved() {
        ArgumentCaptor<WeatherReading> captor = ArgumentCaptor.forClass(WeatherReading.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static WeatherOverviewResponse overview(String temperature) {
        return WeatherOverviewResponse.builder()
                .current(WeatherConditions.builder()
                        .time(HOUR)
                        .temperature(new BigDecimal(temperature))
                        .humidity(85)
                        .build())
                .build();
    }

    @Test
    void poll_dwdError_reportsFailure() {
        when(dwdWeatherService.getOverview()).thenThrow(new RuntimeException("DWD weg"));

        service.scheduledPoll();

        verify(jobMonitor).reportFailure(org.mockito.ArgumentMatchers.contains("DWD weg"));
    }
}
