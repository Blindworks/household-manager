package com.household.manager.grocery.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BasePriceParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lidl-Filialangebote (nur Lebensmittel) aus der Such-JSON von lidl.de. Das Sortiment gilt deutschlandweit
 * (kein Markt-Bezug). Ergänzung zu marktguru, das Lidl vollständiger abdeckt.
 */
@Component
@Slf4j
public class LidlSource implements OfferSource {

    static final int PAGE_SIZE = 60;
    static final int MAX_PAGES = 15;
    private static final String SEARCH_URL = "https://www.lidl.de/q/api/search"
            + "?assortment=DE&locale=de_DE&version=2.1.0&q=*&store=1&fetchsize=" + PAGE_SIZE + "&offset=";
    /** Ein Accept: application/json beantwortet Lidl mit 401. */
    private static final Map<String, String> HEADERS = Map.of("Accept", "*/*");
    private static final Pattern BADGE_DATES =
            Pattern.compile("von\\s+(\\d{1,2})\\.(\\d{1,2})\\.\\s*-\\s*(\\d{1,2})\\.(\\d{1,2})\\.");

    private final GroceryHttp http;
    private final GroceryProperties properties;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public LidlSource(GroceryHttp http, GroceryProperties properties, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public SourceKey key() {
        return SourceKey.LIDL;
    }

    @Override
    public boolean enabled() {
        return properties.isSourceEnabled(SourceKey.LIDL);
    }

    @Override
    public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
        if (!ctx.enabledChains().contains(Chain.LIDL)) {
            return List.of();
        }
        List<RawOffer> offers = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        for (int page = 0; page < MAX_PAGES && page * PAGE_SIZE < total; page++) {
            JsonNode root;
            try {
                root = parse(http.get(URI.create(SEARCH_URL + page * PAGE_SIZE), HEADERS));
            } catch (SourceFetchException e) {
                if (page == 0) {
                    throw e;
                }
                log.warn("Lidl: Seite {} nicht abrufbar, behalte {} Angebote: {}", page, offers.size(), e.getMessage());
                break;
            }
            JsonNode items = root.path("items");
            if (items.isEmpty()) {
                break;
            }
            total = root.path("numFound").asInt(Integer.MAX_VALUE);
            items.forEach(item -> parseItem(item.path("gridbox").path("data")).ifPresent(offers::add));
        }
        return offers;
    }

    private JsonNode parse(String body) throws SourceFetchException {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new SourceFetchException("Antwort nicht lesbar (" + e.getMessage() + ")", e);
        }
        if (root == null || !root.path("items").isArray()) {
            throw new SourceFetchException("Antwort nicht lesbar (keine Artikelliste)");
        }
        return root;
    }

    private Optional<RawOffer> parseItem(JsonNode data) {
        if (!"Food".equals(data.path("category").asText())) {
            return Optional.empty();
        }
        String title = data.path("fullTitle").asText("").trim();
        String id = data.path("productId").asText("");
        JsonNode priceNode = data.path("price");
        BigDecimal price = decimal(priceNode.path("price"));
        if (title.isEmpty() || id.isEmpty() || price == null) {
            return Optional.empty();
        }
        BigDecimal oldPrice = decimal(priceNode.path("discount").path("deletedPrice"));
        if (oldPrice != null && oldPrice.compareTo(price) <= 0) {
            oldPrice = null;
        }
        BigDecimal loyaltyPrice = null;
        JsonNode lidlPlus = data.path("lidlPlus");
        if (lidlPlus.isArray() && !lidlPlus.isEmpty()) {
            loyaltyPrice = decimal(lidlPlus.get(0).path("price").path("price"));
            if (loyaltyPrice != null && loyaltyPrice.compareTo(price) >= 0) {
                loyaltyPrice = null;
            }
        }
        String packaging = textOrNull(priceNode.path("packaging").path("text"));
        String basePriceText = textOrNull(priceNode.path("basePrice").path("text"));
        BasePrice basePrice = BasePriceParser.resolve(null, basePriceText, packaging, null, price).orElse(null);
        LocalDate[] validity = validity(data.path("stockAvailability").path("badgeInfo").path("badges"));
        return Optional.of(RawOffer.builder()
                .source(SourceKey.LIDL)
                .sourceOfferId(id)
                .chain(Chain.LIDL)
                .title(title)
                .brand(textOrNull(data.path("brand").path("name")))
                .packaging(packaging)
                .price(price)
                .oldPrice(oldPrice)
                .loyaltyPrice(loyaltyPrice)
                .basePrice(basePrice)
                .basePriceText(basePriceText)
                .validFrom(validity[0])
                .validTo(validity[1])
                .imageUrl(textOrNull(data.path("image")))
                .build());
    }

    /** Datum aus dem Filial-Badge, sonst die laufende Woche Montag bis Samstag. */
    private LocalDate[] validity(JsonNode badges) {
        LocalDate today = LocalDate.now(clock);
        if (badges.isArray()) {
            for (JsonNode badge : badges) {
                Matcher matcher = BADGE_DATES.matcher(badge.path("text").asText(""));
                if (matcher.find()) {
                    LocalDate[] range = badgeRange(matcher, today);
                    if (range != null) {
                        return range;
                    }
                }
            }
        }
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        return new LocalDate[]{monday, monday.with(DayOfWeek.SATURDAY)};
    }

    /** Das Badge nennt kein Jahr: Jahr des Abrufs, Dezember-Abruf mit Januar-Angebot rollt ins Folgejahr. */
    private static LocalDate[] badgeRange(Matcher matcher, LocalDate today) {
        try {
            int fromMonth = Integer.parseInt(matcher.group(2));
            int toMonth = Integer.parseInt(matcher.group(4));
            int year = fromMonth < today.getMonthValue() - 6 ? today.getYear() + 1 : today.getYear();
            LocalDate from = LocalDate.of(year, fromMonth, Integer.parseInt(matcher.group(1)));
            LocalDate to = LocalDate.of(toMonth < fromMonth ? year + 1 : year, toMonth,
                    Integer.parseInt(matcher.group(3)));
            return to.isBefore(from) ? null : new LocalDate[]{from, to};
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        return value.signum() > 0 ? value : null;
    }

    private static String textOrNull(JsonNode node) {
        String text = node.asText("").trim();
        return text.isEmpty() ? null : text;
    }
}
