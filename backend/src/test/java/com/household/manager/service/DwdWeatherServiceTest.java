package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.WeatherOverviewResponse;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DwdWeatherServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DwdWeatherService service =
            new DwdWeatherService(null, objectMapper);

    /** forecastStart = 2026-06-21T12:00:00 Europe/Berlin, timeStep = 1h. */
    private static final long FORECAST_START =
            ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))
                    .toInstant().toEpochMilli();

    private String sampleJson() {
        return "{\"10637\":{"
                + "\"forecastStart\":" + FORECAST_START + ","
                + "\"forecast1\":{"
                + "\"start\":" + FORECAST_START + ",\"timeStep\":3600000,"
                + "\"temperature\":[205,210,230,235],"
                + "\"precipitationTotal\":[0,0,5,12],"
                + "\"icon1h\":[1,1,8,8],"
                + "\"windSpeed\":[120,130,140,150],"
                + "\"windDirection\":[180,185,190,200],"
                + "\"humidity\":[60,62,70,72],"
                + "\"surfacePressure\":[10132,10130,10125,10120]"
                + "},"
                + "\"warnings\":[{"
                + "\"warnId\":42,\"event\":\"GEWITTER\",\"level\":3,"
                + "\"headline\":\"Amtliche WARNUNG vor GEWITTER\","
                + "\"descriptionText\":\"Es treten Gewitter auf.\","
                + "\"instruction\":\"Meiden Sie freie Flaechen.\","
                + "\"start\":" + FORECAST_START + ",\"end\":" + (FORECAST_START + 7200000) + "}]"
                + "}}";
    }

    @Test
    void parsesCurrentConditionsWithScaling() {
        WeatherOverviewResponse result = service.parseOverview(sampleJson(), "10637");

        assertThat(result.getCurrent().getTemperature()).isEqualByComparingTo("20.5");
        assertThat(result.getCurrent().getHumidity()).isEqualTo(60);
        assertThat(result.getCurrent().getPressure()).isEqualByComparingTo("1013.2");
        assertThat(result.getCurrent().getIcon()).isEqualTo(1);
    }

    @Test
    void buildsHourlyForecast() {
        WeatherOverviewResponse result = service.parseOverview(sampleJson(), "10637");

        assertThat(result.getHourlyForecast()).hasSize(4);
        assertThat(result.getHourlyForecast().get(2).getTemperature())
                .isEqualByComparingTo("23.0");
        assertThat(result.getHourlyForecast().get(2).getPrecipitation())
                .isEqualByComparingTo("0.5");
    }

    @Test
    void detectsNextRainAtFirstPositivePrecipitation() {
        WeatherOverviewResponse result = service.parseOverview(sampleJson(), "10637");

        assertThat(result.getNextRain())
                .isEqualTo(ZonedDateTime.of(2026, 6, 21, 14, 0, 0, 0,
                        ZoneId.of("Europe/Berlin")).toLocalDateTime());
    }

    @Test
    void mapsWarnings() {
        WeatherOverviewResponse result = service.parseOverview(sampleJson(), "10637");

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).getEvent()).isEqualTo("GEWITTER");
        assertThat(result.getWarnings().get(0).getLevel()).isEqualTo(3);
        assertThat(result.getWarnings().get(0).getInstruction())
                .isEqualTo("Meiden Sie freie Flaechen.");
    }

    @Test
    void returnsNoNextRainWhenDry() {
        String dry = sampleJson().replace("[0,0,5,12]", "[0,0,0,0]");
        WeatherOverviewResponse result = service.parseOverview(dry, "10637");

        assertThat(result.getNextRain()).isNull();
    }
}
