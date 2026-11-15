package com.household.manager.grocery.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BasePriceParser;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Penny direkt von penny.de: Angebote hängen an der Verkaufsregion des Markts, nicht am Markt selbst. Jede Region
 * wird deshalb nur einmal abgerufen, die Angebote werden für jeden gewählten Markt der Region vervielfältigt.
 */
@Component
@Slf4j
public class PennySource implements OfferSource, MarketSearch {

    private static final String BASE = "https://www.penny.de/";
    private static final int NEAREST_MARKETS = 5;
    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final Set<String> OFFER_TYPES = Set.of("offer", "promo", "promoPlus");
    private static final Pattern WEEK = Pattern.compile("(\\d{4})-(\\d{1,2})");
    private static final Pattern SECTION_DAY = Pattern.compile("^ab-([a-z]+)--(.+)$");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "montag", DayOfWeek.MONDAY, "dienstag", DayOfWeek.TUESDAY, "mittwoch", DayOfWeek.WEDNESDAY,
            "donnerstag", DayOfWeek.THURSDAY, "freitag", DayOfWeek.FRIDAY, "samstag", DayOfWeek.SATURDAY);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GroceryHttp http;
    private final GroceryProperties properties;

    public PennySource(GroceryHttp http, GroceryProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    /**
     * Angebotswoche (z. B. {@code 2026-47}), Kategorienamen der Woche und je Kategorie der Starttag, sofern die
     * Seite ihn trägt (Abschnitt-Id {@code ab-donnerstag--<kategorie>}); ohne Eintrag gilt der Montag.
     */
    record WeekInfo(String currentWeek, List<String> categories, Map<String, DayOfWeek> startDays) {
    }

    @Override
    public SourceKey key() {
        return SourceKey.PENNY;
    }

    @Override
    public boolean enabled() {
        return properties.isSourceEnabled(SourceKey.PENNY);
    }

    @Override
    public Chain chain() {
        return Chain.PENNY;
    }

    @Override
    public List<MarketCandidate> search(String zip, double lat, double lng) throws SourceFetchException {
        String json = http.get(URI.create(BASE + ".rest/market"), Map.of("Accept", "application/json"));
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new SourceFetchException("Antwort nicht lesbar (Marktliste)", e);
        }
        if (!tree.isArray()) {
            throw new SourceFetchException("Antwort nicht lesbar (Marktliste)");
        }
        return nearest(tree, lat, lng);
    }

    @Override
    public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
        if (!ctx.enabledChains().contains(Chain.PENNY)) {
            return List.of();
        }
        Map<String, List<MarketCandidate>> byRegion = new LinkedHashMap<>();
        for (MarketCandidate market : ctx.selectedMarkets()) {
            if (market.chain() != Chain.PENNY) {
                continue;
            }
            if (market.region() == null || market.region().isBlank()) {
                log.warn("Penny: Markt {} ohne Verkaufsregion übersprungen", market.externalId());
                continue;
            }
            byRegion.computeIfAbsent(market.region(), r -> new ArrayList<>()).add(market);
        }
        if (byRegion.isEmpty()) {
            return List.of();
        }
        WeekInfo week = parseOffersPage(http.get(URI.create(BASE + "angebote"), Map.of()));
        if (week.currentWeek() == null) {
            throw new SourceFetchException("Angebotswoche nicht gefunden");
        }
        LocalDate monday = mondayOf(week.currentWeek());
        if (monday == null) {
            throw new SourceFetchException("Angebotswoche nicht lesbar: " + week.currentWeek());
        }
        List<RawOffer> offers = new ArrayList<>();
        int requests = 0;
        int succeeded = 0;
        String lastError = null;
        for (Map.Entry<String, List<MarketCandidate>> region : byRegion.entrySet()) {
            for (String category : week.categories()) {
                requests++;
                try {
                    JsonNode tree = readTree(http.get(categoryUri(week.currentWeek(), category, region.getKey()),
                            Map.of("Accept", "application/json")));
                    LocalDate from = monday.with(week.startDays().getOrDefault(category, DayOfWeek.MONDAY));
                    for (MarketCandidate market : region.getValue()) {
                        offers.addAll(offersOf(tree, market, from, monday.plusDays(5)));
                    }
                    succeeded++;
                } catch (SourceFetchException e) {
                    lastError = e.getMessage();
                    log.warn("Penny: Kategorie {} (Region {}) nicht abrufbar: {}", category, region.getKey(),
                            e.getMessage());
                }
            }
        }
        if (requests > 0 && succeeded == 0) {
            throw new SourceFetchException("Keine Penny-Kategorie abrufbar (" + lastError + ")");
        }
        if (succeeded < requests) {
            log.warn("Penny: nur {} von {} Kategorie-Abrufen erfolgreich", succeeded, requests);
        }
        return offers;
    }

    /** Die nächsten fünf Märkte nach Luftlinie; Märkte ohne Kennung oder Koordinaten fallen weg. */
    List<MarketCandidate> parseMarkets(String json, double lat, double lng) {
        try {
            return nearest(MAPPER.readTree(json), lat, lng);
        } catch (JsonProcessingException e) {
            log.warn("Penny: Marktliste nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    WeekInfo parseOffersPage(String html) {
        Document page = Jsoup.parse(html);
        Element weeks = page.selectFirst(".js-weeks[data-current-week]");
        String currentWeek = weeks == null || weeks.attr("data-current-week").isBlank()
                ? null : weeks.attr("data-current-week");
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        Map<String, DayOfWeek> startDays = new LinkedHashMap<>();
        for (Element element : page.select("[data-category-name]")) {
            String category = element.attr("data-category-name");
            if (category.isBlank()) {
                continue;
            }
            categories.add(category);
            Matcher matcher = SECTION_DAY.matcher(element.id());
            if (matcher.matches() && DAYS.containsKey(matcher.group(1))) {
                startDays.putIfAbsent(category, DAYS.get(matcher.group(1)));
            }
        }
        return new WeekInfo(currentWeek, new ArrayList<>(categories), startDays);
    }

    /** Gültigkeit Montag bis Samstag der Woche; der Starttag einer Kategorie kommt erst im Abrufpfad dazu. */
    List<RawOffer> parseCategory(String json, MarketCandidate market, String week) {
        LocalDate monday = mondayOf(week);
        try {
            return offersOf(MAPPER.readTree(json), market, monday, monday == null ? null : monday.plusDays(5));
        } catch (JsonProcessingException e) {
            log.warn("Penny: Kategorie nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    private static URI categoryUri(String week, String category, String region) {
        return URI.create(BASE + ".rest/offers/by-category/" + week + "/"
                + URLEncoder.encode(category, StandardCharsets.UTF_8)
                + "?region=" + URLEncoder.encode(region, StandardCharsets.UTF_8));
    }

    private static JsonNode readTree(String json) throws SourceFetchException {
        try {
            JsonNode tree = MAPPER.readTree(json);
            if (tree == null || !tree.path("offerTiles").isArray()) {
                throw new SourceFetchException("Antwort nicht lesbar (Kategorie)");
            }
            return tree;
        } catch (JsonProcessingException e) {
            throw new SourceFetchException("Antwort nicht lesbar (Kategorie)", e);
        }
    }

    private List<MarketCandidate> nearest(JsonNode markets, double lat, double lng) {
        record Scored(MarketCandidate market, double km) {
        }
        List<Scored> scored = new ArrayList<>();
        for (JsonNode node : markets) {
            String wwIdent = node.path("wwIdent").asText("");
            Double marketLat = coordinate(node.path("latitude"));
            Double marketLng = coordinate(node.path("longitude"));
            if (wwIdent.isEmpty() || marketLat == null || marketLng == null) {
                continue;
            }
            String region = node.path("sellingRegion").asText("");
            scored.add(new Scored(new MarketCandidate(Chain.PENNY, wwIdent, node.path("marketName").asText("Penny"),
                    node.path("streetWithHouseNumber").asText("") + ", " + node.path("zipCode").asText("") + " "
                            + node.path("city").asText(""),
                    marketLat, marketLng, region.isEmpty() ? null : region),
                    haversineKm(lat, lng, marketLat, marketLng)));
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

    private List<RawOffer> offersOf(JsonNode tree, MarketCandidate market, LocalDate from, LocalDate to) {
        List<RawOffer> offers = new ArrayList<>();
        for (JsonNode tile : tree.path("offerTiles")) {
            if (OFFER_TYPES.contains(tile.path("primaryType").asText(""))) {
                parseTile(tile, market, from, to).ifPresent(offers::add);
            }
        }
        return offers;
    }

    /**
     * {@code price} ist immer der normale Ladenpreis. Bei {@code promo}-Kacheln mit App-Marker
     * ({@code benefitGroundPrice}) steht im Feld {@code price} der App-Preis und der Normalpreis in
     * {@code crossOutPrice}; ohne Marker ist {@code crossOutPrice} ein gewöhnlicher Streichpreis. Bei {@code promoPlus}
     * ist es umgekehrt
     * ({@code benefitPrice} = App-Preis). Ein UVP ({@code originalPriceType: uvp}, {@code listPrice}) ist nie
     * Vergleichspreis.
     */
    private Optional<RawOffer> parseTile(JsonNode tile, MarketCandidate market, LocalDate from, LocalDate to) {
        String title = tile.path("title").asText("").replaceAll("[\\s*]+$", "").trim();
        String uuid = tile.path("uuid").asText("");
        BigDecimal shown = number(tile.path("price"));
        BigDecimal crossOut = number(tile.path("crossOutPrice"));
        if (title.isEmpty() || uuid.isEmpty() || shown == null) {
            return Optional.empty();
        }
        BigDecimal price = shown;
        BigDecimal loyalty = number(tile.path("benefitPrice"));
        BigDecimal oldPrice = crossOut;
        if ("promo".equals(tile.path("primaryType").asText()) && crossOut != null && isAppTile(tile)) {
            price = crossOut;
            loyalty = shown;
            oldPrice = null;
        } else if (oldPrice == null && !"uvp".equalsIgnoreCase(tile.path("originalPriceType").asText(""))) {
            oldPrice = number(tile.path("originalPrice"));
        }
        if (oldPrice != null && oldPrice.compareTo(price) <= 0) {
            oldPrice = null;
        }
        String packaging = textOrNull(tile.path("quantity"));
        String basePriceText = textOrNull(tile.path("basePrice"));
        BasePrice basePrice = BasePriceParser.resolve(null, basePriceText, packaging, null, price).orElse(null);
        return Optional.of(RawOffer.builder()
                .source(SourceKey.PENNY)
                .sourceOfferId(uuid)
                .chain(Chain.PENNY)
                .marketExternalId(market.externalId())
                .title(title)
                .packaging(packaging)
                .price(price)
                .oldPrice(oldPrice)
                .loyaltyPrice(loyalty)
                .lowestPrice30Days(number(tile.path("lowestPrice30Days")))
                .basePrice(basePrice)
                .basePriceText(basePriceText)
                .validFrom(from)
                .validTo(to)
                .imageUrl(textOrNull(tile.path("imageRendition").path("tileMd")))
                .build());
    }

    /** Nur App-Kacheln tragen einen App-Grundpreis. */
    private static boolean isAppTile(JsonNode tile) {
        JsonNode marker = tile.path("benefitGroundPrice");
        return marker.isTextual() && !marker.asText().isBlank();
    }

    /** Preise kommen als Text mit angehängter Fußnotenziffer ({@code "12.99²"}); es zählt die führende Zahl. */
    private static BigDecimal number(JsonNode node) {
        if (node.isNumber()) {
            return positive(node.decimalValue());
        }
        if (!node.isTextual()) {
            return null;
        }
        Matcher matcher = NUMBER.matcher(node.asText());
        return matcher.find() ? positive(new BigDecimal(matcher.group(1).replace(',', '.'))) : null;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value.signum() > 0 ? value : null;
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    /** Montag der ISO-Woche aus {@code "2026-47"}; null, wenn unlesbar. */
    static LocalDate mondayOf(String week) {
        Matcher matcher = WEEK.matcher(week == null ? "" : week);
        if (!matcher.matches()) {
            return null;
        }
        int weekNumber = Integer.parseInt(matcher.group(2));
        if (weekNumber < 1 || weekNumber > 53) {
            return null;
        }
        return LocalDate.of(Integer.parseInt(matcher.group(1)), 1, 4)
                .with(WeekFields.ISO.weekOfWeekBasedYear(), weekNumber)
                .with(DayOfWeek.MONDAY);
    }
}
