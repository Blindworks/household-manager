package com.household.manager.grocery;

import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.grocery.GroceryRunService.Snapshot;
import com.household.manager.grocery.dto.DealDto;
import com.household.manager.grocery.dto.DealsView;
import com.household.manager.grocery.dto.GrocerySummaryDto;
import com.household.manager.grocery.dto.GrocerySummaryDto.SummaryItem;
import com.household.manager.grocery.dto.PriceObservationDto;
import com.household.manager.grocery.entity.GroceryMarket;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.Deal;
import com.household.manager.repository.GroceryMarketRepository;
import com.household.manager.repository.GroceryPriceObservationRepository;
import com.household.manager.repository.GroceryProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Liest Schnäppchen, passende Angebote und Preisverlauf aus dem Snapshot des {@link GroceryRunService}. */
@Slf4j
@Service
public class GroceryDealQueryService {

    static final String ALL_MARKETS = "alle Märkte";
    static final int HISTORY_WEEKS = 52;

    private static final Comparator<DealDto> DEAL_ORDER = Comparator
            .comparing((DealDto d) -> d.verdict() == Verdict.MATCH ? 0 : 1)
            .thenComparing(d -> d.savingPercent() == null ? Integer.MIN_VALUE : d.savingPercent(),
                    Comparator.reverseOrder());

    private final GroceryRunService runService;
    private final GroceryProductRepository products;
    private final GroceryMarketRepository markets;
    private final GroceryPriceObservationRepository observations;
    private final GroceryOwners owners;
    private final Clock clock;

    public GroceryDealQueryService(GroceryRunService runService, GroceryProductRepository products,
                                   GroceryMarketRepository markets, GroceryPriceObservationRepository observations,
                                   GroceryOwners owners, Clock clock) {
        this.runService = runService;
        this.products = products;
        this.markets = markets;
        this.observations = observations;
        this.owners = owners;
        this.clock = clock;
    }

    /** Schnäppchen der gewählten Besitzer: sicher vor vielleicht, dann nach Ersparnis. */
    public DealsView deals(String owner) {
        Predicate<Long> filter = owners.filter(owner);
        Snapshot snapshot = runService.snapshot();
        Map<Long, String> marketNames = marketNames();
        List<DealDto> result = new ArrayList<>();
        for (GroceryProduct product : products.findAll()) {
            if (!filter.test(product.getOwnerUserId())) {
                continue;
            }
            for (Deal deal : snapshot.deals().getOrDefault(product.getId(), List.of())) {
                result.add(toDto(product, deal.match(), deal, marketNames));
            }
        }
        result.sort(DEAL_ORDER);
        return new DealsView(snapshot.collectedAt(), result);
    }

    /** Alle aktuell passenden Angebote eines Produkts, auch ohne Schnäppchen. */
    public List<DealDto> offers(long productId) {
        GroceryProduct product = find(productId);
        Snapshot snapshot = runService.snapshot();
        Map<GroceryOffer, Deal> dealsByOffer = new HashMap<>();
        for (Deal deal : snapshot.deals().getOrDefault(productId, List.of())) {
            dealsByOffer.put(deal.match().offer(), deal);
        }
        Map<Long, String> marketNames = marketNames();
        List<DealDto> result = new ArrayList<>();
        for (MatchedOffer match : snapshot.matches().getOrDefault(productId, List.of())) {
            result.add(toDto(product, match, dealsByOffer.get(match.offer()), marketNames));
        }
        result.sort(DEAL_ORDER);
        return result;
    }

    /** Beobachtete Grundpreise der letzten 52 Wochen, nach ISO-Woche aufsteigend. */
    public List<PriceObservationDto> priceHistory(long productId) {
        find(productId);
        LocalDateTime from = LocalDateTime.now(clock).minusWeeks(HISTORY_WEEKS);
        return observations.findByProductIdAndObservedAtGreaterThanEqualOrderByIsoWeekAsc(productId, from).stream()
                .map(o -> new PriceObservationDto(o.getChain(), o.getChain().displayName(), o.getIsoWeek(),
                        o.getBasePrice(), o.getObservedAt()))
                .toList();
    }

    /** Sichere (und bestätigte) Schnäppchen aller Personen und des Haushalts für die Hub-Karte. */
    public GrocerySummaryDto summary() {
        Snapshot snapshot = runService.snapshot();
        Map<Long, String> names = owners.displayNames();
        List<SummaryItem> items = new ArrayList<>();
        for (GroceryProduct product : products.findAll()) {
            for (Deal deal : snapshot.deals().getOrDefault(product.getId(), List.of())) {
                if (deal.match().verdict() == Verdict.MATCH) {
                    GroceryOffer offer = deal.match().offer();
                    items.add(new SummaryItem(product.getName(), product.getOwnerUserId(),
                            GroceryOwners.ownerName(product.getOwnerUserId(), names), offer.getChain(),
                            offer.getChain().displayName(), deal.basePrice(), offer.getBaseUnit(),
                            deal.savingPercent()));
                }
            }
        }
        items.sort(Comparator.comparingInt(SummaryItem::savingPercent).reversed());
        return new GrocerySummaryDto(items);
    }

    private GroceryProduct find(long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Produkt", "id", productId));
    }

    private Map<Long, String> marketNames() {
        Map<Long, String> names = new HashMap<>();
        try {
            for (GroceryMarket market : markets.findAll()) {
                names.put(market.getId(), market.getName());
            }
        } catch (RuntimeException e) {
            log.warn("Marktnamen nicht lesbar: {}", e.getMessage());
        }
        return names;
    }

    private static DealDto toDto(GroceryProduct product, MatchedOffer match, Deal deal, Map<Long, String> markets) {
        GroceryOffer offer = match.offer();
        String marketName = offer.getMarketId() == null ? ALL_MARKETS
                : markets.getOrDefault(offer.getMarketId(), ALL_MARKETS);
        return new DealDto(product.getId(), product.getName(), product.getOwnerUserId(), offer.getChain(),
                offer.getChain().displayName(), marketName, offer.getTitle(), offer.getPackaging(),
                offer.getPrice(), offer.getBasePrice(), offer.getBaseUnit(),
                deal == null ? null : deal.reference(), deal == null ? null : deal.referenceKind(),
                deal == null ? null : deal.savingPercent(), offer.getLoyaltyPrice(),
                deal == null ? null : deal.appBasePrice(), deal != null && deal.appOnly(), match.verdict(),
                match.reasonKind(), match.reason(), match.userDecided(), deal != null && deal.warmingUp(),
                deal != null, offer.getValidFrom(), offer.getValidTo(), offer.getImageUrl(), offer.getFingerprint());
    }
}
