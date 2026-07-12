package com.household.manager.service;

import com.household.manager.dto.AlexaAirQualityReadingResponse;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlexaAirQualityReadingServiceTest {

    @Mock
    private AlexaAirQualityReadingRepository repository;

    @InjectMocks
    private AlexaAirQualityReadingService service;

    private AlexaAirQualityReading reading(String applianceId, LocalDateTime time) {
        return AlexaAirQualityReading.builder()
                .id(1L)
                .applianceId(applianceId)
                .deviceName("Luftsensor Wohnzimmer")
                .readingTime(time)
                .iaq(52)
                .pm25(new BigDecimal("3.0"))
                .build();
    }

    @Test
    void latestReturnsNewestReadingPerDevice() {
        LocalDateTime now = LocalDateTime.now();
        when(repository.findDistinctApplianceIds()).thenReturn(List.of("A", "B"));
        when(repository.findTopByApplianceIdOrderByReadingTimeDesc("A"))
                .thenReturn(Optional.of(reading("A", now)));
        when(repository.findTopByApplianceIdOrderByReadingTimeDesc("B"))
                .thenReturn(Optional.of(reading("B", now.minusMinutes(5))));

        List<AlexaAirQualityReadingResponse> latest = service.getLatestPerDevice();

        assertThat(latest).hasSize(2);
        assertThat(latest.get(0).getApplianceId()).isEqualTo("A");
        assertThat(latest.get(0).getIaq()).isEqualTo(52);
    }

    @Test
    void getAllReadingsMapsEntities() {
        when(repository.findAllByOrderByReadingTimeAsc())
                .thenReturn(List.of(reading("A", LocalDateTime.now())));

        List<AlexaAirQualityReadingResponse> all = service.getAllReadings();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getDeviceName()).isEqualTo("Luftsensor Wohnzimmer");
        assertThat(all.get(0).getPm25()).isEqualByComparingTo("3.0");
    }
}
