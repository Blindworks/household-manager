package com.household.manager.grocery.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BasePriceParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kaufland direkt von filiale.kaufland.de: Die Angebotsseite eines Markts (Cookie {@code x-aem-variant}) trägt die
 * Angebote als eingebettetes JSON {@code window.SSR[...] = {"component":"OfferTemplate",...}}.
 */
@Component
@Slf4j
public class KauflandSource implements OfferSource, MarketSearch {

    private static final String BASE = "https://filiale.kaufland.de/";
    private static final int NEAREST_MARKETS = 5;
    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final Pattern TEMPLATE = Pattern.compile("\"component\"\\s*:\\s*\"OfferTemplate\"");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GroceryHttp http;
    private final GroceryProperties properties;

    public KauflandSource(GroceryHttp http, GroceryProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public SourceKey key() {
        return SourceKey.KAUFLAND;
    }

    @Override
    public boolean enabled() {
        return properties.isSourceEnabled(SourceKey.KAUFLAND);
    }

    @Override
    public Chain chain() {
        return Chain.KAUFLAND;
    }

    @Override
    public List<MarketCandidate> search(String zip, double lat, double lng) throws SourceFetchException {
        String json = http.get(URI.create(BASE + ".klstorefinder.json"), Map.of("Accept", "application/json"));
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new SourceFetchException("Antwort nicht lesbar (Filialliste)", e);
        }
        if (tree == null || !tree.isArray()) {
            throw new SourceFetchException("Antwort nicht lesbar (Filialliste)");
        }
        return nearest(tree, lat, lng);
    }

    @Override
    public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
        if (!ctx.enabledChains().contains(Chain.KAUFLAND)) {
            return List.of();
        }
        List<MarketCandidate> markets = ctx.selectedMarkets().stream()
                .filter(market -> market.chain() == Chain.KAUFLAND).toList();
        if (markets.isEmpty()) {
            return List.of();
        }
        List<RawOffer> offers = new ArrayList<>();
        int succeeded = 0;
        String lastError = null;
        for (MarketCandidate market : markets) {
            try {
                String html = http.get(URI.create(BASE + "angebote/uebersicht.html"),
                        Map.of("Cookie", "x-aem-variant=" + market.externalId()));
                offers.addAll(parseOffers(html, market));
                succeeded++;
            } catch (SourceFetchException e) {
                lastError = e.getMessage();
                log.warn("Kaufland: Markt {} nicht abrufbar: {}", market.externalId(), e.getMessage());
            }
        }
        if (succeeded == 0) {
            throw new SourceFetchException("Kein Kaufland-Markt abrufbar (" + lastError + ")");
        }
        if (succeeded < markets.size()) {
            log.warn("Kaufland: nur {} von {} Märkten erfolgreich", succeeded, markets.size());
        }
        return offers;
    }

    /** Ein Markt-Objekt (bygeo) oder eine Liste; die nächsten fünf nach Luftlinie. */
    List<MarketCandidate> parseStores(String json, double lat, double lng) {
        try {
            JsonNode tree = MAPPER.readTree(json);
            return nearest(tree.isArray() ? tree : MAPPER.createArrayNode().add(tree), lat, lng);
        } catch (JsonProcessingException e) {
            log.warn("Kaufland: Filialdaten nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    /** Wirft, wenn die Seite das Angebots-JSON nicht trägt; doppelte {@code klNr} je Woche zählen einmal. */
    List<RawOffer> parseOffers(String html, MarketCandidate market) throws SourceFetchException {
        JsonNode cycles = extractOfferTemplate(html).path("props").path("offerData").path("cycles");
        if (!cycles.isArray()) {
            throw new SourceFetchException("Antwort nicht lesbar (Angebote)");
        }
        List<RawOffer> offers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode cycle : cycles) {
            for (JsonNode category : cycle.path("categories")) {
                LocalDate categoryFrom = date(category.path("salesFrom"));
                LocalDate categoryTo = date(category.path("salesTo"));
                for (JsonNode offer : category.path("offers")) {
                    LocalDate from = orElse(date(offer.path("salesFrom")), categoryFrom);
                    LocalDate to = orElse(date(offer.path("salesTo")), categoryTo);
                    String klNr = text(offer.path("klNr"));
                    if (klNr == null || !seen.add(klNr + "_" + from)) {
                        continue;
                    }
                    parseOffer(offer, klNr, from, to, market).ifPresent(offers::add);
                }
            }
        }
        return offers;
    }

    private static JsonNode extractOfferTemplate(String html) throws SourceFetchException {
        Matcher matcher = TEMPLATE.matcher(html == null ? "" : html);
        if (!matcher.find()) {
            throw new SourceFetchException("Antwort nicht lesbar (Angebotsdaten nicht gefunden)");
        }
        int start = html.lastIndexOf('{', matcher.start());
        int end = html.indexOf("</script>", matcher.end());
        try {
            return MAPPER.readTree(html.substring(start, end < 0 ? html.length() : end));
        } catch (JsonProcessingException e) {
            throw new SourceFetchException("Antwort nicht lesbar (Angebotsdaten)", e);
        }
    }

    /** {@code title} ist bei Kaufland die Marke, {@code subtitle} das Produkt; {@code price} ist der Normalpreis. */
    private static Optional<RawOffer> parseOffer(JsonNode offer, String klNr, LocalDate from, LocalDate to,
                                                 MarketCandidate market) {
        String brandOrTitle = text(offer.path("title"));
        String subtitle = text(offer.path("subtitle"));
        BigDecimal price = number(offer.path("price"));
        if (brandOrTitle == null || price == null) {
            return Optional.empty();
        }
        BigDecimal oldPrice = number(offer.path("formattedOldPrice"));
        if (oldPrice != null && oldPrice.compareTo(price) <= 0) {
            oldPrice = null;
        }
        String packaging = text(offer.path("unit"));
        String description = text(offer.path("detailDescription"));
        String basePriceText = text(offer.path("basePrice"));
        BasePrice basePrice = BasePriceParser.resolve(null, basePriceText, packaging, description, price).orElse(null);
        return Optional.of(RawOffer.builder()
                .source(SourceKey.KAUFLAND)
                .sourceOfferId(klNr + "_" + from)
                .chain(Chain.KAUFLAND)
                .marketExternalId(market.externalId())
                .title(subtitle == null ? brandOrTitle : subtitle)
                .brand(subtitle == null ? null : brandOrTitle)
                .description(description)
                .packaging(packaging)
                .price(price)
                .oldPrice(oldPrice)
                .loyaltyPrice(number(offer.path("loyaltyFormattedPrice")))
                .basePrice(basePrice)
                .basePriceText(basePriceText)
                .validFrom(from)
                .validTo(to)
                .imageUrl(text(offer.path("listImage")))
                .build());
    }

    private static List<MarketCandidate> nearest(JsonNode stores, double lat, double lng) {
        record Scored(MarketCandidate market, double km) {
        }
        List<Scored> scored = new ArrayList<>();
        for (JsonNode node : stores) {
            String id = node.path("n").asText("");
            Double storeLat = coordinate(node.path("lat"));
            Double storeLng = coordinate(node.path("lng"));
            if (id.isEmpty() || storeLat == null || storeLng == null) {
                continue;
            }
            scored.add(new Scored(new MarketCandidate(Chain.KAUFLAND, id, node.path("cn").asText("Kaufland"),
                    node.path("sn").asText("") + ", " + node.path("pc").asText("") + " " + node.path("t").asText(""),
                    storeLat, storeLng, null), haversineKm(lat, lng, storeLat, storeLng)));
        }
        return scored.stream().sorted(Comparator.comparingDouble(Scored::km)).limit(NEAREST_MARKETS)
                .map(Scored::market).toList();
    }

    private static Double coordinate(JsonNode node) {
        if (node.isNumber()) {
            return node.doubleValue();
        }
        try {
            return node.isTextual() ? Double.valueOf(node.asText().trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    /** Zahl oder Text mit Zahl ({@code "13.99*"}); nicht positiv oder unlesbar ergibt null. */
    private static BigDecimal number(JsonNode node) {
        BigDecimal value = null;
        if (node.isNumber()) {
            value = node.decimalValue();
        } else if (node.isTextual()) {
            Matcher matcher = NUMBER.matcher(node.asText());
            if (matcher.find()) {
                value = new BigDecimal(matcher.group(1).replace(',', '.'));
            }
        }
        return value != null && value.signum() > 0 ? value : null;
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    private static LocalDate date(JsonNode node) {
        try {
            return node.isTextual() ? LocalDate.parse(node.asText()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate orElse(LocalDate value, LocalDate fallback) {
        return value != null ? value : fallback;
    }
}
