package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BasePrice;
import com.household.manager.grocery.pricing.BaseUnit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarktguruSourceTest {

    private static final String BASE = "https://api.marktguru.de/api/v1/";
    private static final String PUBLISHERS = """
            {"results": [{"uniqueName": "rewe"}, {"uniqueName": "penny"}, {"uniqueName": "nahkauf"}]}
            """;

    private final GroceryProperties properties = new GroceryProperties();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/grocery/marktguru/" + name), StandardCharsets.UTF_8);
    }

    private MarktguruSource source(FakeHttpTransport transport) {
        return new MarktguruSource(new GroceryHttp(transport), properties);
    }

    private static FetchContext context(Chain... chains) {
        return new FetchContext("61118", 50.1, 8.7, List.of(), Set.of(chains));
    }

    private static String offersJson(int count, int firstId) {
        String results = IntStream.range(0, count).mapToObj(i -> """
                {"id": %d, "description": "x", "product": {"name": "Produkt %d"},
                 "brand": {"name": "Marke", "indexOffer": true}, "unit": {"shortName": "kg"},
                 "price": 1.0, "oldPrice": 0.0, "referencePrice": 2.0,
                 "validFrom": "2026-11-15T23:00:00Z", "validTo": "2026-11-21T22:59:59Z"}
                """.formatted(firstId + i, i)).collect(Collectors.joining(","));
        return "{\"totalResults\": 600, \"results\": [" + results + "]}";
    }

    @Test
    void parseKeysReadsLowercaseApiKeyNotGoogleKey() throws IOException {
        Optional<MarktguruSource.Keys> keys = source(new FakeHttpTransport()).parseKeys(fixture("home.html"));

        assertThat(keys).isPresent();
        assertThat(keys.get().apiKey()).isEqualTo("TEST_API_KEY");
        assertThat(keys.get().clientKey()).isEqualTo("TEST_CLIENT_KEY");
    }

    @Test
    void chainOfMapsRetailers() {
        assertThat(MarktguruSource.chainOf("rewe")).contains(Chain.REWE);
        assertThat(MarktguruSource.chainOf("rewe-center")).contains(Chain.REWE);
        assertThat(MarktguruSource.chainOf("aldi-sued")).contains(Chain.ALDI_SUED);
        assertThat(MarktguruSource.chainOf("netto-marken-discount")).contains(Chain.NETTO_MD);
        assertThat(MarktguruSource.chainOf("edeka")).contains(Chain.EDEKA);
        assertThat(MarktguruSource.chainOf("e-center")).contains(Chain.EDEKA);
        assertThat(MarktguruSource.chainOf("trinkgut")).contains(Chain.TRINKGUT);
        assertThat(MarktguruSource.chainOf("alldrink")).contains(Chain.ALLDRINK);
        // nur veraltete Angebote bei marktguru, logo kommt direkt von logo-getraenke.de
        assertThat(MarktguruSource.chainOf("logo-getraenkemarkt")).isEmpty();
        assertThat(MarktguruSource.chainOf("nahkauf")).isEmpty();
    }

    @Test
    void parsesRetailerOffers() throws IOException {
        List<RawOffer> offers = source(new FakeHttpTransport())
                .parseRetailerOffers(fixture("retailers/rewe.json"), Chain.REWE);

        RawOffer cola = offers.stream()
                .filter(o -> o.price().compareTo(new BigDecimal("7.99")) == 0).findFirst().orElseThrow();
        assertThat(cola.source()).isEqualTo(SourceKey.MARKTGURU);
        assertThat(cola.chain()).isEqualTo(Chain.REWE);
        assertThat(cola.sourceOfferId()).isEqualTo("25758152");
        assertThat(cola.basePrice()).isEqualTo(new BasePrice(new BigDecimal("1.330"), BaseUnit.LITER));
        assertThat(cola.validFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(cola.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(cola.marketExternalId()).isNull();
        assertThat(cola.oldPrice()).isNull();
        assertThat(cola.brand()).isEqualTo("Coca-Cola");
        assertThat(cola.imageUrl())
                .isEqualTo("https://cdn.marktguru.de/api/v1/offers/25758152/images/default/0/small.webp");
    }

    @Test
    void fetchPagesUntilEmptyAndRefreshesKeysOn401() throws IOException, SourceFetchException {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.marktguru.de/", 200, fixture("home.html"))
                .respond(BASE + "offers/publishers", 401, "{}")
                .respond(BASE + "offers/publishers", 200, PUBLISHERS)
                .respond(BASE + "publishers/retailer/rewe/offers?as=web&zipCode=61118&limit=512&offset=0", 200,
                        offersJson(512, 1000))
                .respond(BASE + "publishers/retailer/rewe/offers?as=web&zipCode=61118&limit=512&offset=512", 200,
                        offersJson(3, 5000));

        List<RawOffer> offers = source(transport).fetch(context(Chain.REWE));

        assertThat(offers).hasSize(515);
        long homeRequests = transport.requestedUrls().stream()
                .filter(u -> u.startsWith("https://www.marktguru.de/")).count();
        assertThat(homeRequests).isEqualTo(2);
        assertThat(transport.requests().get(1).headers().firstValue("x-apikey")).contains("TEST_API_KEY");
    }

    @Test
    void fetchFailsOnUnparseableRetailerResponse() throws IOException {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.marktguru.de/", 200, fixture("home.html"))
                .respond(BASE + "offers/publishers", 200, PUBLISHERS)
                .respond(BASE + "publishers/retailer/penny/offers", 200, "<html>Block</html>");

        assertThatThrownBy(() -> source(transport).fetch(context(Chain.PENNY)))
                .isInstanceOf(SourceFetchException.class)
                .hasMessage("Antwort nicht lesbar (penny)");
    }

    @Test
    void fetchFailsOnUnparseablePublisherList() throws IOException {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.marktguru.de/", 200, fixture("home.html"))
                .respond(BASE + "offers/publishers", 200, "<html>Block</html>");

        assertThatThrownBy(() -> source(transport).fetch(context(Chain.PENNY)))
                .isInstanceOf(SourceFetchException.class)
                .hasMessageContaining("Antwort nicht lesbar");
    }

    @Test
    void fetchSkipsChainsNotEnabled() throws IOException, SourceFetchException {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.marktguru.de/", 200, fixture("home.html"))
                .respond(BASE + "offers/publishers", 200, PUBLISHERS)
                .respond(BASE + "publishers/retailer/penny/offers", 200, offersJson(2, 1));

        List<RawOffer> offers = source(transport).fetch(context(Chain.PENNY));

        assertThat(offers).hasSize(2).allMatch(o -> o.chain() == Chain.PENNY);
        assertThat(transport.requestedUrls()).noneMatch(u -> u.contains("/retailer/rewe/"));
        assertThat(transport.requestedUrls()).noneMatch(u -> u.contains("nahkauf"));
    }
}
