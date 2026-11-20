package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BasePriceParser;
import com.household.manager.grocery.pricing.BaseUnit;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * logo Getränke-Fachmarkt von logo-getraenke.de: die Seite {@code /angebote/} trägt die Wochenangebote aller
 * Märkte direkt im HTML (Abschnitte „Wochenangebote“, „Biere“, „Wasser“ …, ein Produkt kann in mehreren stehen).
 * Weder marktguru (nur veraltete Angebote) noch kaufDA (kein Prospekt) führen logo aktuell.
 */
@Component
@Slf4j
public class LogoSource implements OfferSource {

    private static final String URL = "https://www.logo-getraenke.de/angebote/";
    private static final Pattern WEEK = Pattern.compile(
            "(\\d{1,2})\\.(\\d{1,2})\\.(?:(\\d{4}))?\\s*-\\s*(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
    private static final Pattern PRICE = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private static final Pattern IMAGE_ID = Pattern.compile("/product_images/(\\d+)\\.");
    /** Grundpreis im Inhaltstext, z. B. „20/0,5 Ltr. Kasten + € 3,10 Pfd. Ltr. = € 1,20“. */
    private static final Pattern UNIT_PRICE = Pattern.compile(
            "\\b(Ltr|kg)\\.?\\s*=\\s*€\\s*(\\d+(?:[.,]\\d+)?)", Pattern.CASE_INSENSITIVE);

    private final GroceryHttp http;
    private final GroceryProperties properties;

    public LogoSource(GroceryHttp http, GroceryProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public SourceKey key() {
        return SourceKey.LOGO;
    }

    @Override
    public boolean enabled() {
        return properties.isSourceEnabled(SourceKey.LOGO);
    }

    @Override
    public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
        if (!ctx.enabledChains().contains(Chain.LOGO)) {
            return List.of();
        }
        return parse(http.get(URI.create(URL), Map.of()));
    }

    /** Wirft, wenn die Seite keinen einzigen Artikel trägt (Seite unlesbar statt leerer Woche). */
    List<RawOffer> parse(String html) throws SourceFetchException {
        Document page = Jsoup.parse(html);
        List<Element> cards = page.select(".top-offers .prodcut-details");
        if (cards.isEmpty()) {
            throw new SourceFetchException("Antwort nicht lesbar (keine Artikel)");
        }
        Element date = page.selectFirst(".top-offers .date");
        LocalDate[] week = week(date == null ? null : date.text());
        Map<String, RawOffer> offers = new LinkedHashMap<>();
        for (Element card : cards) {
            parseCard(card, week[0], week[1]).ifPresent(offer -> offers.putIfAbsent(offer.sourceOfferId(), offer));
        }
        return List.copyOf(offers.values());
    }

    /** {@code [von, bis]} aus „16.11.-21.11.2026“; ohne lesbare Angabe beide {@code null}. */
    static LocalDate[] week(String text) {
        if (text != null) {
            Matcher m = WEEK.matcher(text);
            if (m.find()) {
                try {
                    int endYear = Integer.parseInt(m.group(6));
                    LocalDate to = LocalDate.of(endYear, Integer.parseInt(m.group(5)), Integer.parseInt(m.group(4)));
                    int startMonth = Integer.parseInt(m.group(2));
                    int startYear = m.group(3) != null ? Integer.parseInt(m.group(3))
                            : startMonth > to.getMonthValue() ? endYear - 1 : endYear;
                    return new LocalDate[] {LocalDate.of(startYear, startMonth, Integer.parseInt(m.group(1))), to};
                } catch (DateTimeException | NumberFormatException e) {
                    log.warn("logo: Angebotszeitraum nicht lesbar: {}", text);
                }
            }
        }
        return new LocalDate[] {null, null};
    }

    /** {@code price} ist der Angebotspreis, der durchgestrichene ist nur {@code oldPrice} (nie Maßstab). */
    private Optional<RawOffer> parseCard(Element card, LocalDate from, LocalDate to) {
        String title = text(card.selectFirst("h6"));
        BigDecimal price = number(text(card.selectFirst(".price span")));
        if (title == null || price == null) {
            return Optional.empty();
        }
        title = title.replaceFirst("\\*$", "").trim();
        BigDecimal oldPrice = number(text(card.selectFirst(".price del")));
        if (oldPrice != null && oldPrice.compareTo(price) <= 0) {
            oldPrice = null;
        }
        String content = text(card.selectFirst(".product-content"));
        String description = text(card.selectFirst(".product-desc"));
        Element image = card.selectFirst(".product img");
        String imageUrl = image == null || image.attr("src").isBlank() ? null : image.attr("src");
        return Optional.of(RawOffer.builder()
                .source(SourceKey.LOGO)
                .sourceOfferId(offerId(imageUrl, title, content, price))
                .chain(Chain.LOGO)
                .title(title)
                .description(description)
                .packaging(content)
                .price(price)
                .oldPrice(oldPrice)
                .basePrice(basePrice(content, description, price).orElse(null))
                .validFrom(from)
                .validTo(to)
                .imageUrl(imageUrl)
                .build());
    }

    /** Der Grundpreis steht ausgeschrieben im Inhaltstext; sonst aus der Packung („0,7 Ltr.“ ⇒ „0,7 l“). */
    static Optional<BasePrice> basePrice(String content, String description, BigDecimal price) {
        if (content != null) {
            Matcher m = UNIT_PRICE.matcher(content);
            if (m.find()) {
                BaseUnit unit = m.group(1).equalsIgnoreCase("kg") ? BaseUnit.KILOGRAM : BaseUnit.LITER;
                return Optional.of(new BasePrice(new BigDecimal(m.group(2).replace(',', '.')), unit));
            }
        }
        String packaging = content == null ? null : content.replaceAll("(?i)\\bLtr\\.?", "l");
        return BasePriceParser.resolve(null, null, packaging, description, price);
    }

    /** Die Bildnummer ist die Artikelnummer; ohne Bild ein Schlüssel aus Titel, Inhalt und Preis. */
    private static String offerId(String imageUrl, String title, String content, BigDecimal price) {
        if (imageUrl != null) {
            Matcher m = IMAGE_ID.matcher(imageUrl);
            if (m.find()) {
                return m.group(1);
            }
        }
        return Integer.toHexString((title + "|" + content + "|" + price.toPlainString()).hashCode());
    }

    private static BigDecimal number(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PRICE.matcher(text);
        return m.find() ? new BigDecimal(m.group(1).replace(',', '.')) : null;
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.text().trim();
        return text.isEmpty() ? null : text;
    }
}
