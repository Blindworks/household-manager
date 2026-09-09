package com.household.manager.service;

import com.household.manager.model.entity.MeterType;
import com.household.manager.model.entity.UtilityPrice;
import com.household.manager.repository.UtilityPriceRepository;
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
class MeterCostCalculatorTest {

    @Mock
    private UtilityPriceRepository prices;
    @Mock
    private UtilityPricingSettingsService settings;

    private MeterCostCalculator calculator() {
        return new MeterCostCalculator(prices, settings);
    }

    private static UtilityPrice price(MeterType type, String price, LocalDate from, LocalDate to) {
        return UtilityPrice.builder().meterType(type).price(new BigDecimal(price))
                .validFrom(from).validTo(to).build();
    }

    @Test
    void bewertetMitDemAmDatumGueltigenPreis() {
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.ELECTRICITY)).thenReturn(List.of(
                price(MeterType.ELECTRICITY, "0.40", LocalDate.of(2026, 7, 1), null),
                price(MeterType.ELECTRICITY, "0.30", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1))));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.ELECTRICITY);

        assertThat(book.costOf(new BigDecimal("10"), LocalDate.of(2026, 6, 30)))
                .contains(new BigDecimal("3.00"));
        assertThat(book.costOf(new BigDecimal("10"), LocalDate.of(2026, 7, 1)))
                .contains(new BigDecimal("4.00"));
    }

    @Test
    void ohnePassendenPreisKeineKosten() {
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.WATER)).thenReturn(List.of(
                price(MeterType.WATER, "4.50", LocalDate.of(2026, 7, 1), null)));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.WATER);

        assertThat(book.costOf(new BigDecimal("2"), LocalDate.of(2026, 6, 1))).isEmpty();
    }

    /** validTo ist exklusiv, ein offenes Ende (null) gilt unbegrenzt. */
    @Test
    void validToIstExklusiv() {
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.WATER)).thenReturn(List.of(
                price(MeterType.WATER, "4.50", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1))));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.WATER);

        assertThat(book.costOf(new BigDecimal("1"), LocalDate.of(2026, 6, 30))).isPresent();
        assertThat(book.costOf(new BigDecimal("1"), LocalDate.of(2026, 7, 1))).isEmpty();
    }

    /** Gas: der Zaehler zaehlt m³, der Preis ist €/kWh — dazwischen steht der Faktor. */
    @Test
    void rechnetGasUeberDenKwhFaktorUm() {
        when(settings.getGasKwhPerM3()).thenReturn(new BigDecimal("10.5"));
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.GAS)).thenReturn(List.of(
                price(MeterType.GAS, "0.10", LocalDate.of(2026, 1, 1), null)));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.GAS);

        // 2 m³ × 10,5 kWh/m³ × 0,10 €/kWh = 2,10 €
        assertThat(book.costOf(new BigDecimal("2"), LocalDate.of(2026, 8, 1)))
                .contains(new BigDecimal("2.100"));
    }

    /** Ein Fehler beim Preisladen darf die Verbrauchsserie nicht mitreissen. */
    @Test
    void ladeFehlerErgibtEinLeeresPreisbuch() {
        when(prices.findByMeterTypeOrderByValidFromDesc(any())).thenThrow(new RuntimeException("db weg"));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.ELECTRICITY);

        assertThat(book.costOf(BigDecimal.ONE, LocalDate.of(2026, 8, 1))).isEmpty();
    }

    /** Der Preis wird je Typ EINMAL geladen, nicht je Woche. */
    @Test
    void laedtPreiseEinmalJeBuch() {
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.ELECTRICITY)).thenReturn(List.of(
                price(MeterType.ELECTRICITY, "0.40", LocalDate.of(2026, 1, 1), null)));
        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.ELECTRICITY);

        book.costOf(BigDecimal.ONE, LocalDate.of(2026, 8, 1));
        book.costOf(BigDecimal.ONE, LocalDate.of(2026, 8, 8));

        org.mockito.Mockito.verify(prices, org.mockito.Mockito.times(1))
                .findByMeterTypeOrderByValidFromDesc(MeterType.ELECTRICITY);
    }
}
