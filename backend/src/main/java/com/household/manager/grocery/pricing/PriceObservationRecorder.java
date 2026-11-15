package com.household.manager.grocery.pricing;

import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryPriceObservation;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.ReasonKind;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.repository.GroceryPriceObservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Hält je Produkt, Kette und ISO-Woche den niedrigsten beobachteten Grundpreis fest. Wirft nie. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceObservationRecorder {

    private final GroceryPriceObservationRepository observations;
    private final Clock clock;

    public void record(GroceryProduct product, List<MatchedOffer> matches) {
        for (MatchedOffer match : matches) {
            try {
                if (isRecordable(product, match)) {
                    upsertLowest(product, match.offer());
                }
            } catch (RuntimeException e) {
                log.warn("Preisbeobachtung für Produkt {} nicht gespeichert: {}", product.getId(), e.getMessage());
            }
        }
    }

    private boolean isRecordable(GroceryProduct product, MatchedOffer match) {
        GroceryOffer offer = match.offer();
        return match.verdict() == Verdict.MATCH
                && match.reasonKind() != ReasonKind.PACKAGING_DIFFERS
                && offer.getBasePrice() != null
                && offer.getBaseUnit() == product.getBaseUnit();
    }

    private void upsertLowest(GroceryProduct product, GroceryOffer offer) {
        LocalDate today = LocalDate.now(clock);
        String week = IsoWeeks.of(offer.getValidFrom() != null ? offer.getValidFrom() : today);
        GroceryPriceObservation existing = observations
                .findByProductIdAndChainAndIsoWeek(product.getId(), offer.getChain(), week).orElse(null);
        if (existing == null) {
            existing = new GroceryPriceObservation();
            existing.setProductId(product.getId());
            existing.setChain(offer.getChain());
            existing.setIsoWeek(week);
        } else if (existing.getBasePrice().compareTo(offer.getBasePrice()) <= 0) {
            return;
        }
        existing.setBasePrice(offer.getBasePrice());
        existing.setObservedAt(LocalDateTime.now(clock));
        observations.save(existing);
    }
}
