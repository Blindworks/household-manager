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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REWE direkt von rewe.de, je gewähltem Markt: die Angebotsseite liefert Woche und Artikelnummern, die Kacheln
 * kommen gebündelt vom Frontend-Includes-Endpunkt. Das REWE-Bonus-Guthaben ist ein Rabatt in Punkten, kein Preis.
 */
@Component
@Slf4j
public class ReweSource implements OfferSource, MarketSearch {

    private static final String BASE = "https://www.rewe.de/";
    private static final int TILE_BATCH_SIZE = 80;
    private static final Pattern WEEK = Pattern.compile("(\\d{4})/(\\d{1,2})");
    private static final Pattern PRICE = Pattern.compile("(\\d+(?:[.,]\\d{1,2})?)");
    private static final Pattern CITY_AFTER_ZIP = Pattern.compile("\\b\\d{5}\\s+(.+)$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GroceryHttp http;
    private final GroceryProperties properties;

    public ReweSource(GroceryHttp http, GroceryProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    /** Angebotswoche (z. B. {@code 2026/47}) und die eindeutigen Artikelnummern der Seite. */
    record PageInfo(String week, List<String> nans) {
    }

    @Override
    public SourceKey key() {
        return SourceKey.REWE;
    }

    @Override
    public boolean enabled() {
        return properties.isSourceEnabled(SourceKey.REWE);
    }

    @Override
    public Chain chain() {
        return Chain.REWE;
    }

    @Override
    public List<MarketCandidate> search(String zip, double lat, double lng) throws SourceFetchException {
        String json = http.get(URI.create(BASE + "api/wksmarketsearch?searchTerm="
                + URLEncoder.encode(zip, StandardCharsets.UTF_8)), Map.of("Accept", "application/json"));
        return marketsOf(readTree(json, "Marktsuche"));
    }

    @Override
    public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
        if (!ctx.enabledChains().contains(Chain.REWE)) {
            return List.of();
        }
        List<MarketCandidate> markets = ctx.selectedMarkets().stream().filter(m -> m.chain() == Chain.REWE).toList();
        List<RawOffer> offers = new ArrayList<>();
        int succeeded = 0;
        String lastError = null;
        for (MarketCandidate market : markets) {
            try {
                offers.addAll(fetchMarket(market));
                succeeded++;
            } catch (SourceFetchException e) {
                lastError = e.getMessage();
                log.warn("REWE: Markt {} nicht abrufbar: {}", market.externalId(), e.getMessage());
            }
        }
        if (!markets.isEmpty() && succeeded == 0) {
            throw new SourceFetchException("Kein REWE-Markt abrufbar (" + lastError + ")");
        }
        return offers;
    }

    /** Nachsichtig: eine unlesbare Antwort ergibt eine leere Liste (der Abrufpfad prüft strikt). */
    List<MarketCandidate> parseMarkets(String json) {
        try {
            return marketsOf(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            log.warn("REWE: Marktsuche nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    PageInfo parseOfferPage(String html) {
        Document page = Jsoup.parse(html);
        String week = null;
        LinkedHashSet<String> nans = new LinkedHashSet<>();
        for (Element element : page.select("[data-offer-nan]")) {
            nans.add(element.attr("data-offer-nan"));
            if (week == null && !element.attr("data-offer-week").isBlank()) {
                week = element.attr("data-offer-week");
            }
        }
        return new PageInfo(week, new ArrayList<>(nans));
    }

    /** Kacheln ohne Titel oder ohne Aktionspreis (nur Bonus-Guthaben) werden verworfen. */
    List<RawOffer> parseTiles(String json, String wwIdent) {
        try {
            return offersOf(MAPPER.readTree(json), wwIdent, null, null);
        } catch (JsonProcessingException e) {
            log.warn("REWE: Kachelantwort nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RawOffer> fetchMarket(MarketCandidate market) throws SourceFetchException {
        String wwIdent = market.externalId();
        String html = http.get(URI.create(BASE + "angebote/" + citySlug(market.address()) + "/" + wwIdent + "/x/"),
                Map.of());
        PageInfo page = parseOfferPage(html);
        if (page.nans().isEmpty()) {
            return List.of();
        }
        if (page.week() == null) {
            throw new SourceFetchException("Angebotswoche nicht gefunden (Markt " + wwIdent + ")");
        }
        LocalDate monday = mondayOf(page.week());
        if (monday == null) {
            throw new SourceFetchException("Angebotswoche nicht lesbar: " + page.week());
        }
        List<RawOffer> offers = new ArrayList<>();
        int succeeded = 0;
        int batches = 0;
        String lastError = null;
        for (int from = 0; from < page.nans().size(); from += TILE_BATCH_SIZE) {
            batches++;
            List<String> batch = page.nans().subList(from, Math.min(from + TILE_BATCH_SIZE, page.nans().size()));
            try {
                String response = http.postJson(URI.create(BASE + "api/frontend-includes"),
                        tileRequest(batch, wwIdent, page.week()), Map.of("Accept", "application/json"));
                offers.addAll(offersOf(readTree(response, "Kacheln"), wwIdent, monday, monday.plusDays(5)));
                succeeded++;
            } catch (SourceFetchException e) {
                lastError = e.getMessage();
                log.warn("REWE: Kachelabruf für Markt {} fehlgeschlagen: {}", wwIdent, e.getMessage());
            }
        }
        if (succeeded == 0) {
            throw new SourceFetchException("Keine Kacheln abrufbar (" + lastError + ")");
        }
        if (succeeded < batches) {
            log.warn("REWE: Markt {}: nur {} von {} Kachel-Paketen abrufbar", wwIdent, succeeded, batches);
        }
        return offers;
    }

    private static String tileRequest(List<String> nans, String wwIdent, String week) {
        var array = MAPPER.createArrayNode();
        for (String nan : nans) {
            var params = MAPPER.createObjectNode()
                    .put("nan", nan).put("wwIdent", wwIdent).put("showDuration", "auto")
                    .put("enableDetailDeeplink", "true").put("week", week)
                    .put("crossOutPriceArrowStyle", "false");
            var entry = MAPPER.createObjectNode().put("id", nan).put("namespace", "cor")
                    .put("name", "offer-tile-by-nan");
            entry.set("params", params);
            array.add(entry);
        }
        return array.toString();
    }

    private List<MarketCandidate> marketsOf(JsonNode tree) {
        List<MarketCandidate> markets = new ArrayList<>();
        for (JsonNode node : tree.path("markets")) {
            String wwIdent = node.path("wwIdent").asText("");
            if (wwIdent.isEmpty()) {
                continue;
            }
            JsonNode location = node.path("location");
            markets.add(new MarketCandidate(Chain.REWE, wwIdent, node.path("name").asText("REWE"),
                    node.path("street").asText("") + ", " + node.path("zipCode").asText("") + " "
                            + node.path("city").asText(""),
                    location.path("latitude").isNumber() ? location.path("latitude").doubleValue() : null,
                    location.path("longitude").isNumber() ? location.path("longitude").doubleValue() : null,
                    null));
        }
        return markets;
    }

    private List<RawOffer> offersOf(JsonNode tiles, String wwIdent, LocalDate from, LocalDate to) {
        List<RawOffer> offers = new ArrayList<>();
        for (JsonNode tile : tiles) {
            if (tile.has("error")) {
                continue;
            }
            parseTile(tile.path("id").asText(""), tile.path("content").asText(""), wwIdent, from, to)
                    .ifPresent(offers::add);
        }
        return offers;
    }

    private Optional<RawOffer> parseTile(String nan, String content, String wwIdent, LocalDate from, LocalDate to) {
        Document tile = Jsoup.parse(content);
        String title = tile.select(".cor-offer-information__title-link").text().trim();
        BigDecimal price = price(tile.select(".cor-offer-price__tag-price").text());
        if (nan.isEmpty() || title.isEmpty() || price == null) {
            return Optional.empty();
        }
        String basePriceText = null;
        String packaging = null;
        List<String> description = new ArrayList<>();
        for (Element line : tile.select(".cor-offer-information__additional")) {
            String text = line.text().replaceFirst("^,\\s*", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("(") && text.contains("=")) {
                basePriceText = text;
            } else if (text.toLowerCase().startsWith("je ")) {
                packaging = text;
            } else {
                description.add(text);
            }
        }
        String descriptionText = description.isEmpty() ? null : String.join(", ", description);
        BasePrice basePrice = BasePriceParser.resolve(null, basePriceText, packaging, descriptionText, price)
                .orElse(null);
        Element image = tile.selectFirst("img[data-testid=offer-image]");
        return Optional.of(RawOffer.builder()
                .source(SourceKey.REWE)
                .sourceOfferId(wwIdent + "-" + nan)
                .chain(Chain.REWE)
                .marketExternalId(wwIdent)
                .title(title)
                .description(descriptionText)
                .packaging(packaging)
                .price(price)
                .basePrice(basePrice)
                .basePriceText(basePriceText)
                .validFrom(from)
                .validTo(to)
                .imageUrl(image == null || image.attr("data-src").isBlank() ? null : image.attr("data-src"))
                .build());
    }

    private static BigDecimal price(String text) {
        Matcher matcher = PRICE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal price = new BigDecimal(matcher.group(1).replace(',', '.'));
        return price.signum() > 0 ? price : null;
    }

    /** Montag der ISO-Woche aus {@code "2026/47"}; null, wenn unlesbar. */
    static LocalDate mondayOf(String week) {
        Matcher matcher = WEEK.matcher(week);
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

    /** Stadt-Slug der Angebotsseite: klein, Umlaute umgeschrieben, Nicht-Alphanumerisches zu {@code -}. */
    static String citySlug(String address) {
        Matcher matcher = CITY_AFTER_ZIP.matcher(address == null ? "" : address);
        String city = matcher.find() ? matcher.group(1) : "";
        String slug = city.toLowerCase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "markt" : slug;
    }

    private static JsonNode readTree(String json, String what) throws SourceFetchException {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new SourceFetchException("Antwort nicht lesbar (" + what + ")", e);
        }
    }
}
