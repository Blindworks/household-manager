package com.household.manager.grocery;

import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.pricing.NormalPriceEstimator;
import com.household.manager.repository.GroceryProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hält die Normalpreis-Schätzungen aktuell: geschätzt wird im vollen Lauf für jedes aktive Produkt ohne eigenen
 * Normalpreis, dessen Schätzung fehlt oder älter als 30 Tage ist, höchstens 20 je Lauf. Wirft nie.
 */
@Slf4j
@Component
public class NormalPriceEstimates {

    static final Duration MAX_AGE = Duration.ofDays(30);
    static final int MAX_PER_RUN = 20;

    private final NormalPriceEstimator estimator;
    private final GroceryProductRepository products;
    private final Clock clock;

    public NormalPriceEstimates(NormalPriceEstimator estimator, GroceryProductRepository products, Clock clock) {
        this.estimator = estimator;
        this.products = products;
        this.clock = clock;
    }

    /** Schätzt die fälligen Produkte und setzt den Wert auch an der übergebenen Entität. Liefert die Zahl der Aufrufe. */
    public int refreshDue(List<GroceryProduct> productList, Map<Long, List<MatchedOffer>> matches) {
        LocalDateTime now = LocalDateTime.now(clock);
        int calls = 0;
        for (GroceryProduct product : productList) {
            if (calls >= MAX_PER_RUN) {
                log.info("Normalpreis-Schätzung: Deckel von {} je Lauf erreicht, Rest im nächsten Lauf", MAX_PER_RUN);
                break;
            }
            if (!isDue(product, now)) {
                continue;
            }
            calls++;
            List<GroceryOffer> evidence = matches.getOrDefault(product.getId(), List.of()).stream()
                    .map(MatchedOffer::offer).toList();
            Optional<BigDecimal> estimate = estimator.estimate(product, evidence);
            if (estimate.isEmpty()) {
                continue;
            }
            try {
                products.updateEstimate(product.getId(), estimate.get(), now);
                product.setEstimatedPricePerUnit(estimate.get());
                product.setEstimatedAt(now);
                log.info("Normalpreis für '{}' geschätzt: {} € je {}", product.getName(), estimate.get(),
                        product.getBaseUnit());
            } catch (RuntimeException e) {
                log.warn("Normalpreis-Schätzung für Produkt {} nicht gespeichert: {}", product.getId(),
                        e.getMessage());
            }
        }
        return calls;
    }

    static boolean isDue(GroceryProduct product, LocalDateTime now) {
        if (product.getNormalPricePerUnit() != null) {
            return false;
        }
        return product.getEstimatedPricePerUnit() == null || product.getEstimatedAt() == null
                || product.getEstimatedAt().isBefore(now.minus(MAX_AGE));
    }
}
