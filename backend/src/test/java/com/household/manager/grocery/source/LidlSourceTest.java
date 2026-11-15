package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LidlSourceTest {

    private static final String URL = "https://www.lidl.de/q/api/search";
    private static final String PAGE_URL =
            URL + "?assortment=DE&locale=de_DE&version=2.1.0&q=*&store=1&fetchsize=60&offset=";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("Europe/Berlin"));

    private final GroceryProperties properties = new GroceryProperties();

    private LidlSource source(FakeHttpTransport transport) {
        return new LidlSource(new GroceryHttp(transport), properties, CLOCK);
    }

    private static FetchContext context(Set<Chain> chains) {
        return new FetchContext("60386", 50.13, 8.75, List.of(), chains);
    }

    private static String food(int id, String extra, String badge) {
        return item(id, "Food", "\"price\":{\"price\":1.59,\"discount\":{\"deletedPrice\":1.69},"
                + "\"packaging\":{\"text\":\"Je 200 g\"},\"basePrice\":{\"text\":\"1 kg = 7.95\"}}" + extra, badge);
    }

    private static String item(int id, String category, String price, String badge) {
        return "{\"gridbox\":{\"data\":{\"productId\":" + id + ",\"fullTitle\":\"Produkt " + id
                + "\",\"category\":\"" + category + "\",\"brand\":{\"name\":\"MARKE\"},"
                + "\"image\":\"https://www.lidl.de/assets/" + id + ".png\"," + price
                + ",\"stockAvailability\":{\"badgeInfo\":{\"badges\":[{\"text\":\"" + badge + "\"}]}}}}}";
    }

    private static String page(int numFound, String... items) {
        return "{\"numFound\":" + numFound + ",\"items\":[" + String.join(",", items) + "]}";
    }

    @Test
    void parsesOnlyFoodStoreOffers() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond(URL, 200, page(2, food(1, "", "nur in der Filiale"),
                item(2, "NonFood", "\"price\":{\"price\":9.99}", "nur in der Filiale")));

        List<RawOffer> offers = source(transport).fetch(context(Set.of(Chain.LIDL)));

        assertThat(offers).hasSize(1);
        RawOffer offer = offers.get(0);
        assertThat(offer.sourceOfferId()).isEqualTo("1");
        assertThat(offer.chain()).isEqualTo(Chain.LIDL);
        assertThat(offer.marketExternalId()).isNull();
        assertThat(offer.price()).isEqualByComparingTo("1.59");
        assertThat(offer.oldPrice()).isEqualByComparingTo("1.69");
        assertThat(offer.packaging()).isEqualTo("Je 200 g");
        assertThat(offer.brand()).isEqualTo("MARKE");
        assertThat(offer.validFrom()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(offer.validTo()).isEqualTo(LocalDate.of(2026, 11, 14));
    }

    @Test
    void lidlPlusPriceIsLoyaltyPrice() throws Exception {
        String plus = ",\"lidlPlus\":[{\"price\":{\"price\":1.43}}]";
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond(URL, 200, page(1, food(1, plus, "nur in der Filiale von 16.11. - 21.11.")));

        RawOffer offer = source(transport).fetch(context(Set.of(Chain.LIDL))).get(0);

        assertThat(offer.price()).isEqualByComparingTo("1.59");
        assertThat(offer.loyaltyPrice()).isEqualByComparingTo(new BigDecimal("1.43"));
        assertThat(offer.validFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(offer.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
    }

    @Test
    void doesNotSendAcceptJsonHeader() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond(URL, 200, page(1, food(1, "", "nur in der Filiale")));

        source(transport).fetch(context(Set.of(Chain.LIDL)));

        assertThat(transport.requests()).isNotEmpty().allSatisfy(request ->
                assertThat(request.headers().allValues("Accept")).noneMatch(v -> v.contains("application/json")));
    }

    @Test
    void pagesInStepsOf60() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond(PAGE_URL + "0", 200, page(130, food(1, "", "x")));
        transport.respond(PAGE_URL + "60", 200, page(130, food(2, "", "x")));
        transport.respond(PAGE_URL + "120", 200, page(130, food(3, "", "x")));

        List<RawOffer> offers = source(transport).fetch(context(Set.of(Chain.LIDL)));

        assertThat(offers).extracting(RawOffer::sourceOfferId).containsExactly("1", "2", "3");
        assertThat(transport.requestedUrls()).hasSize(3);
    }

    @Test
    void failedLaterPageKeepsCollectedOffers() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond(PAGE_URL + "0", 200, page(130, food(1, "", "x")));
        transport.respond(PAGE_URL + "60", 500, "");

        assertThat(source(transport).fetch(context(Set.of(Chain.LIDL)))).hasSize(1);
    }

    @Test
    void failedFirstPageThrows() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond(URL, 401, "");

        assertThatThrownBy(() -> source(transport).fetch(context(Set.of(Chain.LIDL))))
                .isInstanceOf(SourceFetchException.class);
    }

    @Test
    void skipsWhenChainNotEnabled() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();

        assertThat(source(transport).fetch(context(Set.of(Chain.REWE)))).isEmpty();
        assertThat(transport.requests()).isEmpty();
    }
}
