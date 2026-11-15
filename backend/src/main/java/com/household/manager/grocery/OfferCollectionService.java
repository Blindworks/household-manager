package com.household.manager.grocery;

import com.household.manager.grocery.entity.GroceryMarket;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GrocerySourceRun;
import com.household.manager.grocery.source.FetchContext;
import com.household.manager.grocery.source.OfferSource;
import com.household.manager.grocery.source.RawOffer;
import com.household.manager.grocery.source.SourceFetchException;
import com.household.manager.grocery.source.SourceKey;
import com.household.manager.repository.GroceryMarketRepository;
import com.household.manager.repository.GroceryOfferRepository;
import com.household.manager.repository.GrocerySourceRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ruft alle eingeschalteten Quellen einzeln ab (ein Fehler stoppt die übrigen nie), führt die Angebote zusammen
 * und ersetzt den Bestand. Hat keine Quelle etwas geliefert, bleibt der alte Bestand unangetastet.
 */
@Service
@Slf4j
public class OfferCollectionService {

    private static final int MAX_REASON_LENGTH = 300;

    public record CollectionResult(int offerCount, Map<SourceKey, String> failures) {
    }

    private final List<OfferSource> sources;
    private final GroceryProperties properties;
    private final FetchContextProvider contextProvider;
    private final GroceryMarketRepository markets;
    private final GroceryOfferRepository offers;
    private final GrocerySourceRunRepository runs;
    private final OfferMerger merger;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OfferCollectionService(List<OfferSource> sources, GroceryProperties properties,
            FetchContextProvider contextProvider, GroceryMarketRepository markets, GroceryOfferRepository offers,
            GrocerySourceRunRepository runs, OfferMerger merger, Clock clock, TransactionTemplate transactions) {
        this.sources = sources;
        this.properties = properties;
        this.contextProvider = contextProvider;
        this.markets = markets;
        this.offers = offers;
        this.runs = runs;
        this.merger = merger;
        this.clock = clock;
        this.transactions = transactions;
    }

    public CollectionResult collect() {
        FetchContext context = contextProvider.current();
        List<RawOffer> collected = new ArrayList<>();
        Map<SourceKey, String> failures = new EnumMap<>(SourceKey.class);
        boolean anySourceDelivered = false;

        for (OfferSource source : sources) {
            SourceKey key = source.key();
            if (!properties.isSourceEnabled(key) || !source.enabled()) {
                log.debug("Angebotsquelle {} ist deaktiviert", key);
                continue;
            }
            try {
                List<RawOffer> delivered = source.fetch(context);
                collected.addAll(delivered);
                anySourceDelivered |= !delivered.isEmpty();
                recordRun(key, delivered.size(), true, null);
            } catch (SourceFetchException e) {
                fail(key, e.getMessage(), failures, e);
            } catch (RuntimeException e) {
                fail(key, "Unerwarteter Fehler: " + e, failures, e);
            }
        }

        if (!anySourceDelivered) {
            log.warn("Keine Angebotsquelle hat Angebote geliefert - der bisherige Bestand bleibt erhalten");
            return new CollectionResult(0, failures);
        }
        List<GroceryOffer> merged = toEntities(merger.merge(collected, marketIds()));
        LocalDate today = LocalDate.now(clock);
        transactions.executeWithoutResult(status -> {
            offers.deleteActiveFrom(today);
            offers.saveAll(merged);
        });
        log.info("Angebotsbestand ersetzt: {} Angebote aus {} Rohangeboten, {} Quelle(n) fehlgeschlagen",
                merged.size(), collected.size(), failures.size());
        return new CollectionResult(merged.size(), failures);
    }

    private void fail(SourceKey key, String reason, Map<SourceKey, String> failures, Exception cause) {
        log.warn("Angebotsquelle {} fehlgeschlagen: {}", key, reason, cause);
        failures.put(key, reason);
        recordRun(key, 0, false, reason);
    }

    private void recordRun(SourceKey key, int offerCount, boolean ok, String reason) {
        try {
            GrocerySourceRun run = new GrocerySourceRun();
            run.setSourceKey(key);
            run.setLastRunAt(LocalDateTime.now(clock));
            run.setOfferCount(offerCount);
            run.setOk(ok);
            run.setReason(reason == null || reason.length() <= MAX_REASON_LENGTH
                    ? reason : reason.substring(0, MAX_REASON_LENGTH));
            runs.save(run);
        } catch (RuntimeException e) {
            log.warn("Quellenlauf {} konnte nicht gespeichert werden", key, e);
        }
    }

    private Map<String, Long> marketIds() {
        Map<String, Long> ids = new HashMap<>();
        for (GroceryMarket market : markets.findAll()) {
            ids.put(market.getChain().name() + "|" + market.getExternalId(), market.getId());
        }
        return ids;
    }

    private List<GroceryOffer> toEntities(List<OfferMerger.MergedOffer> merged) {
        LocalDateTime now = LocalDateTime.now(clock);
        return merged.stream().map(offer -> {
            GroceryOffer entity = new GroceryOffer();
            entity.setChain(offer.chain());
            entity.setMarketId(offer.marketId());
            entity.setSources(offer.sources().stream().sorted().map(Enum::name).collect(Collectors.joining(",")));
            entity.setSourceOfferIds(offer.sourceOfferIds().entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(",")));
            entity.setTitle(offer.title());
            entity.setBrand(offer.brand());
            entity.setDescription(offer.description());
            entity.setPackaging(offer.packaging());
            entity.setPrice(offer.price());
            entity.setOldPrice(offer.oldPrice());
            entity.setLoyaltyPrice(offer.loyaltyPrice());
            entity.setLowestPrice30d(offer.lowestPrice30Days());
            if (offer.basePrice() != null) {
                entity.setBasePrice(offer.basePrice().amount());
                entity.setBaseUnit(offer.basePrice().unit());
            }
            entity.setValidFrom(offer.validFrom());
            entity.setValidTo(offer.validTo());
            entity.setImageUrl(offer.imageUrl());
            entity.setFingerprint(offer.fingerprint());
            entity.setCollectedAt(now);
            return entity;
        }).toList();
    }
}
