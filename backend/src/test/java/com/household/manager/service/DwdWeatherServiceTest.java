package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.WeatherOverviewResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

    private static final Instant AT_START = Instant.ofEpochMilli(FORECAST_START);

    /** 14:30 – mitten in der dritten Forecast-Stunde (Index 2). */
    private static final Instant TWO_AND_A_HALF_HOURS_LATER =
            Instant.ofEpochMilli(FORECAST_START + 2 * 3600000L + 1800000L);

    private String sampleJson() {
        return "{\"10637\":{"
                + "\"forecastStart\":" + FORECAST_START + ","
                + "\"forecast1\":{"
                + "\"start\":" + FORECAST_START + ",\"timeStep\":3600000,"
                + "\"temperature\":[205,210,230,235],"
                + "\"precipitationTotal\":[0,0,5,12],"
                + "\"icon1h\":[1,1,8,8],"
                + "\"humidity\":[600,620,700,720],"
                + "\"surfacePressure\":[10132,10130,10125,10120]"
                + "},"
                + "\"days\":[{\"windSpeed\":93,\"windDirection\":380}],"
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
        WeatherOverviewResponse result =
                service.parseOverview(sampleJson(), "10637", AT_START);

        assertThat(result.getCurrent().getTemperature()).isEqualByComparingTo("20.5");
        assertThat(result.getCurrent().getHumidity()).isEqualTo(60);
        assertThat(result.getCurrent().getPressure()).isEqualByComparingTo("1013.2");
        assertThat(result.getCurrent().getIcon()).isEqualTo(1);
        assertThat(result.getCurrent().getWindSpeed()).isEqualByComparingTo("9.3");
        assertThat(result.getCurrent().getWindDirection()).isEqualTo(20);
    }

    @Test
    void currentConditionsUseTheHourMatchingNow() {
        WeatherOverviewResponse result =
                service.parseOverview(sampleJson(), "10637", TWO_AND_A_HALF_HOURS_LATER);

        assertThat(result.getCurrent().getTemperature()).isEqualByComparingTo("23.0");
        assertThat(result.getCurrent().getHumidity()).isEqualTo(70);
        assertThat(result.getCurrent().getPressure()).isEqualByComparingTo("1012.5");
        assertThat(result.getCurrent().getIcon()).isEqualTo(8);
    }

    @Test
    void hourlyForecastStartsAtCurrentHour() {
        WeatherOverviewResponse result =
                service.parseOverview(sampleJson(), "10637", TWO_AND_A_HALF_HOURS_LATER);

        assertThat(result.getHourlyForecast()).hasSize(2);
        assertThat(result.getHourlyForecast().get(0).getTime())
                .isEqualTo(ZonedDateTime.of(2026, 6, 21, 14, 0, 0, 0,
                        ZoneId.of("Europe/Berlin")).toLocalDateTime());
        assertThat(result.getHourlyForecast().get(0).getTemperature())
                .isEqualByComparingTo("23.0");
    }

    @Test
    void clampsToFirstHourWhenNowBeforeForecastStart() {
        Instant beforeStart = Instant.ofEpochMilli(FORECAST_START - 3600000L);

        WeatherOverviewResponse result =
                service.parseOverview(sampleJson(), "10637", beforeStart);

        assertThat(result.getCurrent().getTemperature()).isEqualByComparingTo("20.5");
        assertThat(result.getHourlyForecast()).hasSize(4);
    }

    @Test
    void clampsToLastHourWhenNowAfterForecastEnd() {
        Instant afterEnd = Instant.ofEpochMilli(FORECAST_START + 10 * 3600000L);

        WeatherOverviewResponse result =
                service.parseOverview(sampleJson(), "10637", afterEnd);

        assertThat(result.getCurrent().getTemperature()).isEqualByComparingTo("23.5");
        assertThat(result.getHourlyForecast()).hasSize(1);
    }

    @Test
    void buildsHourlyForecast() {
        WeatherOverviewResponse result =
                service.parseOverview(sampleJson(), "10637", AT_START);

        assertThat(result.getHourlyForecast()).hasSize(4);
        assertThat(result.getHourlyForecast().get(2).getTemperature())
                .isEqualByComparingTo("23.0");
        assertThat(result.getHourlyForecast().get(2).getPrecipitation())
                .isEqualByComparingTo("0.5");
    }

    @Test
    void detectsNextRainAtFirstPositivePrecipitation() {
        WeatherOverviewResponse result =
                service.parseOverview(sampleJson(), "10637", AT_START);

        assertThat(result.getNextRain())
                .isEqualTo(ZonedDateTime.of(2026, 6, 21, 14, 0, 0, 0,
                        ZoneId.of("Europe/Berlin")).toLocalDateTime());
    }

    @Test
    void mapsWarnings() {
        WeatherOverviewResponse result =
                service.parseOverview(sampleJson(), "10637", AT_START);

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).getEvent()).isEqualTo("GEWITTER");
        assertThat(result.getWarnings().get(0).getLevel()).isEqualTo(3);
        assertThat(result.getWarnings().get(0).getInstruction())
                .isEqualTo("Meiden Sie freie Flaechen.");
    }

    @Test
    void returnsNoNextRainWhenDry() {
        String dry = sampleJson().replace("[0,0,5,12]", "[0,0,0,0]");
        WeatherOverviewResponse result =
                service.parseOverview(dry, "10637", AT_START);

        assertThat(result.getNextRain()).isNull();
    }
}
