package com.household.manager.grocery.source;

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
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Norma deutschlandweit von norma-online.de: Die Übersicht verlinkt je Angebotstag eine HTML-Seite
 * ({@code /de/angebote/ab-freitag,-20.11.26/}); Angebote gelten bis Samstag derselben Woche.
 */
@Component
@Slf4j
public class NormaSource implements OfferSource {

    private static final String BASE = "https://www.norma-online.de";
    private static final Pattern DAY_LINK =
            Pattern.compile("^/de/angebote/ab-[a-z]+,-(\\d{2})\\.(\\d{2})\\.(\\d{2})/?$");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private static final Pattern APP_PRICE = Pattern.compile(
            "mit der NORMA Pluss App.*?billiger:\\s*(\\d+(?:[.,]\\d+)?)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WAS_PRICE =
            Pattern.compile("^statt\\s+(\\d+(?:[.,]\\d+)?)", Pattern.CASE_INSENSITIVE);

    private final GroceryHttp http;
    private final GroceryProperties properties;

    public NormaSource(GroceryHttp http, GroceryProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public SourceKey key() {
        return SourceKey.NORMA;
    }

    @Override
    public boolean enabled() {
        return properties.isSourceEnabled(SourceKey.NORMA);
    }

    @Override
    public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
        if (!ctx.enabledChains().contains(Chain.NORMA)) {
            return List.of();
        }
        List<String> dayPaths = dayLinks(http.get(URI.create(BASE + "/de/angebote/"), Map.of()));
        if (dayPaths.isEmpty()) {
            throw new SourceFetchException("Keine Norma-Tagesseiten gefunden");
        }
        List<RawOffer> offers = new ArrayList<>();
        int succeeded = 0;
        String lastError = null;
        for (String path : dayPaths) {
            try {
                offers.addAll(parseDayPage(http.get(URI.create(BASE + path), Map.of()), startOf(path)));
                succeeded++;
            } catch (SourceFetchException e) {
                lastError = e.getMessage();
                log.warn("Norma: Tagesseite {} nicht abrufbar: {}", path, e.getMessage());
            }
        }
        if (succeeded == 0) {
            throw new SourceFetchException("Keine Norma-Tagesseite abrufbar (" + lastError + ")");
        }
        if (succeeded < dayPaths.size()) {
            log.warn("Norma: nur {} von {} Tagesseiten erfolgreich", succeeded, dayPaths.size());
        }
        return offers;
    }

    /** Pfade der Tagesseiten in Reihenfolge des Auftretens, ohne Doppelte. */
    List<String> dayLinks(String overviewHtml) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Element link : Jsoup.parse(overviewHtml).select("a[href]")) {
            String href = link.attr("href");
            if (DAY_LINK.matcher(href).matches()) {
                paths.add(href.endsWith("/") ? href : href + "/");
            }
        }
        return new ArrayList<>(paths);
    }

    /** Wirft, wenn die Seite keinen einzigen Artikel trägt (Seite unlesbar statt leerer Tag). */
    List<RawOffer> parseDayPage(String html, LocalDate validFrom) throws SourceFetchException {
        Document page = Jsoup.parse(html);
        List<Element> articles = page.select("article.produktBoxContainer");
        if (articles.isEmpty()) {
            throw new SourceFetchException("Antwort nicht lesbar (keine Artikel)");
        }
        LocalDate validTo = validFrom == null ? null : validFrom.with(DayOfWeek.SATURDAY);
        List<RawOffer> offers = new ArrayList<>();
        for (Element article : articles) {
            parseArticle(article, validFrom, validTo).ifPresent(offers::add);
        }
        return offers;
    }

    static LocalDate startOf(String dayPath) {
        Matcher matcher = DAY_LINK.matcher(dayPath);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDate.of(2000 + Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** {@code price} ist der Normalpreis; ein UVP ist nie Vergleichspreis, der App-Preis steht in {@code loyaltyPrice}. */
    private Optional<RawOffer> parseArticle(Element article, LocalDate from, LocalDate to) {
        String id = article.id().replaceFirst("^of_", "");
        String name = text(article.selectFirst(".produktBox-txt-headline"));
        Element priceElement = article.selectFirst(".produktBox-cont-wrapper-price span");
        BigDecimal price = priceElement == null ? null : number(priceElement.attr("aria-label"));
        if (id.isEmpty() || name == null || price == null) {
            return Optional.empty();
        }
        BigDecimal oldPrice = null;
        Element was = article.selectFirst(".produktBox-cont-wrapper-uvp");
        if (was != null) {
            Matcher matcher = WAS_PRICE.matcher(was.text().trim());
            oldPrice = matcher.find() ? number(matcher.group(1)) : null;
        }
        if (oldPrice != null && oldPrice.compareTo(price) <= 0) {
            oldPrice = null;
        }
        Element image = article.selectFirst("img");
        BigDecimal loyaltyPrice = null;
        if (image != null) {
            Matcher matcher = APP_PRICE.matcher(image.attr("alt"));
            loyaltyPrice = matcher.find() ? number(matcher.group(1)) : null;
        }
        String packaging = text(article.selectFirst(".produktBox-txt-inh"));
        String description = text(article.selectFirst(".produktBox-txt-description"));
        String basePriceText = normalBasePriceText(article);
        BasePrice basePrice = BasePriceParser.resolve(null, basePriceText, packaging, description, price).orElse(null);
        return Optional.of(RawOffer.builder()
                .source(SourceKey.NORMA)
                .sourceOfferId(id)
                .chain(Chain.NORMA)
                .title(name)
                .brand(text(article.selectFirst(".supplier")))
                .description(description)
                .packaging(packaging)
                .price(price)
                .oldPrice(oldPrice)
                .loyaltyPrice(loyaltyPrice)
                .basePrice(basePrice)
                .basePriceText(basePriceText)
                .validFrom(from)
                .validTo(to)
                .imageUrl(image == null || image.attr("src").isBlank() ? null : absolute(image.attr("src")))
                .build());
    }

    /** Bei App-Angeboten stehen zwei Grundpreise; es zählt der ohne App. */
    private static String normalBasePriceText(Element article) {
        for (Element line : article.select(".produktBox-txt-price")) {
            String text = line.text().trim();
            if (!text.isEmpty() && !text.toLowerCase().contains("mit app")) {
                return text.replaceFirst("(?i)\\s*ohne App\\s*$", "");
            }
        }
        return null;
    }

    private static String absolute(String src) {
        return src.startsWith("http") ? src : BASE + (src.startsWith("/") ? "" : "/") + src;
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.text().trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal number(String text) {
        Matcher matcher = NUMBER.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal value = new BigDecimal(matcher.group(1).replace(',', '.'));
        return value.signum() > 0 ? value : null;
    }
}
