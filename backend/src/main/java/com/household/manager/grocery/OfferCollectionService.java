package com.household.manager.grocery;

import com.household.manager.grocery.entity.GroceryMarket;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GrocerySourceRun;
import com.household.manager.grocery.pricing.BasePrice;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Ruft alle eingeschalteten Quellen einzeln ab (ein Fehler stoppt die übrigen nie), führt die Angebote zusammen
 * und ersetzt den Bestand. Hat keine Quelle etwas geliefert, bleibt der alte Bestand unangetastet.
 */
@Service
@Slf4j
public class OfferCollectionService {

    // Spaltenlängen aus Changeset 20261115-0102
    static final int SOURCES_LENGTH = 100;
    static final int SOURCE_OFFER_IDS_LENGTH = 500;
    static final int TITLE_LENGTH = 300;
    static final int BRAND_LENGTH = 150;
    static final int PACKAGING_LENGTH = 200;
    static final int IMAGE_URL_LENGTH = 500;


    private static final int MAX_REASON_LENGTH = 300;
    private static final String EMPTY_REASON = "Keine Angebote geliefert";

    /**
     * {@code delivered}: Zahl der gültigen Angebote je erfolgreicher Quelle; {@code rawCount}: Rohangebote vor dem
     * Zusammenführen (inklusive des Bestands ausgefallener Quellen).
     */
    public record CollectionResult(int offerCount, Map<SourceKey, String> failures, Map<SourceKey, Integer> delivered,
                                   int rawCount) {

        public CollectionResult(int offerCount, Map<SourceKey, String> failures) {
            this(offerCount, failures, Map.of(), 0);
        }
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
    private final AtomicBoolean running = new AtomicBoolean();

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

    /**
     * @throws IllegalStateException wenn bereits ein Sammellauf läuft
     */
    public CollectionResult collect() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Ein Sammellauf läuft bereits");
        }
        try {
            return runCollection();
        } finally {
            running.set(false);
        }
    }

    private CollectionResult runCollection() {
        FetchContext context = contextProvider.current();
        LocalDate today = LocalDate.now(clock);
        List<GroceryMarket> marketList = markets.findAll();
        List<GroceryOffer> stock = inScope(offers.findActiveFrom(today), context, marketList);
        List<RawOffer> collected = new ArrayList<>();
        Map<SourceKey, String> failures = new EnumMap<>(SourceKey.class);
        Map<SourceKey, Integer> delivered = new EnumMap<>(SourceKey.class);
        boolean anySourceDelivered = false;

        for (OfferSource source : sources) {
            SourceKey key = source.key();
            if (!properties.isSourceEnabled(key) || !source.enabled()) {
                log.debug("Angebotsquelle {} ist deaktiviert", key);
                continue;
            }
            try {
                List<RawOffer> fetched = source.fetch(context).stream()
                        .filter(o -> o.validTo() == null || !o.validTo().isBefore(today))
                        .toList();
                if (fetched.isEmpty() && stockContains(stock, key)) {
                    fail(key, EMPTY_REASON, failures, null);
                    continue;
                }
                collected.addAll(fetched);
                anySourceDelivered |= !fetched.isEmpty();
                delivered.put(key, fetched.size());
                recordRun(key, fetched.size(), true, null);
            } catch (SourceFetchException e) {
                fail(key, e.getMessage(), failures, e);
            } catch (RuntimeException e) {
                fail(key, "Unerwarteter Fehler: " + e, failures, e);
            }
        }

        if (!anySourceDelivered) {
            log.warn("Keine Angebotsquelle hat Angebote geliefert - der bisherige Bestand bleibt erhalten");
            return new CollectionResult(0, failures, delivered, collected.size());
        }
        Map<Long, String> externalIds = marketList.stream()
                .collect(Collectors.toMap(GroceryMarket::getId, GroceryMarket::getExternalId, (a, b) -> a));
        for (SourceKey failed : failures.keySet()) {
            stock.stream().filter(o -> hasSource(o, failed))
                    .forEach(o -> collected.add(rehydrate(o, failed, externalIds)));
        }
        List<GroceryOffer> merged = toEntities(merger.merge(collected, marketIds(marketList)));
        transactions.executeWithoutResult(status -> {
            offers.deleteActiveFrom(today);
            offers.saveAll(merged);
        });
        log.info("Angebotsbestand ersetzt: {} Angebote aus {} Rohangeboten, {} Quelle(n) fehlgeschlagen",
                merged.size(), collected.size(), failures.size());
        return new CollectionResult(merged.size(), failures, delivered, collected.size());
    }

    /**
     * Nur Bestand, den die aktuelle Auswahl noch verlangt: Kette eingeschaltet und – bei marktgenauen Angeboten –
     * Markt gewählt. Sonst gälte eine Quelle nach dem Abwählen ihres Markts als ausgefallen („Keine Angebote
     * geliefert“) und ihre alten Angebote würden bis zum Ablauf weiter wiederbelebt.
     */
    private static List<GroceryOffer> inScope(List<GroceryOffer> stock, FetchContext context,
                                              List<GroceryMarket> marketList) {
        java.util.Set<String> selected = context.selectedMarkets().stream()
                .map(m -> m.chain().name() + "|" + m.externalId())
                .collect(Collectors.toSet());
        Map<Long, String> marketKeys = marketList.stream().collect(Collectors.toMap(GroceryMarket::getId,
                m -> m.getChain().name() + "|" + m.getExternalId(), (a, b) -> a));
        return stock.stream()
                .filter(o -> context.enabledChains().contains(o.getChain()))
                .filter(o -> o.getMarketId() == null || selected.contains(marketKeys.get(o.getMarketId())))
                .toList();
    }

    private static boolean stockContains(List<GroceryOffer> stock, SourceKey key) {
        return stock.stream().anyMatch(o -> hasSource(o, key));
    }

    private static boolean hasSource(GroceryOffer offer, SourceKey key) {
        return offer.getSources() != null && List.of(offer.getSources().split(",")).contains(key.name());
    }

    /** Alte Angebote einer ausgefallenen Quelle bleiben bis zum Ablauf ihrer Gültigkeit erhalten. */
    private static RawOffer rehydrate(GroceryOffer offer, SourceKey key, Map<Long, String> externalIds) {
        String sourceOfferId = null;
        if (offer.getSourceOfferIds() != null) {
            for (String entry : offer.getSourceOfferIds().split(",")) {
                if (entry.startsWith(key.name() + ":")) {
                    sourceOfferId = entry.substring(key.name().length() + 1);
                }
            }
        }
        return RawOffer.builder()
                .source(key)
                .sourceOfferId(sourceOfferId)
                .chain(offer.getChain())
                .marketExternalId(offer.getMarketId() == null ? null : externalIds.get(offer.getMarketId()))
                .title(offer.getTitle())
                .brand(offer.getBrand())
                .description(offer.getDescription())
                .packaging(offer.getPackaging())
                .price(offer.getPrice())
                .oldPrice(offer.getOldPrice())
                .loyaltyPrice(offer.getLoyaltyPrice())
                .lowestPrice30Days(offer.getLowestPrice30d())
                .basePrice(offer.getBasePrice() == null ? null : new BasePrice(offer.getBasePrice(), offer.getBaseUnit()))
                .validFrom(offer.getValidFrom())
                .validTo(offer.getValidTo())
                .imageUrl(offer.getImageUrl())
                .build();
    }

    private void fail(SourceKey key, String reason, Map<SourceKey, String> failures, Exception cause) {
        if (cause == null) {
            log.warn("Angebotsquelle {} fehlgeschlagen: {}", key, reason);
        } else {
            log.warn("Angebotsquelle {} fehlgeschlagen: {}", key, reason, cause);
        }
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

    private Map<String, Long> marketIds(List<GroceryMarket> marketList) {
        Map<String, Long> ids = new HashMap<>();
        for (GroceryMarket market : marketList) {
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
            entity.setSources(cut(offer.sources().stream().sorted().map(Enum::name)
                    .collect(Collectors.joining(",")), SOURCES_LENGTH));
            entity.setSourceOfferIds(joinFitting(offer.sourceOfferIds().entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue()).toList(), SOURCE_OFFER_IDS_LENGTH));
            entity.setTitle(cut(offer.title(), TITLE_LENGTH));
            entity.setBrand(cut(offer.brand(), BRAND_LENGTH));
            entity.setDescription(offer.description());
            entity.setPackaging(cut(offer.packaging(), PACKAGING_LENGTH));
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
            entity.setImageUrl(offer.imageUrl() == null || offer.imageUrl().length() > IMAGE_URL_LENGTH
                    ? null : offer.imageUrl());
            entity.setFingerprint(offer.fingerprint());
            entity.setCollectedAt(now);
            return entity;
        }).toList();
    }

    /** Kürzt auf die Spaltenlänge (Changeset 20261115-0102); null bleibt null. */
    static String cut(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Verbindet ganze Einträge mit Komma, solange sie passen; ein zerschnittener Eintrag wäre nicht mehr lesbar. */
    static String joinFitting(List<String> entries, int maxLength) {
        StringBuilder joined = new StringBuilder();
        for (String entry : entries) {
            int needed = joined.isEmpty() ? entry.length() : joined.length() + 1 + entry.length();
            if (needed > maxLength) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(entry);
        }
        return joined.isEmpty() ? null : joined.toString();
    }
}
