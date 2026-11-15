package com.household.manager.grocery.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BasePriceParser;
import com.household.manager.grocery.pricing.BaseUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggregator marktguru.de: Händlerliste über die Publisher-Übersicht, je Händler seitenweise die Angebote.
 * Die API-Schlüssel stehen im Quelltext der Startseite und werden je Lauf frisch geholt.
 */
@Component
@Slf4j
public class MarktguruSource implements OfferSource {

    private static final String HOME_URL = "https://www.marktguru.de/";
    private static final String API = "https://api.marktguru.de/api/v1/";
    private static final String IMAGE_URL = "https://cdn.marktguru.de/api/v1/offers/%s/images/default/0/small.webp";
    private static final int PAGE_SIZE = 512;
    private static final int MAX_PAGES = 20;
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Pattern API_KEY = Pattern.compile("\"apiKey\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CLIENT_KEY = Pattern.compile("\"clientKey\"\\s*:\\s*\"([^\"]+)\"");
    private static final String NO_BRAND_PREFIX = "thisisnobrand";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GroceryHttp http;
    private final GroceryProperties properties;

    public MarktguruSource(GroceryHttp http, GroceryProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    record Keys(String apiKey, String clientKey) {
    }

    @Override
    public SourceKey key() {
        return SourceKey.MARKTGURU;
    }

    @Override
    public boolean enabled() {
        return properties.isSourceEnabled(SourceKey.MARKTGURU);
    }

    @Override
    public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
        Session session = new Session();
        String publishers = session.get(API + "offers/publishers?as=mobile&zipCode=" + ctx.zip()
                + "&limit=50&offerLimit=5");
        Map<String, RawOffer> offersById = new LinkedHashMap<>();
        for (String uniqueName : retailerNames(publishers)) {
            Optional<Chain> chain = chainOf(uniqueName).filter(ctx.enabledChains()::contains);
            if (chain.isEmpty()) {
                continue;
            }
            for (RawOffer offer : fetchRetailer(session, ctx.zip(), uniqueName, chain.get())) {
                offersById.putIfAbsent(offer.sourceOfferId(), offer);
            }
        }
        return new ArrayList<>(offersById.values());
    }

    private List<RawOffer> fetchRetailer(Session session, String zip, String uniqueName, Chain chain)
            throws SourceFetchException {
        List<RawOffer> result = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = API + "publishers/retailer/" + uniqueName + "/offers?as=web&zipCode=" + zip
                    + "&limit=" + PAGE_SIZE + "&offset=" + (page * PAGE_SIZE);
            List<RawOffer> offers = offersOf(readTree(session.get(url), uniqueName), chain);
            result.addAll(offers);
            if (offers.size() < PAGE_SIZE) {
                return result;
            }
        }
        log.warn("marktguru: Seitenlimit ({}) für {} erreicht, weitere Angebote werden ignoriert",
                MAX_PAGES, uniqueName);
        return result;
    }

    private List<String> retailerNames(String publishersJson) throws SourceFetchException {
        List<String> names = new ArrayList<>();
        for (JsonNode publisher : readTree(publishersJson, "Händlerliste").path("results")) {
            String name = publisher.path("uniqueName").asText("");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    static Optional<Chain> chainOf(String uniqueName) {
        return switch (uniqueName) {
            case "rewe", "rewe-center" -> Optional.of(Chain.REWE);
            case "penny" -> Optional.of(Chain.PENNY);
            case "kaufland" -> Optional.of(Chain.KAUFLAND);
            case "edeka", "e-center", "edeka-center" -> Optional.of(Chain.EDEKA);
            case "lidl" -> Optional.of(Chain.LIDL);
            case "aldi-sued" -> Optional.of(Chain.ALDI_SUED);
            case "netto-marken-discount" -> Optional.of(Chain.NETTO_MD);
            default -> Optional.empty();
        };
    }

    Optional<Keys> parseKeys(String html) {
        Matcher api = API_KEY.matcher(html);
        Matcher client = CLIENT_KEY.matcher(html);
        if (api.find() && client.find()) {
            return Optional.of(new Keys(api.group(1), client.group(1)));
        }
        return Optional.empty();
    }

    /** Nachsichtig: eine unlesbare Antwort ergibt eine leere Liste (der Abrufpfad prüft strikt). */
    List<RawOffer> parseRetailerOffers(String json, Chain chain) {
        try {
            return offersOf(MAPPER.readTree(json), chain);
        } catch (JsonProcessingException e) {
            log.warn("marktguru: Angebotsantwort nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RawOffer> offersOf(JsonNode tree, Chain chain) {
        List<RawOffer> offers = new ArrayList<>();
        for (JsonNode node : tree.path("results")) {
            parseOffer(node, chain).ifPresent(offers::add);
        }
        return offers;
    }

    private Optional<RawOffer> parseOffer(JsonNode node, Chain chain) {
        String id = node.path("id").asText("");
        BigDecimal price = decimal(node.path("price"));
        String title = node.path("product").path("name").asText("");
        if (id.isEmpty() || price == null || title.isEmpty()) {
            return Optional.empty();
        }
        String description = textOrNull(node.path("description"));
        BigDecimal oldPrice = decimal(node.path("oldPrice"));
        BasePrice structured = structuredBasePrice(node);
        BasePrice basePrice = BasePriceParser.resolve(structured, null, null, description, price).orElse(null);
        return Optional.of(RawOffer.builder()
                .source(SourceKey.MARKTGURU)
                .sourceOfferId(id)
                .chain(chain)
                .title(title)
                .brand(brand(node.path("brand")))
                .description(description)
                .price(price)
                .oldPrice(oldPrice != null && oldPrice.signum() > 0 ? oldPrice : null)
                .basePrice(basePrice)
                .validFrom(date(node.path("validFrom")))
                .validTo(date(node.path("validTo")))
                .imageUrl(IMAGE_URL.formatted(id))
                .build());
    }

    private static BasePrice structuredBasePrice(JsonNode node) {
        BigDecimal reference = decimal(node.path("referencePrice"));
        if (reference == null || reference.signum() <= 0) {
            return null;
        }
        return switch (node.path("unit").path("shortName").asText("")) {
            case "l" -> new BasePrice(reference, BaseUnit.LITER);
            case "kg" -> new BasePrice(reference, BaseUnit.KILOGRAM);
            case "Stk" -> new BasePrice(reference, BaseUnit.PIECE);
            default -> null;
        };
    }

    private static String brand(JsonNode brand) {
        String name = brand.path("name").asText("");
        return name.isEmpty() || name.startsWith(NO_BRAND_PREFIX) ? null : name;
    }

    private static String textOrNull(JsonNode node) {
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }

    private static LocalDate date(JsonNode node) {
        try {
            return node.isTextual() ? Instant.parse(node.asText()).atZone(BERLIN).toLocalDate() : null;
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

    /** Schlüssel eines Abrufs: einmal geholt, bei 401 genau einmal erneuert. */
    private final class Session {

        private Keys keys;

        String get(String url) throws SourceFetchException {
            try {
                return http.get(URI.create(url), headers(keys()));
            } catch (HttpStatusException e) {
                if (e.status() != 401) {
                    throw e;
                }
                keys = null;
                return http.get(URI.create(url), headers(keys()));
            }
        }

        private Keys keys() throws SourceFetchException {
            if (keys == null) {
                keys = parseKeys(http.get(URI.create(HOME_URL), Map.of()))
                        .orElseThrow(() -> new SourceFetchException("API-Schlüssel nicht gefunden"));
            }
            return keys;
        }

        private Map<String, String> headers(Keys current) {
            return Map.of("x-apikey", current.apiKey(), "x-clientkey", current.clientKey());
        }
    }
}
