package com.household.manager.service;

import com.household.manager.model.entity.MeterType;
import com.household.manager.model.entity.UtilityPrice;
import com.household.manager.repository.UtilityPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Einzige Definition von "was kostet Menge X eines Zaehlertyps am Datum Y".
 *
 * <p>Die Preise eines Typs werden EINMAL je {@link PriceBook} geladen und in Java
 * zugeordnet — bei 24 Monaten sind das bis zu 104 Ablesewochen, eine Query je Woche
 * waere unnoetig. Gas zaehlt m³, der Gaspreis ist €/kWh: dazwischen steht der
 * pflegbare Faktor aus {@link UtilityPricingSettingsService}.
 *
 * <p>Nur der Arbeitspreis: Grundgebuehr, Netzentgelte und Steuern sind nicht Teil
 * des Modells — das Ergebnis sind verbrauchsabhaengige Kosten, kein Rechnungsbetrag.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MeterCostCalculator {

    private final UtilityPriceRepository priceRepository;
    private final UtilityPricingSettingsService settings;

    /**
     * Preisbuch eines Typs. Ein Ladefehler ergibt ein leeres Buch (alle Kosten
     * {@code empty}) statt einer Ausnahme — die Verbrauchsserie kommt trotzdem.
     */
    public PriceBook priceBookFor(MeterType type) {
        try {
            List<UtilityPrice> prices = priceRepository.findByMeterTypeOrderByValidFromDesc(type);
            BigDecimal unitFactor = type == MeterType.GAS ? settings.getGasKwhPerM3() : BigDecimal.ONE;
            return new PriceBook(prices, unitFactor);
        } catch (Exception e) {
            log.warn("Preise fuer {} konnten nicht geladen werden, Kosten entfallen", type, e);
            return new PriceBook(List.of(), BigDecimal.ONE);
        }
    }

    /** Alle Preise eines Typs plus Einheitenfaktor; unveraenderlich. */
    public static final class PriceBook {
        private final List<UtilityPrice> prices;
        private final BigDecimal unitFactor;

        PriceBook(List<UtilityPrice> prices, BigDecimal unitFactor) {
            this.prices = List.copyOf(prices);
            this.unitFactor = unitFactor;
        }

        /** Kosten der Menge zum am Datum gueltigen Preis, unrundet; leer ohne Preis. */
        public Optional<BigDecimal> costOf(BigDecimal amount, LocalDate date) {
            return priceAt(date).map(price -> amount.multiply(unitFactor).multiply(price));
        }

        private Optional<BigDecimal> priceAt(LocalDate date) {
            return prices.stream()
                    .filter(p -> !p.getValidFrom().isAfter(date))
                    .filter(p -> p.getValidTo() == null || p.getValidTo().isAfter(date))
                    .map(UtilityPrice::getPrice)
                    .findFirst();
        }
    }
}
