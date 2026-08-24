package com.household.manager.service;

import com.household.manager.dto.MeterConsumptionSeries;
import com.household.manager.model.entity.MeterReading;
import com.household.manager.model.entity.MeterType;
import com.household.manager.repository.MeterReadingRepository;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeterConsumptionSeriesServiceTest {

    @Mock
    private MeterReadingRepository repository;

    private MeterConsumptionSeriesService service;

    /** Fester "heute"-Bezug, damit die Fenstergrenzen im Test nicht mitwandern. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @BeforeEach
    void setUp() {
        service = new MeterConsumptionSeriesService(repository, () -> TODAY);
        when(repository.findByMeterTypeOrderByReadingDateAsc(any())).thenReturn(List.of());
    }

    private static MeterReading reading(LocalDate date, String value, boolean estimated) {
        return MeterReading.builder()
                .meterType(MeterType.ELECTRICITY)
                .readingValue(new BigDecimal(value))
                .readingDate(date.atStartOfDay())
                .estimated(estimated)
                .build();
    }

    private void stromAblesungen(MeterReading... readings) {
        when(repository.findByMeterTypeOrderByReadingDateAsc(MeterType.ELECTRICITY))
                .thenReturn(List.of(readings));
    }

    private MeterConsumptionSeries strom(ConsumptionRange range) {
        return service.getSeries(range).stream()
                .filter(s -> s.meterType() == MeterType.ELECTRICITY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Keine Stromserie in der Antwort"));
    }

    @Test
    void bildetProAblesungEinenBalkenAusDerDifferenzZurVorablesung() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1038", false),
                reading(LocalDate.of(2026, 8, 21), "1075", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.WEEKS_8);

        assertThat(series.unit()).isEqualTo("kWh");
        assertThat(series.points()).hasSize(2);
        assertThat(series.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(series.points().get(0).consumption()).isEqualByComparingTo("38");
        assertThat(series.points().get(1).consumption()).isEqualByComparingTo("37");
    }

    /**
     * Die allererste Ablesung hat keinen Vorgaenger, aus dem sich ein Verbrauch
     * bilden liesse - sie darf keinen Balken erzeugen.
     */
    @Test
    void laesstDieAllererstAblesungWeg() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 14), "1000", false),
                reading(LocalDate.of(2026, 8, 21), "1037", false));

        assertThat(strom(ConsumptionRange.WEEKS_8).points())
                .extracting(p -> p.periodStart())
                .containsExactly(LocalDate.of(2026, 8, 21));
    }

    @Test
    void beschriftetWochenbalkenMitDerKalenderwoche() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1038", false));

        assertThat(strom(ConsumptionRange.WEEKS_8).points().get(0).label()).isEqualTo("KW 33");
    }

    @Test
    void kennzeichnetGeschaetzteAblesungen() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1038", true));

        assertThat(strom(ConsumptionRange.WEEKS_8).points().get(0).estimated()).isTrue();
    }

    /**
     * Zaehlertausch oder -reset: ein Minusbalken waere eine Falschaussage. Die API
     * verhindert solche Werte beim Anlegen, der CSV-Import ist der Weg, auf dem sie
     * trotzdem in der Tabelle landen koennen.
     */
    @Test
    void verwirftNegativeDifferenzen() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "12", false),
                reading(LocalDate.of(2026, 8, 21), "50", false));

        assertThat(strom(ConsumptionRange.WEEKS_8).points())
                .extracting(p -> p.periodStart())
                .containsExactly(LocalDate.of(2026, 8, 21));
    }

    /**
     * Das Fenster von WEEKS_8 beginnt am 2026-06-29. Die Januar-Differenz faellt
     * heraus; die Differenz vom 14.08. bleibt drin, auch wenn ihre Vorablesung weit
     * davor liegt - der Balken gehoert zum Ablesedatum.
     */
    @Test
    void laesstAblesungenVorDemFensterWeg() {
        stromAblesungen(
                reading(LocalDate.of(2026, 1, 9), "500", false),
                reading(LocalDate.of(2026, 1, 16), "540", false),
                reading(LocalDate.of(2026, 8, 14), "1000", false),
                reading(LocalDate.of(2026, 8, 21), "1038", false));

        assertThat(strom(ConsumptionRange.WEEKS_8).points())
                .extracting(p -> p.periodStart())
                .containsExactly(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 21));
    }

    /**
     * Ein Typ ohne Ablesungen erzeugt keine leere Serie - das Frontend soll keine
     * leeren Diagramme zeichnen muessen.
     */
    @Test
    void laesstTypenOhneAblesungenAus() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 14), "1000", false),
                reading(LocalDate.of(2026, 8, 21), "1038", false));

        assertThat(service.getSeries(ConsumptionRange.WEEKS_8))
                .extracting(MeterConsumptionSeries::meterType)
                .containsExactly(MeterType.ELECTRICITY);
    }

    /** Ein kaputter Typ darf die anderen nicht mitreissen. */
    @Test
    void isoliertFehlerJeZaehlertyp() {
        when(repository.findByMeterTypeOrderByReadingDateAsc(MeterType.GAS))
                .thenThrow(new RuntimeException("DB weg"));
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 14), "1000", false),
                reading(LocalDate.of(2026, 8, 21), "1038", false));

        assertThat(service.getSeries(ConsumptionRange.WEEKS_8))
                .extracting(MeterConsumptionSeries::meterType)
                .containsExactly(MeterType.ELECTRICITY);
    }

    @Test
    void nenntDieEinheitJeZaehlertyp() {
        when(repository.findByMeterTypeOrderByReadingDateAsc(MeterType.WATER)).thenReturn(List.of(
                MeterReading.builder().meterType(MeterType.WATER)
                        .readingValue(new BigDecimal("100"))
                        .readingDate(LocalDate.of(2026, 8, 14).atStartOfDay()).build(),
                MeterReading.builder().meterType(MeterType.WATER)
                        .readingValue(new BigDecimal("102"))
                        .readingDate(LocalDate.of(2026, 8, 21).atStartOfDay()).build()));

        assertThat(service.getSeries(ConsumptionRange.WEEKS_8))
                .filteredOn(s -> s.meterType() == MeterType.WATER)
                .extracting(MeterConsumptionSeries::unit)
                .containsExactly("m³");
    }

    /**
     * Zwei Ablesungen derselben ISO-Woche (Freitagsablesung plus eine Korrektur am
     * Samstag) traegen dasselbe Label. Das Frontend zeichnet daraus eine
     * Kategorienachse - bei doppeltem Kategorienamen landen beide Werte auf derselben
     * Position und ein Balken verschwaende lautlos. Deshalb werden sie zusammengefasst.
     */
    @Test
    void fasstZweiAblesungenDerselbenWocheZusammen() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1030", false),
                reading(LocalDate.of(2026, 8, 15), "1038", true));

        MeterConsumptionSeries series = strom(ConsumptionRange.WEEKS_8);

        assertThat(series.points()).hasSize(1);
        assertThat(series.points().get(0).label()).isEqualTo("KW 33");
        assertThat(series.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(series.points().get(0).consumption()).isEqualByComparingTo("38");
        assertThat(series.points().get(0).estimated()).isTrue();
    }

    @Test
    void fasstWochenZuKalendermonatenZusammen() {
        stromAblesungen(
                reading(LocalDate.of(2026, 7, 3), "1000", false),
                reading(LocalDate.of(2026, 7, 10), "1010", false),
                reading(LocalDate.of(2026, 7, 17), "1030", false),
                reading(LocalDate.of(2026, 8, 7), "1075", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);

        assertThat(series.points()).hasSize(2);
        assertThat(series.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(series.points().get(0).label()).isEqualTo("Juli 26");
        assertThat(series.points().get(0).consumption()).isEqualByComparingTo("30");
        assertThat(series.points().get(1).periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(series.points().get(1).consumption()).isEqualByComparingTo("45");
    }

    /**
     * Eine Ablesewoche liegt oft quer ueber den Monatswechsel. Sie zaehlt vollstaendig
     * in den Monat ihres Ablesedatums - der Balken entspricht so weiterhin echten
     * Ablesungen.
     */
    @Test
    void schlaegtEineWocheUeberDemMonatswechselDemAblesemonatZu() {
        stromAblesungen(
                reading(LocalDate.of(2026, 6, 26), "1000", false),
                reading(LocalDate.of(2026, 7, 3), "1020", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);

        assertThat(series.points()).hasSize(1);
        assertThat(series.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(series.points().get(0).consumption()).isEqualByComparingTo("20");
    }

    /**
     * Sonst verschwaende eine geschaetzte Woche in einem sonst echten Monat spurlos.
     */
    @Test
    void markiertEinenMonatSobaldEineWocheGeschaetztWar() {
        stromAblesungen(
                reading(LocalDate.of(2026, 7, 3), "1000", false),
                reading(LocalDate.of(2026, 7, 10), "1010", true),
                reading(LocalDate.of(2026, 7, 17), "1030", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);

        assertThat(series.points()).hasSize(1);
        assertThat(series.points().get(0).estimated()).isTrue();
    }

    @Test
    void erzeugtKeineMonatsbalkenOhneAblesung() {
        stromAblesungen(
                reading(LocalDate.of(2026, 5, 1), "900", false),
                reading(LocalDate.of(2026, 5, 8), "920", false),
                reading(LocalDate.of(2026, 8, 7), "1075", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);

        // Mai (20) und August (155) - Juni und Juli fehlen ganz, statt als 0 dazustehen.
        assertThat(series.points())
                .extracting(p -> p.periodStart())
                .containsExactly(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 8, 1));
    }

    /**
     * Die Klasse hat zwei Konstruktoren: einen fuer Spring und einen fuer die Tests
     * mit festem "heute". Spring waehlt nur dann selbsttaetig einen aus, wenn es
     * genau EINER ist - sonst sucht es den Default-Konstruktor und der gesamte
     * Anwendungsstart bricht ab (real passiert 2026-08-24).
     *
     * <p>Kein Unit-Test faengt das, weil sie den Service selbst bauen, und kein
     * WebMvcTest faengt es, weil der den Service mockt. Deshalb hier ein echter,
     * winziger Spring-Kontext.
     */
    @Test
    void laesstSichVonSpringInstanziieren() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterReadingRepository.class, () -> repository);
            context.registerBean(MeterConsumptionSeriesService.class);
            context.refresh();

            // Den Bean auch benutzen: das belegt zugleich, dass Spring ihm das
            // Repository gereicht hat, und haelt den Repository-Stub in Gebrauch.
            assertThat(context.getBean(MeterConsumptionSeriesService.class)
                    .getSeries(ConsumptionRange.WEEKS_8)).isEmpty();
        }
    }
}
