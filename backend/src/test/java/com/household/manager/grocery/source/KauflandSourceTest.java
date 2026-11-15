package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BaseUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KauflandSourceTest {

    private final GroceryProperties properties = new GroceryProperties();

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/grocery/kaufland/" + name), StandardCharsets.UTF_8);
    }

    private KauflandSource source(FakeHttpTransport transport) {
        return new KauflandSource(new GroceryHttp(transport), properties);
    }

    private static MarketCandidate market(String id) {
        return new MarketCandidate(Chain.KAUFLAND, id, "Kaufland Frankfurt", "Wächtersbacher Str. 75, 60386 Frankfurt",
                50.13, 8.75, null);
    }

    private static FetchContext context(MarketCandidate... markets) {
        return new FetchContext("60386", 50.13, 8.75, List.of(markets), Set.of(Chain.KAUFLAND));
    }

    private static String page(String json) {
        return "<script>window.SSR = window.SSR || {}; window.SSR['abc'] = " + json + ";</script>";
    }

    private static String offer(String klNr, String title, String from) {
        return "{\"title\":\"" + title + "\",\"price\":1.99,\"klNr\":\"" + klNr + "\",\"salesFrom\":\"" + from
                + "\",\"salesTo\":\"" + from + "\"}";
    }

    private static String template(String... categories) {
        return "{\"component\": \"OfferTemplate\", \"props\": {\"offerData\": {\"cycles\": [{\"categories\": ["
                + String.join(",", categories) + "]}]}}}";
    }

    private static String category(String from, String to, String... offers) {
        return "{\"displayName\":\"X\",\"salesFrom\":\"" + from + "\",\"salesTo\":\"" + to + "\",\"offers\":["
                + String.join(",", offers) + "]}";
    }

    @Test
    void parseBygeoReadsStore() throws Exception {
        List<MarketCandidate> markets = source(new FakeHttpTransport()).parseStores(fixture("bygeo.json"), 50.13, 8.75);

        assertThat(markets).hasSize(1);
        MarketCandidate store = markets.get(0);
        assertThat(store.chain()).isEqualTo(Chain.KAUFLAND);
        assertThat(store.externalId()).isEqualTo("DE2553");
        assertThat(store.address()).isEqualTo("Wächtersbacher Straße 75, 60386 Frankfurt am Main");
        assertThat(store.lat()).isEqualTo(50.1319846);
        assertThat(store.region()).isNull();
    }

    @Test
    void searchReturnsFiveNearestOfStoreList() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        StringBuilder stores = new StringBuilder("[");
        for (int i = 0; i < 7; i++) {
            stores.append(i > 0 ? "," : "").append("{\"n\":\"DE").append(i).append("\",\"lat\":\"")
                    .append(50.0 + i * 0.1).append("\",\"lng\":\"8.7\",\"cn\":\"K").append(i)
                    .append("\",\"pc\":\"60386\",\"sn\":\"Str. ").append(i).append("\",\"t\":\"Frankfurt\"}");
        }
        stores.append(",{\"n\":\"X\",\"cn\":\"ohne Koordinaten\"}]");
        transport.respond("https://filiale.kaufland.de/.klstorefinder.json", 200, stores.toString());

        List<MarketCandidate> markets = source(transport).search("60386", 50.31, 8.7);

        assertThat(markets).extracting(MarketCandidate::externalId).containsExactly("DE3", "DE4", "DE2", "DE5", "DE1");
    }

    @Test
    void extractsOfferTemplateJson() throws Exception {
        List<RawOffer> offers = source(new FakeHttpTransport()).parseOffers(fixture("uebersicht_DE2553.html"),
                market("DE2553"));

        assertThat(offers).hasSize(88);
        assertThat(offers).allSatisfy(offer -> {
            assertThat(offer.chain()).isEqualTo(Chain.KAUFLAND);
            assertThat(offer.marketExternalId()).isEqualTo("DE2553");
            assertThat(offer.price()).isPositive();
        });
    }

    @Test
    void parsesCocaColaWithLoyaltyAndDedupesByKlNr() throws Exception {
        List<RawOffer> offers = source(new FakeHttpTransport()).parseOffers(fixture("uebersicht_DE2553.html"),
                market("DE2553"));

        RawOffer cola = offers.stream().filter(o -> o.sourceOfferId().startsWith("02070183")).findFirst().orElseThrow();
        assertThat(cola.brand()).isEqualTo("COCA-COLA, FANTA,");
        assertThat(cola.title()).contains("SPRITE oder MEZZO MIX");
        assertThat(cola.price()).isEqualByComparingTo("10.99");
        assertThat(cola.oldPrice()).isEqualByComparingTo("16.99");
        assertThat(cola.packaging()).isEqualTo("je Ka. 12 x 1-l-PET-Fl.");
        assertThat(cola.basePrice().unit()).isEqualTo(BaseUnit.LITER);
        assertThat(cola.basePrice().amount()).isEqualByComparingTo("0.920");
        assertThat(cola.validFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(cola.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(cola.imageUrl()).startsWith("https://kaufland.media.schwarz/");

        RawOffer spaten = offers.stream().filter(o -> o.sourceOfferId().startsWith("01453462")).findFirst().orElseThrow();
        assertThat(spaten.price()).isEqualByComparingTo("14.99");
        assertThat(spaten.loyaltyPrice()).isEqualByComparingTo("13.99");
    }

    @Test
    void duplicateKlNrFromThemeCategoriesCountsOnce() throws Exception {
        String html = page(template(
                category("2026-11-16", "2026-11-21", offer("111", "A", "2026-11-16")),
                category("2026-11-16", "2026-11-21", offer("111", "A", "2026-11-16"), offer("222", "B", "2026-11-16"))));

        List<RawOffer> offers = source(new FakeHttpTransport()).parseOffers(html, market("DE1"));

        assertThat(offers).extracting(RawOffer::brand, RawOffer::title).containsExactly(
                org.assertj.core.groups.Tuple.tuple(null, "A"), org.assertj.core.groups.Tuple.tuple(null, "B"));
    }

    @Test
    void sendsStoreCookie() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond("https://filiale.kaufland.de/angebote/uebersicht.html", 200, fixture("uebersicht_DE2553.html"));

        List<RawOffer> offers = source(transport).fetch(context(market("DE2553")));

        assertThat(offers).hasSize(88);
        assertThat(transport.requests()).hasSize(1);
        assertThat(transport.requests().get(0).headers().firstValue("Cookie")).contains("x-aem-variant=DE2553");
    }

    @Test
    void nextWeekOffersKeepTheirDates() throws Exception {
        String html = page(template(
                category("2026-11-16", "2026-11-21", offer("111", "A", "2026-11-16")),
                category("2026-11-23", "2026-11-28", offer("111", "A", "2026-11-23"))));

        List<RawOffer> offers = source(new FakeHttpTransport()).parseOffers(html, market("DE1"));

        assertThat(offers).hasSize(2);
        assertThat(offers).extracting(RawOffer::validFrom)
                .containsExactly(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23));
        assertThat(offers).extracting(RawOffer::sourceOfferId).doesNotHaveDuplicates();
    }

    @Test
    void failingStoreDoesNotDropOthers() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond("https://filiale.kaufland.de/angebote/uebersicht.html", 200, "<html>kaputt</html>");
        transport.respond("https://filiale.kaufland.de/angebote/uebersicht.html", 200, fixture("uebersicht_DE2553.html"));

        List<RawOffer> offers = source(transport).fetch(context(market("DE1"), market("DE2553")));

        assertThat(offers).hasSize(88).allMatch(o -> o.marketExternalId().equals("DE2553"));
    }

    @Test
    void unreadableOnlyStoreFails() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond("https://filiale.kaufland.de/angebote/uebersicht.html", 200, "<html>kaputt</html>");

        assertThatThrownBy(() -> source(transport).fetch(context(market("DE1"))))
                .isInstanceOf(SourceFetchException.class);
    }

    @Test
    void enabledOnlyWithChainAndSelectedMarket() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        KauflandSource source = source(transport);

        assertThat(source.fetch(new FetchContext("1", 0, 0, List.of(market("DE1")), Set.of(Chain.REWE)))).isEmpty();
        assertThat(source.fetch(context())).isEmpty();
        assertThat(transport.requests()).isEmpty();
    }
}
