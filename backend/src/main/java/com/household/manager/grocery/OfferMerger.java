package com.household.manager.grocery;

import com.household.manager.grocery.matching.OfferFingerprint;
import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BasePriceParser;
import com.household.manager.grocery.source.RawOffer;
import com.household.manager.grocery.source.SourceKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Führt die Rohangebote aller Quellen zu einer Angebotsliste zusammen.
 *
 * <p>Zwei Angebote gelten als dasselbe, wenn Kette, überlappende Gültigkeit und Preis passen und Marke+Titel
 * (Jaccard über Wort-Tokens ab 0,5) oder der Text-Fingerabdruck ähneln. Direktquellen kennen den Markt und gewinnen
 * bei Preis, Treuepreis, Markt und Grundpreis; Aggregatoren (ohne Markt) schließen sich jedem passenden
 * Direktangebot an und bleiben sonst kettenweit.
 */
@Component
@Slf4j
public class OfferMerger {

    private static final double MIN_SIMILARITY = 0.5;
    private static final BigDecimal MAX_PRICE_DEVIATION = new BigDecimal("0.30");

    public record MergedOffer(
            Chain chain,
            Long marketId,
            Set<SourceKey> sources,
            Map<SourceKey, String> sourceOfferIds,
            String title,
            String brand,
            String description,
            String packaging,
            BigDecimal price,
            BigDecimal oldPrice,
            BigDecimal loyaltyPrice,
            BigDecimal lowestPrice30Days,
            BasePrice basePrice,
            LocalDate validFrom,
            LocalDate validTo,
            String imageUrl,
            String fingerprint
    ) {
    }

    /**
     * @param marketIdByChainAndExternal Schlüssel {@code "<CHAIN>|<externalId>"}; unbekannte Märkte gelten als kettenweit
     */
    public List<MergedOffer> merge(List<RawOffer> raws, Map<String, Long> marketIdByChainAndExternal) {
        Map<Chain, List<RawOffer>> byChain = new LinkedHashMap<>();
        for (RawOffer raw : raws) {
            if (raw.chain() != null && raw.source() != null && raw.price() != null
                    && raw.title() != null && !raw.title().isBlank()) {
                byChain.computeIfAbsent(raw.chain(), c -> new ArrayList<>()).add(raw);
            }
        }
        List<MergedOffer> result = new ArrayList<>();
        byChain.forEach((chain, offers) -> mergeChain(chain, offers, marketIdByChainAndExternal, result));
        return result;
    }

    private void mergeChain(Chain chain, List<RawOffer> offers, Map<String, Long> markets, List<MergedOffer> out) {
        List<Group> direct = new ArrayList<>();
        List<RawOffer> aggregators = new ArrayList<>();
        for (RawOffer offer : offers) {
            if (offer.source().isAggregator()) {
                aggregators.add(offer);
            } else {
                direct.add(new Group(offer, marketId(chain, offer, markets), true));
            }
        }
        List<Group> aggregatorOnly = new ArrayList<>();
        for (RawOffer offer : aggregators) {
            Long market = marketId(chain, offer, markets);
            boolean merged = false;
            for (Group group : direct) {
                if (market != null && !market.equals(group.marketId)) {
                    continue;
                }
                if (group.accepts(offer)) {
                    group.add(offer);
                    merged = true;
                }
            }
            if (!merged) {
                Group target = aggregatorOnly.stream()
                        .filter(g -> java.util.Objects.equals(g.marketId, market) && g.accepts(offer))
                        .findFirst().orElse(null);
                if (target == null) {
                    aggregatorOnly.add(new Group(offer, market, false));
                } else {
                    target.add(offer);
                }
            }
        }
        direct.forEach(g -> out.add(g.build(chain)));
        aggregatorOnly.forEach(g -> out.add(g.build(chain)));
    }

    private static Long marketId(Chain chain, RawOffer offer, Map<String, Long> markets) {
        return offer.marketExternalId() == null ? null : markets.get(chain.name() + "|" + offer.marketExternalId());
    }

    private final class Group {

        private final List<RawOffer> members = new ArrayList<>();
        private final Long marketId;
        private final boolean directPrimary;

        Group(RawOffer primary, Long marketId, boolean directPrimary) {
            this.members.add(primary);
            this.marketId = marketId;
            this.directPrimary = directPrimary;
        }

        RawOffer primary() {
            return members.get(0);
        }

        void add(RawOffer offer) {
            members.add(offer);
        }

        boolean accepts(RawOffer other) {
            RawOffer primary = primary();
            if (!overlaps(primary, other) || !similar(primary, other)) {
                return false;
            }
            if (primary.price().compareTo(other.price()) == 0) {
                return true;
            }
            if (directPrimary && withinTolerance(primary.price(), other.price())) {
                log.warn("Preisabweichung zwischen {} und {} ({}): '{}' {} gegen {} - Direktpreis gewinnt",
                        primary.source(), other.source(), primary.chain(), primary.title(), primary.price(), other.price());
                return true;
            }
            return false;
        }

        MergedOffer build(Chain chain) {
            RawOffer primary = primary();
            String title = pick(RawOffer::title);
            String brand = pick(RawOffer::brand);
            String description = pick(RawOffer::description);
            String packaging = pick(RawOffer::packaging);
            BasePrice structured = members.stream().map(RawOffer::basePrice)
                    .filter(b -> b != null && b.amount() != null).findFirst().orElse(null);
            String basePriceText = pick(RawOffer::basePriceText);
            BasePrice basePrice = BasePriceParser.resolve(structured, basePriceText, packaging, description,
                    primary.price()).orElse(null);
            Map<SourceKey, String> ids = new EnumMap<>(SourceKey.class);
            members.forEach(m -> ids.putIfAbsent(m.source(), m.sourceOfferId()));
            Set<SourceKey> sources = new HashSet<>(ids.keySet());
            sources.add(primary.source());
            return new MergedOffer(chain, marketId, sources, ids, title, brand, description, packaging,
                    primary.price(), pickDecimal(RawOffer::oldPrice), pickDecimal(RawOffer::loyaltyPrice),
                    pickDecimal(RawOffer::lowestPrice30Days), basePrice,
                    pickDate(RawOffer::validFrom), pickDate(RawOffer::validTo), pick(RawOffer::imageUrl),
                    OfferFingerprint.of(title, brand, description, packaging));
        }

        private String pick(Function<RawOffer, String> field) {
            return members.stream().map(field).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
        }

        private BigDecimal pickDecimal(Function<RawOffer, BigDecimal> field) {
            return members.stream().map(field).filter(v -> v != null).findFirst().orElse(null);
        }

        private LocalDate pickDate(Function<RawOffer, LocalDate> field) {
            return members.stream().map(field).filter(v -> v != null).findFirst().orElse(null);
        }
    }

    private static boolean overlaps(RawOffer a, RawOffer b) {
        LocalDate aFrom = a.validFrom() == null ? LocalDate.MIN : a.validFrom();
        LocalDate aTo = a.validTo() == null ? LocalDate.MAX : a.validTo();
        LocalDate bFrom = b.validFrom() == null ? LocalDate.MIN : b.validFrom();
        LocalDate bTo = b.validTo() == null ? LocalDate.MAX : b.validTo();
        return !aFrom.isAfter(bTo) && !bFrom.isAfter(aTo);
    }

    private static boolean withinTolerance(BigDecimal directPrice, BigDecimal otherPrice) {
        if (directPrice.signum() <= 0) {
            return false;
        }
        BigDecimal deviation = otherPrice.subtract(directPrice).abs().divide(directPrice, MathContext.DECIMAL64);
        return deviation.compareTo(MAX_PRICE_DEVIATION) <= 0;
    }

    private static boolean similar(RawOffer a, RawOffer b) {
        String fingerprintA = OfferFingerprint.of(a.title(), a.brand(), a.description(), a.packaging());
        String fingerprintB = OfferFingerprint.of(b.title(), b.brand(), b.description(), b.packaging());
        return fingerprintA.equals(fingerprintB)
                || jaccard(tokens(a.brand(), a.title()), tokens(b.brand(), b.title())) >= MIN_SIMILARITY;
    }

    /** Wort-Tokens aus Marke und Titel; Einzelzeichen (Mengenangaben wie "6 x 1 l") bleiben außen vor. */
    private static Set<String> tokens(String brand, String title) {
        String text = ((brand == null ? "" : brand) + " " + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();
        for (String token : text.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
