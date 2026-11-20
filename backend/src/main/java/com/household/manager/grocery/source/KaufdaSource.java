package com.household.manager.grocery.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.GrocerySettingsService;
import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BasePriceParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator kaufDA: Prospekte aus dem Regal-Endpunkt der Website, je Prospekt die Angebote. Für Ketten mit
 * Märkten gilt ein Prospekt nur, wenn sein nächster Markt ({@code closestStore}) einem gewählten Markt entspricht;
 * Edekas eigene Seite ist bot-geschützt, deshalb kommen deren Märkte ebenfalls aus {@code closestStore}.
 */
@Component
@Slf4j
public class KaufdaSource implements OfferSource, MarketSearch {

    private static final String BASE = "https://www.kaufda.de/";
    private static final int SHELF_PAGE_SIZE = 60;
    private static final int MAX_SHELF_PAGES = 10;
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter KAUFDA_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final Pattern NEXT_DATA =
            Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern HOUSE_NUMBER =
            Pattern.compile("\\s+\\d+\\s*[a-zA-Z]?(\\s*[-/–]\\s*\\d+\\s*[a-zA-Z]?)*\\s*$");
    private static final Pattern ZIP = Pattern.compile("\\b(\\d{5})\\b");
    private static final Pattern LOYALTY_CONDITION = Pattern.compile("(?i)\\bapp\\b|lidl plus|kaufland card|mit karte");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GroceryHttp http;
    private final GroceryProperties properties;
    private final GrocerySettingsService settings;

    public KaufdaSource(GroceryHttp http, GroceryProperties properties, GrocerySettingsService settings) {
        this.http = http;
        this.properties = properties;
        this.settings = settings;
    }

    /** Ein Prospekt; {@code store} ist der nächste Markt aus {@code closestStore} (null bei Aldi-Landingpage). */
    record Brochure(String contentId, String publisherName, Chain chain, String storeName, String storeZip,
                    String storeStreet, MarketCandidate store, LocalDate validFrom, LocalDate validUntil) {
    }

    @Override
    public SourceKey key() {
        return SourceKey.KAUFDA;
    }

    @Override
    public boolean enabled() {
        return properties.isSourceEnabled(SourceKey.KAUFDA);
    }

    @Override
    public Chain chain() {
        return Chain.EDEKA;
    }

    @Override
    public List<MarketCandidate> search(String zip, double lat, double lng) throws SourceFetchException {
        Map<String, MarketCandidate> markets = new LinkedHashMap<>();
        for (Brochure brochure : fetchShelf(lat, lng)) {
            if (brochure.chain() == Chain.EDEKA && brochure.store() != null) {
                markets.putIfAbsent(brochure.store().externalId(), brochure.store());
            }
        }
        return new ArrayList<>(markets.values());
    }

    @Override
    public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
        List<Brochure> shelf = fetchShelf(ctx.lat(), ctx.lng());
        List<Brochure> relevant = new ArrayList<>();
        Map<Brochure, String> marketIds = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (Brochure brochure : shelf) {
            if (!ctx.enabledChains().contains(brochure.chain()) || !seenIds.add(brochure.contentId())) {
                continue;
            }
            if (brochure.chain().hasMarkets()) {
                selectedMarketId(brochure, ctx.selectedMarkets()).ifPresent(marketId -> {
                    relevant.add(brochure);
                    marketIds.put(brochure, marketId);
                });
            } else {
                relevant.add(brochure);
            }
        }
        if (ctx.enabledChains().contains(Chain.ALDI_SUED)
                && relevant.stream().noneMatch(b -> b.chain() == Chain.ALDI_SUED)) {
            try {
                aldiSuedBrochure().filter(b -> seenIds.add(b.contentId())).ifPresent(relevant::add);
            } catch (SourceFetchException e) {
                log.warn("kaufDA: Aldi-Süd-Prospekt nicht abrufbar: {}", e.getMessage());
            }
        }
        String accountId = settings.kaufdaAccountId();
        Map<String, RawOffer> offersById = new LinkedHashMap<>();
        int succeeded = 0;
        String lastError = null;
        for (Brochure brochure : relevant) {
            try {
                String json = http.get(URI.create(BASE + "api/personalisedOffers?brochureId="
                        + brochure.contentId() + "&size=1000&bonialAccountId=" + accountId
                        + "&userPlatformCategory=desktop"), Map.of());
                for (RawOffer offer : offersOf(readTree(json, "Angebote " + brochure.publisherName()),
                        brochure, marketIds.get(brochure))) {
                    offersById.putIfAbsent(offer.sourceOfferId(), offer);
                }
                succeeded++;
            } catch (SourceFetchException e) {
                lastError = e.getMessage();
                log.warn("kaufDA: Prospekt {} ({}) nicht abrufbar: {}", brochure.contentId(),
                        brochure.publisherName(), e.getMessage());
            }
        }
        if (!relevant.isEmpty() && succeeded == 0) {
            throw new SourceFetchException("Kein Prospekt abrufbar (" + lastError + ")");
        }
        return new ArrayList<>(offersById.values());
    }

    /** Nachsichtig: eine unlesbare Antwort ergibt eine leere Liste (der Abrufpfad prüft strikt). */
    List<Brochure> parseShelf(String json) {
        try {
            return brochuresOf(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            log.warn("kaufDA: Regalantwort nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    /** Nachsichtig wie {@link #parseShelf}; Angebote gelten kettenweit (kein Markt). */
    List<RawOffer> parseOffers(String json, Brochure brochure) {
        try {
            return offersOf(MAPPER.readTree(json), brochure, null);
        } catch (JsonProcessingException e) {
            log.warn("kaufDA: Angebotsantwort nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    /** Liest die Prospekt-Id des Aldi-Süd-Prospekts aus dem {@code __NEXT_DATA__} der Händlerseite. */
    Optional<String> aldiSuedBrochureId(String html) {
        Matcher matcher = NEXT_DATA.matcher(html);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            JsonNode items = MAPPER.readTree(matcher.group(1))
                    .path("props").path("pageProps").path("pageInformation").path("offers").path("main").path("items");
            for (JsonNode item : items) {
                JsonNode parent = item.path("parentContent");
                String id = parent.path("id").asText("");
                if (!id.isEmpty() && "static_brochure".equals(parent.path("type").asText(""))
                        && chainOf(item.path("publisherName").asText("")).orElse(null) == Chain.ALDI_SUED) {
                    return Optional.of(id);
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("kaufDA: Aldi-Süd-Seite nicht lesbar: {}", e.getMessage());
        }
        return Optional.empty();
    }

    static Optional<Chain> chainOf(String publisherName) {
        String key = publisherName.toLowerCase().replaceAll("[^a-z]", "");
        if (key.startsWith("lidl")) {
            return Optional.of(Chain.LIDL);
        } else if (key.startsWith("rewe")) {
            return Optional.of(Chain.REWE);
        } else if (key.startsWith("penny")) {
            return Optional.of(Chain.PENNY);
        } else if (key.startsWith("edeka") || key.startsWith("ecenter")) {
            return Optional.of(Chain.EDEKA);
        } else if (key.startsWith("kaufland")) {
            return Optional.of(Chain.KAUFLAND);
        } else if (key.startsWith("aldis")) {
            return Optional.of(Chain.ALDI_SUED);
        } else if (key.equals("netto") || key.startsWith("nettomarken")) {
            return Optional.of(Chain.NETTO_MD);
        } else if (key.startsWith("trinkgut")) {
            return Optional.of(Chain.TRINKGUT);
        } else if (key.startsWith("alldrink")) {
            return Optional.of(Chain.ALLDRINK);
        }
        return Optional.empty();
    }

    private List<Brochure> fetchShelf(double lat, double lng) throws SourceFetchException {
        List<Brochure> brochures = new ArrayList<>();
        int totalPages = 1;
        for (int page = 0; page < Math.min(totalPages, MAX_SHELF_PAGES); page++) {
            String json = http.get(URI.create(BASE + "api/shelf?lat=" + lat + "&lng=" + lng
                    + "&size=" + SHELF_PAGE_SIZE + "&page=" + page + "&sectorIds="), Map.of());
            JsonNode tree = readTree(json, "Regal");
            brochures.addAll(brochuresOf(tree));
            totalPages = tree.path("page").path("totalPages").asInt(1);
        }
        if (totalPages > MAX_SHELF_PAGES) {
            log.warn("kaufDA: Regal hat {} Seiten, nur die ersten {} werden gelesen", totalPages, MAX_SHELF_PAGES);
        }
        return brochures;
    }

    private Optional<Brochure> aldiSuedBrochure() throws SourceFetchException {
        String html = http.get(URI.create(BASE + "Geschaefte/Aldi-Sued"), Map.of());
        Optional<String> id = aldiSuedBrochureId(html);
        if (id.isEmpty()) {
            throw new SourceFetchException("Aldi-Süd-Prospekt nicht gefunden");
        }
        return Optional.of(new Brochure(id.get(), "ALDI SÜD", Chain.ALDI_SUED, null, null, null, null, null, null));
    }

    private static Optional<String> selectedMarketId(Brochure brochure, List<MarketCandidate> selected) {
        String street = normalizeStreet(brochure.storeStreet());
        for (MarketCandidate market : selected) {
            if (market.chain() == brochure.chain()
                    && brochure.storeZip() != null && brochure.storeZip().equals(zipOf(market.address()))
                    && street.equals(streetOf(market.address()))) {
                return Optional.of(market.externalId());
            }
        }
        return Optional.empty();
    }

    /** PLZ aus {@code "<Straße Hausnr>, <PLZ> <Ort>"}. */
    private static String zipOf(String address) {
        if (address == null) {
            return null;
        }
        Matcher matcher = ZIP.matcher(address.substring(address.indexOf(',') + 1));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String streetOf(String address) {
        if (address == null) {
            return "";
        }
        int comma = address.indexOf(',');
        return normalizeStreet(comma < 0 ? address : address.substring(0, comma));
    }

    /** Klein, ohne Hausnummer, „str.“ = „straße“, Umlaute umgeschrieben, nur Buchstaben und Ziffern. */
    static String normalizeStreet(String street) {
        if (street == null) {
            return "";
        }
        String s = HOUSE_NUMBER.matcher(street.trim()).replaceFirst("").toLowerCase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("str\\.", "strasse").replaceAll("str\\b", "strasse");
        return s.replaceAll("[^a-z0-9]", "");
    }

    private List<Brochure> brochuresOf(JsonNode tree) {
        List<Brochure> brochures = new ArrayList<>();
        for (JsonNode entry : tree.path("contents")) {
            String type = entry.path("contentType").asText("");
            JsonNode content = entry.path("content");
            if (!type.equals("brochure") && !type.equals("brochurePremium")) {
                continue;
            }
            String contentId = content.path("contentId").asText("");
            String publisher = content.path("publisher").path("name").asText("");
            Optional<Chain> chain = chainOf(publisher);
            if (contentId.isEmpty() || chain.isEmpty()) {
                continue;
            }
            JsonNode store = content.path("closestStore");
            String zip = store.path("zip").asText("");
            String street = store.path("street").asText("");
            brochures.add(new Brochure(contentId, publisher, chain.get(), store.path("name").asText(null),
                    zip.isEmpty() ? null : zip, street, storeCandidate(chain.get(), store, zip, street),
                    date(content.path("validFrom"), false), date(content.path("validUntil"), true)));
        }
        return brochures;
    }

    private static MarketCandidate storeCandidate(Chain chain, JsonNode store, String zip, String street) {
        if (zip.isEmpty() || street.isEmpty()) {
            return null;
        }
        String number = store.path("streetNumber").asText("").trim();
        String address = (number.isEmpty() ? street : street + " " + number) + ", " + zip + " "
                + store.path("city").asText("");
        Double lat = coordinate(store, "latitude", "lat");
        Double lng = coordinate(store, "longitude", "lng");
        return new MarketCandidate(chain, "kaufda:" + zip + ":" + normalizeStreet(street),
                store.path("name").asText(chain.displayName()), address.trim(), lat, lng, null);
    }

    private static Double coordinate(JsonNode store, String name, String shortName) {
        JsonNode node = store.has(name) ? store.path(name) : store.path(shortName);
        return node.isNumber() ? node.doubleValue() : null;
    }

    private List<RawOffer> offersOf(JsonNode tree, Brochure brochure, String marketExternalId) {
        List<RawOffer> offers = new ArrayList<>();
        for (JsonNode node : tree.path("contents")) {
            parseOffer(node, brochure, marketExternalId).ifPresent(offers::add);
        }
        return offers;
    }

    private Optional<RawOffer> parseOffer(JsonNode node, Brochure brochure, String marketExternalId) {
        String id = node.path("id").asText("");
        String title = node.path("title").asText("");
        JsonNode prices = node.path("prices");
        BigDecimal main = decimal(prices.path("mainPrice"));
        BigDecimal secondary = decimal(prices.path("secondaryPrice"));
        if (id.isEmpty() || title.isEmpty() || main == null || main.signum() <= 0) {
            return Optional.empty();
        }
        boolean secondaryIsUvp = !prices.has("secondaryPriceIsUVP") || prices.path("secondaryPriceIsUVP").asBoolean(true);
        boolean hasNormalPrice = secondary != null && !secondaryIsUvp && secondary.compareTo(main) > 0;
        boolean loyalty = isLoyaltyCondition(prices.path("conditions"));
        if (loyalty && !hasNormalPrice) {
            return Optional.empty();
        }
        BigDecimal price = loyalty ? secondary : main;
        String description = textOrNull(node.path("description"));
        String basePriceText = textOrNull(prices.path("priceByBaseUnit"));
        BasePrice basePrice = BasePriceParser.resolve(null, basePriceText, null, description, main).orElse(null);
        if (loyalty && basePrice != null) {
            basePrice = new BasePrice(basePrice.amount().multiply(price)
                    .divide(main, 3, RoundingMode.HALF_UP), basePrice.unit());
        }
        return Optional.of(RawOffer.builder()
                .source(SourceKey.KAUFDA)
                .sourceOfferId(id)
                .chain(brochure.chain())
                .marketExternalId(marketExternalId)
                .title(title)
                .brand(textOrNull(node.path("brand")))
                .description(description)
                .price(price)
                .oldPrice(!loyalty && hasNormalPrice ? secondary : null)
                .loyaltyPrice(loyalty ? main : null)
                .basePrice(basePrice)
                .basePriceText(basePriceText)
                .validFrom(firstNonNull(date(node.path("validFrom"), false), brochure.validFrom()))
                .validTo(firstNonNull(date(node.path("validUntil"), true), brochure.validUntil()))
                .imageUrl(textOrNull(node.path("offerImages").path("url").path("normal")))
                .build());
    }

    private static boolean isLoyaltyCondition(JsonNode conditions) {
        for (JsonNode condition : conditions) {
            for (JsonNode text : condition) {
                if (LOYALTY_CONDITION.matcher(text.asText("")).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static String textOrNull(JsonNode node) {
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    /** kaufDA liefert UTC-Zeitstempel; Enden stehen auf Mitternacht des Folgetags und zählen zum Vortag. */
    private static LocalDate date(JsonNode node, boolean endOfValidity) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            var berlin = OffsetDateTime.parse(node.asText(), KAUFDA_TIMESTAMP).atZoneSameInstant(BERLIN);
            LocalDate date = berlin.toLocalDate();
            return endOfValidity && berlin.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? date.minusDays(1) : date;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static JsonNode readTree(String json, String what) throws SourceFetchException {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new SourceFetchException("Antwort nicht lesbar (" + what + ")", e);
        }
    }
}
