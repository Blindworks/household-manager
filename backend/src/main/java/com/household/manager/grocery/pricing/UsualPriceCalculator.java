package com.household.manager.grocery.pricing;

import com.household.manager.grocery.entity.GroceryPriceObservation;
import com.household.manager.repository.GroceryPriceObservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Üblicher Grundpreis: Median der Beobachtungen der letzten 12 ISO-Wochen (inklusive der laufenden). */
@Component
@RequiredArgsConstructor
public class UsualPriceCalculator {

    static final int WINDOW_WEEKS = 12;
    static final int MIN_OBSERVATIONS = 6;
    static final int MIN_WEEKS = 3;

    private final GroceryPriceObservationRepository observations;

    public record UsualPrice(BigDecimal median, int observations, int distinctWeeks, boolean warmingUp) {
    }

    public UsualPrice usual(long productId, LocalDate today) {
        List<String> weeks = new ArrayList<>();
        for (int i = 0; i < WINDOW_WEEKS; i++) {
            weeks.add(IsoWeeks.of(today.minusWeeks(i)));
        }
        List<GroceryPriceObservation> found = observations.findByProductIdAndIsoWeekIn(productId, weeks);
        Set<String> distinct = new HashSet<>();
        found.forEach(o -> distinct.add(o.getIsoWeek()));
        boolean warmingUp = found.size() < MIN_OBSERVATIONS || distinct.size() < MIN_WEEKS;
        return new UsualPrice(median(found), found.size(), distinct.size(), warmingUp);
    }

    private BigDecimal median(List<GroceryPriceObservation> found) {
        if (found.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = found.stream().map(GroceryPriceObservation::getBasePrice)
                .sorted(Comparator.naturalOrder()).toList();
        int n = sorted.size();
        BigDecimal value = n % 2 == 1
                ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 3, RoundingMode.HALF_UP);
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
