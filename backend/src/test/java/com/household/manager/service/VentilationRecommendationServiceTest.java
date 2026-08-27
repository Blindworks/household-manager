package com.household.manager.service;

import com.household.manager.config.VentilationProperties;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.VentilationAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class VentilationRecommendationServiceTest {

    private TemperatureSeriesService temperatureSeriesService;
    private VentilationRecommendationService service;

    @BeforeEach
    void setUp() {
        temperatureSeriesService = Mockito.mock(TemperatureSeriesService.class);
        service = new VentilationRecommendationService(
                temperatureSeriesService, new VentilationProperties());
    }

    private CurrentTemperatureReading reading(
            String source, String name, String temp, int ageMinutes) {
        return CurrentTemperatureReading.builder()
                .sensorId(source.toLowerCase() + ":" + name)
                .name(name)
                .source(source)
                .temperature(new BigDecimal(temp))
                .measuredAt(LocalDateTime.now().minusMinutes(ageMinutes))
                .build();
    }

    @Test
    void empfiehltLueftenWennRaumWarmUndDraussenKuehler() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        VentilationAssessment result = service.assess();

        assertThat(result.recommended()).isTrue();
        assertThat(result.outdoorTemperature()).isEqualByComparingTo("21.0");
        assertThat(result.rooms()).hasSize(1);
        assertThat(result.rooms().get(0).name()).isEqualTo("Schlafzimmer");
    }

    @Test
    void keineEmpfehlungUnterRaumschwelle() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "18.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "23.9", 5)));

        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void keineEmpfehlungBeiZuKleinerDifferenz() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "24.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void keineAussageOhneFrischenAussenwert() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 45),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        VentilationAssessment result = service.assess();

        assertThat(result.recommended()).isNull();
        assertThat(result.rooms()).isEmpty();
    }

    @Test
    void veralteteRaumwerteWerdenIgnoriert() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 45)));

        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void raeumeSindAbsteigendNachTemperaturSortiert() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "20.0", 5),
                reading("ZIGBEE", "Wohnzimmer", "25.0", 5),
                reading("ALEXA", "Schlafzimmer", "26.0", 5)));

        List<String> names = service.assess().rooms().stream()
                .map(r -> r.name()).toList();

        assertThat(names).containsExactly("Schlafzimmer", "Wohnzimmer");
    }

    @Test
    void hystereseHaeltBestehendeEmpfehlungBeiKleinererDifferenz() {
        // Erst aktivieren: Differenz 5 °C.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isTrue();

        // Differenz nur noch 1.5 °C: unter der Einschalt- (2), über der Ausschaltschwelle (1).
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "24.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isTrue();

        // Differenz 0.5 °C: unter der Ausschaltschwelle — Empfehlung erlischt.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "25.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isFalse();

        // Und bleibt aus: 1.5 °C Differenz reicht ohne bestehende Empfehlung nicht.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "24.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void fehlenderAussenwertSetztHystereseZurueck() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isTrue();

        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isNull();

        // Nach der Rückkehr gilt wieder die Einschaltschwelle (2 °C), nicht die Hysterese.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "24.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void aussenfuehlerZaehltNichtAlsRaum() {
        // Der Garten haengt an derselben Zigbee-Quelle wie die Raumsensoren: ohne
        // Ausschluss wuerde er mit dem DWD-Wert verglichen und "Lueften lohnt sich" melden.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "27.0", 5),
                reading("ZIGBEE", "Temperatur Aqara Garten", "31.0", 5)));

        VentilationAssessment result = service.assess();

        assertThat(result.recommended()).isFalse();
        assertThat(result.rooms()).isEmpty();
    }

    @Test
    void aussenfuehlerLiefertDieAussentemperaturVorDemDwdWert() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", " temperatur aqara GARTEN ", "27.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        VentilationAssessment result = service.assess();

        // Draussen (Gartenfuehler) waermer als der Raum: kein Lueften, obwohl der DWD-Wert
        // 5 Grad tiefer liegt. Name case-insensitiv und mit Rand-Leerzeichen erkannt.
        assertThat(result.outdoorTemperature()).isEqualByComparingTo("27.0");
        assertThat(result.recommended()).isFalse();
    }

    @Test
    void dwdWertGreiftWennDerAussenfuehlerVeraltetIst() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Temperatur Aqara Garten", "27.0", 45),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        VentilationAssessment result = service.assess();

        assertThat(result.outdoorTemperature()).isEqualByComparingTo("21.0");
        assertThat(result.recommended()).isTrue();
    }

    @Test
    void ersterKonfigurierterAussenfuehlerGewinnt() {
        VentilationProperties properties = new VentilationProperties();
        properties.setOutdoorSensorNames(List.of("Terrasse", "Temperatur Aqara Garten"));
        service = new VentilationRecommendationService(temperatureSeriesService, properties);
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Temperatur Aqara Garten", "23.0", 5),
                reading("ZIGBEE", "Terrasse", "22.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        VentilationAssessment result = service.assess();

        assertThat(result.outdoorTemperature()).isEqualByComparingTo("22.0");
        assertThat(result.rooms()).extracting(r -> r.name()).containsExactly("Schlafzimmer");
    }

    @Test
    void ohneKonfigurierteAussenfuehlerBleibtNurDerDwdWert() {
        VentilationProperties properties = new VentilationProperties();
        properties.setOutdoorSensorNames(List.of());
        service = new VentilationRecommendationService(temperatureSeriesService, properties);
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        VentilationAssessment result = service.assess();

        assertThat(result.outdoorTemperature()).isEqualByComparingTo("21.0");
        assertThat(result.recommended()).isTrue();
    }
}
