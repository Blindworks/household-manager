package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KaufdaSourceTest {

    private static final String SHELF_URL = "https://www.kaufda.de/api/shelf?";
    private static final String OFFERS_URL = "https://www.kaufda.de/api/personalisedOffers?";
    private static final String SIMPLE_PRICES = "{\"mainPrice\":1.0,\"secondaryPrice\":0,\"conditions\":[]}";

    private final GroceryProperties properties = new GroceryProperties();
    private final Map<String, String> settings = new HashMap<>();
    private final ApplicationSettingsService settingsService = settingsService();

    private ApplicationSettingsService settingsService() {
        ApplicationSettingsService service = mock(ApplicationSettingsService.class);
        when(service.getString(anyString(), anyString(), anyString()))
                .thenAnswer(call -> settings.getOrDefault(call.<String>getArgument(1), call.getArgument(2)));
        doAnswer(call -> settings.put(call.getArgument(1), call.getArgument(2)))
                .when(service).saveSetting(anyString(), anyString(), anyString());
        return service;
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/grocery/kaufda/" + name), StandardCharsets.UTF_8);
    }

    private KaufdaSource source(FakeHttpTransport transport) {
        return new KaufdaSource(new GroceryHttp(transport), properties,
                new com.household.manager.grocery.GrocerySettingsService(settingsService));
    }

    private static String shelfOf(String... entries) {
        return "{\"page\":{\"totalPages\":1,\"number\":0},\"contents\":[" + String.join(",", entries) + "]}";
    }

    private static String brochure(String type, String contentId, String publisher, String storeName,
                                   String street, String number, String zip) {
        return """
                {"contentType":"%s","content":{"contentId":"%s","id":"1","publisher":{"name":"%s"},
                 "closestStore":{"name":"%s","street":"%s","streetNumber":"%s","zip":"%s","city":"Bad Vilbel",
                 "latitude":50.1,"longitude":8.7},
                 "validFrom":"2026-11-15T23:00:00.000+0000","validUntil":"2026-11-21T22:00:00.000+0000"}}
                """.formatted(type, contentId, publisher, storeName, street, number, zip);
    }

    private static String offersOf(String... offers) {
        return "{\"contents\":[" + String.join(",", offers) + "]}";
    }

    private static String offer(String id, String title, String prices) {
        return """
                {"id":"%s","title":"%s","brand":null,"description":"je 1 l","publisherName":"X",
                 "prices":%s,"validFrom":"2026-11-15T23:00:00.000+0000","validUntil":"2026-11-21T22:00:00.000+0000",
                 "offerImages":{"url":{"normal":"https://img/%s.jpg"}}}
                """.formatted(id, title, prices, id);
    }

    private static MarketCandidate market(Chain chain, String id, String address) {
        return new MarketCandidate(chain, id, "Markt", address, null, null, null);
    }

    @Test
    void parseShelfMapsPublishersToChains() {
        String json = shelfOf(
                brochure("brochure", "c1", "Lidl", "Lidl", "Alte Frankfurter Straße", "15", "61118"),
                brochure("brochure", "c2", "REWE", "REWE X", "Marktstraße", "1", "60388"),
                brochure("brochure", "c3", "Penny", "PENNY", "Friedberger Straße", "77", "61118"),
                brochure("brochure", "c4", "EDEKA Stenger", "EDEKA Stenger", "Die Landwehr", "1", "61138"),
                brochure("brochure", "c5", "E center", "E center", "Hauptstr.", "3", "61118"),
                brochure("brochure", "c6", "Kaufland", "Kaufland", "Ring", "9", "61118"),
                brochure("brochure", "c7", "ALDI S�D", "Aldi", "Ring", "1", "61118"),
                brochure("brochure", "c8", "Netto Marken-Discount", "Netto", "Ring", "2", "61118"),
                brochure("brochure", "c9", "Segmüller", "Segmüller", "Im Rödling", "2", "64331"),
                brochure("brochurePremium", "c10", "expert", "expert", "Wilhelmstraße", "23", "61381"),
                "{\"contentType\":\"blogCarousel\",\"content\":{\"title\":\"Tipps\"}}");

        List<KaufdaSource.Brochure> brochures = source(new FakeHttpTransport()).parseShelf(json);

        assertThat(brochures).extracting(KaufdaSource.Brochure::contentId)
                .containsExactly("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8");
        assertThat(brochures).extracting(KaufdaSource.Brochure::chain).containsExactly(
                Chain.LIDL, Chain.REWE, Chain.PENNY, Chain.EDEKA, Chain.EDEKA, Chain.KAUFLAND,
                Chain.ALDI_SUED, Chain.NETTO_MD);
        KaufdaSource.Brochure rewe = brochures.get(1);
        assertThat(rewe.storeZip()).isEqualTo("60388");
        assertThat(rewe.validFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(rewe.validUntil()).isEqualTo(LocalDate.of(2026, 11, 21));
    }

    @Test
    void parseShelfReadsTheRealFixture() throws IOException {
        List<KaufdaSource.Brochure> brochures = source(new FakeHttpTransport()).parseShelf(fixture("shelf_api.json"));

        assertThat(brochures).extracting(KaufdaSource.Brochure::chain)
                .containsExactlyInAnyOrder(Chain.LIDL, Chain.REWE, Chain.REWE, Chain.PENNY, Chain.EDEKA);
    }

    @Test
    void parsesPennyBrochureOffers() throws IOException {
        KaufdaSource.Brochure brochure = new KaufdaSource.Brochure("545f", "Penny", Chain.PENNY, "PENNY", "61118", null, null,
                LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 21));

        List<RawOffer> offers = source(new FakeHttpTransport()).parseOffers(fixture("brochures/penny.json"), brochure);

        RawOffer cola = offers.stream().filter(o -> o.brand() != null && o.brand().contains("Coca-Cola"))
                .findFirst().orElseThrow();
        assertThat(cola.source()).isEqualTo(SourceKey.KAUFDA);
        assertThat(cola.chain()).isEqualTo(Chain.PENNY);
        assertThat(cola.price()).isEqualByComparingTo("8.49");
        assertThat(cola.loyaltyPrice()).isNull();
        assertThat(cola.oldPrice()).isNull();
        assertThat(cola.basePrice().unit()).isEqualTo(BaseUnit.LITER);
        assertThat(cola.basePrice().amount()).isEqualByComparingTo("1.43");
        assertThat(cola.validFrom()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(cola.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(cola.imageUrl()).startsWith("https://");
        assertThat(cola.sourceOfferId()).isEqualTo("d26bb7bd-4ced-464b-bc17-fda16ef118c3");
    }

    @Test
    void appOnlyConditionBecomesLoyaltyPriceAndKeepsNormalPrice() {
        KaufdaSource.Brochure brochure = new KaufdaSource.Brochure("b", "Penny", Chain.PENNY, "P", "61118", null, null, null, null);
        String json = offersOf(
                offer("a", "Mit Normalpreis", """
                        {"mainPrice":5.49,"secondaryPrice":6.99,"secondaryPriceIsUVP":false,
                         "priceByBaseUnit":"","conditions":[{"other":"Nur mit PENNY App"}]}"""),
                offer("b", "Ohne Normalpreis", """
                        {"mainPrice":1.99,"secondaryPrice":0,"conditions":[{"other":"Nur mit PENNY App"}]}"""),
                offer("c", "Je", """
                        {"mainPrice":2.99,"secondaryPrice":3.99,"secondaryPriceIsUVP":false,"conditions":[{"other":"Je"}]}"""));

        List<RawOffer> offers = source(new FakeHttpTransport()).parseOffers(json, brochure);

        assertThat(offers).extracting(RawOffer::title).containsExactly("Mit Normalpreis", "Je");
        assertThat(offers.get(0).loyaltyPrice()).isEqualByComparingTo("5.49");
        assertThat(offers.get(0).price()).isEqualByComparingTo("6.99");
        assertThat(offers.get(1).loyaltyPrice()).isNull();
        assertThat(offers.get(1).price()).isEqualByComparingTo("2.99");
        assertThat(offers.get(1).oldPrice()).isEqualByComparingTo("3.99");
    }

    @Test
    void marketExternalIdFromClosestStore() throws SourceFetchException {
        String shelf = shelfOf(
                brochure("brochure", "rewe-1", "REWE", "REWE Südbahnhof", "Am Südbahnhof", "2-10", "61118"),
                brochure("brochure", "rewe-2", "REWE", "REWE Anderswo", "Marktstr.", "1", "60388"),
                brochure("brochure", "lidl-1", "Lidl", "Lidl", "Alte Frankfurter Straße", "15", "61118"));
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond(SHELF_URL, 200, shelf)
                .respond(OFFERS_URL + "brochureId=rewe-1", 200, offersOf(offer("o1", "Bier", SIMPLE_PRICES)))
                .respond(OFFERS_URL + "brochureId=lidl-1", 200, offersOf(offer("o2", "Milch", SIMPLE_PRICES)));
        FetchContext ctx = new FetchContext("61118", 50.1, 8.7,
                List.of(market(Chain.REWE, "240767", "Am Südbahnhof 2-10, 61118 Bad Vilbel")),
                Set.of(Chain.REWE, Chain.LIDL));

        List<RawOffer> offers = source(transport).fetch(ctx);

        assertThat(offers).extracting(RawOffer::title).containsExactlyInAnyOrder("Bier", "Milch");
        assertThat(offers).filteredOn(o -> o.chain() == Chain.REWE)
                .extracting(RawOffer::marketExternalId).containsExactly("240767");
        assertThat(offers).filteredOn(o -> o.chain() == Chain.LIDL)
                .extracting(RawOffer::marketExternalId).containsOnlyNulls();
        assertThat(transport.requestedUrls()).noneMatch(url -> url.contains("rewe-2"));
        assertThat(transport.requestedUrls()).anyMatch(url -> url.contains("bonialAccountId=")
                && url.contains("userPlatformCategory=desktop") && url.contains("size=1000"));
    }

    @Test
    void matchesStreetAbbreviationsAndSkipsDisabledChains() throws SourceFetchException {
        String shelf = shelfOf(
                brochure("brochure", "rewe-1", "REWE", "REWE", "Friedberger Straße", "5", "61118"),
                brochure("brochure", "penny-1", "Penny", "Penny", "Friedberger Straße", "77", "61118"));
        FakeHttpTransport transport = new FakeHttpTransport().respond(SHELF_URL, 200, shelf)
                .respond(OFFERS_URL, 200, offersOf(offer("o1", "Bier", SIMPLE_PRICES)));
        FetchContext ctx = new FetchContext("61118", 50.1, 8.7,
                List.of(market(Chain.REWE, "1", "Friedberger Str. 5, 61118 Bad Vilbel"),
                        market(Chain.PENNY, "2", "Friedberger Str. 77, 61118 Bad Vilbel")),
                Set.of(Chain.REWE));

        List<RawOffer> offers = source(transport).fetch(ctx);

        assertThat(offers).extracting(RawOffer::marketExternalId).containsExactly("1");
        assertThat(transport.requestedUrls()).noneMatch(url -> url.contains("penny-1"));
    }

    @Test
    void accountIdIsGeneratedOnceAndReused() throws SourceFetchException {
        FakeHttpTransport transport = new FakeHttpTransport().respond(SHELF_URL, 200, shelfOf());
        KaufdaSource source = source(transport);
        FetchContext ctx = new FetchContext("61118", 50.1, 8.7, List.of(), Set.of(Chain.LIDL));

        source.fetch(ctx);
        String first = settings.get("kaufda_account_id");
        source.fetch(ctx);

        assertThat(first).isNotBlank();
        assertThat(settings.get("kaufda_account_id")).isEqualTo(first);
    }

    @Test
    void aldiSuedBrochureIdFromLandingPage() throws IOException {
        KaufdaSource source = source(new FakeHttpTransport());

        Optional<String> id = source.aldiSuedBrochureId(fixture("aldi-sued-bad-vilbel.html"));

        assertThat(id).contains("71ee6eda-0684-4416-9d94-8210741492b5");
        assertThat(source.aldiSuedBrochureId("<html></html>")).isEmpty();
    }

    @Test
    void aldiSuedIsFetchedViaLandingPage() throws IOException, SourceFetchException {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond(SHELF_URL, 200, shelfOf())
                .respond("https://www.kaufda.de/Geschaefte/Aldi-Sued", 200, fixture("aldi-sued-bad-vilbel.html"))
                .respond(OFFERS_URL + "brochureId=71ee6eda", 200, offersOf(offer("o1", "Nudeln", SIMPLE_PRICES)));

        List<RawOffer> offers = source(transport).fetch(
                new FetchContext("61118", 50.1, 8.7, List.of(), Set.of(Chain.ALDI_SUED)));

        assertThat(offers).extracting(RawOffer::chain).containsExactly(Chain.ALDI_SUED);
        assertThat(offers.get(0).marketExternalId()).isNull();
    }

    @Test
    void unreadableShelfThrows() {
        FakeHttpTransport transport = new FakeHttpTransport().respond(SHELF_URL, 200, "<html>nope</html>");
        KaufdaSource source = source(transport);
        FetchContext ctx = new FetchContext("61118", 50.1, 8.7, List.of(), Set.of(Chain.LIDL));

        assertThatThrownBy(() -> source.fetch(ctx))
                .isInstanceOf(SourceFetchException.class).hasMessageContaining("nicht lesbar");
    }

    @Test
    void edekaMarketSearchFromShelf() throws SourceFetchException {
        String shelf = shelfOf(
                brochure("brochure", "e1", "EDEKA Stenger", "EDEKA Stenger", "Die Landwehr", "1", "61138"),
                brochure("brochure", "e2", "EDEKA Stenger", "EDEKA Stenger", "Die Landwehr", "1", "61138"),
                brochure("brochure", "l1", "Lidl", "Lidl", "Ring", "1", "61118"));
        FakeHttpTransport transport = new FakeHttpTransport().respond(SHELF_URL, 200, shelf);
        KaufdaSource source = source(transport);

        List<MarketCandidate> markets = source.search("61118", 50.1, 8.7);

        assertThat(markets).hasSize(1);
        MarketCandidate market = markets.get(0);
        assertThat(market.chain()).isEqualTo(Chain.EDEKA);
        assertThat(market.externalId()).isEqualTo("kaufda:61138:dielandwehr");
        assertThat(market.name()).isEqualTo("EDEKA Stenger");
        assertThat(market.address()).isEqualTo("Die Landwehr 1, 61138 Bad Vilbel");
        assertThat(source.chain()).isEqualTo(Chain.EDEKA);
    }

    @Test
    void selectedEdekaMarketMatchesItsOwnBrochure() throws SourceFetchException {
        String shelf = shelfOf(
                brochure("brochure", "e1", "EDEKA Stenger", "EDEKA Stenger", "Die Landwehr", "1", "61138"));
        FakeHttpTransport transport = new FakeHttpTransport().respond(SHELF_URL, 200, shelf)
                .respond(OFFERS_URL, 200, offersOf(offer("o1", "Bier", SIMPLE_PRICES)));
        KaufdaSource source = source(transport);
        MarketCandidate selected = source.search("61118", 50.1, 8.7).get(0);

        List<RawOffer> offers = source.fetch(
                new FetchContext("61118", 50.1, 8.7, List.of(selected), Set.of(Chain.EDEKA)));

        assertThat(offers).extracting(RawOffer::marketExternalId).containsExactly(selected.externalId());
    }

    private static String priced(String id, String prices) {
        return offer(id, "T" + id, prices);
    }

    @Test
    void uvpOrOddSecondaryPriceIsNotANormalPrice() {
        KaufdaSource.Brochure brochure = new KaufdaSource.Brochure("b", "Penny", Chain.PENNY, "P", "61118", null, null, null, null);
        String app = "[{\"other\":\"Nur mit PENNY App\"}]";
        String json = offersOf(
                priced("1", "{\"mainPrice\":5.0,\"secondaryPrice\":9.0,\"secondaryPriceIsUVP\":true,\"conditions\":" + app + "}"),
                priced("2", "{\"mainPrice\":5.0,\"secondaryPrice\":9.0,\"conditions\":" + app + "}"),
                priced("3", "{\"mainPrice\":5.0,\"secondaryPrice\":9.0,\"secondaryPriceIsUVP\":true,\"conditions\":[]}"),
                priced("4", "{\"mainPrice\":5.0,\"secondaryPrice\":4.0,\"secondaryPriceIsUVP\":false,\"conditions\":" + app + "}"));

        List<RawOffer> offers = source(new FakeHttpTransport()).parseOffers(json, brochure);

        assertThat(offers).extracting(RawOffer::sourceOfferId).containsExactly("3");
        assertThat(offers.get(0).oldPrice()).isNull();
        assertThat(offers.get(0).price()).isEqualByComparingTo("5.0");
    }

    @Test
    void plusAsPlainWordIsNoLoyaltyCondition() {
        KaufdaSource.Brochure brochure = new KaufdaSource.Brochure("b", "Penny", Chain.PENNY, "P", "61118", null, null, null, null);
        String json = offersOf(priced("1", "{\"mainPrice\":5.0,\"secondaryPrice\":0,"
                + "\"conditions\":[{\"other\":\"zzgl. Pfand plus Versand\"}]}"));

        List<RawOffer> offers = source(new FakeHttpTransport()).parseOffers(json, brochure);

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).loyaltyPrice()).isNull();
    }

    @Test
    void allBrochuresOfAChainAreFetched() throws SourceFetchException {
        String shelf = shelfOf(
                brochure("brochure", "lidl-1", "Lidl", "Lidl", "Ring", "1", "61118"),
                brochure("brochure", "lidl-2", "Lidl", "Lidl", "Ring", "1", "61118"),
                brochure("brochure", "lidl-1", "Lidl", "Lidl", "Ring", "1", "61118"));
        FakeHttpTransport transport = new FakeHttpTransport().respond(SHELF_URL, 200, shelf)
                .respond(OFFERS_URL + "brochureId=lidl-1", 200, offersOf(offer("o1", "Alt", SIMPLE_PRICES)))
                .respond(OFFERS_URL + "brochureId=lidl-2", 200, offersOf(offer("o2", "Neu", SIMPLE_PRICES)));

        List<RawOffer> offers = source(transport).fetch(
                new FetchContext("61118", 50.1, 8.7, List.of(), Set.of(Chain.LIDL)));

        assertThat(offers).extracting(RawOffer::title).containsExactlyInAnyOrder("Alt", "Neu");
        assertThat(transport.requestedUrls().stream().filter(u -> u.startsWith(OFFERS_URL))).hasSize(2);
    }

    @Test
    void aldiLandingPageFailureKeepsOtherChains() throws SourceFetchException {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond(SHELF_URL, 200, shelfOf(brochure("brochure", "lidl-1", "Lidl", "Lidl", "Ring", "1", "61118")))
                .respond("https://www.kaufda.de/Geschaefte/Aldi-Sued", 404, "nope")
                .respond(OFFERS_URL, 200, offersOf(offer("o1", "Milch", SIMPLE_PRICES)));

        List<RawOffer> offers = source(transport).fetch(
                new FetchContext("61118", 50.1, 8.7, List.of(), Set.of(Chain.LIDL, Chain.ALDI_SUED)));

        assertThat(offers).extracting(RawOffer::title).containsExactly("Milch");
    }

    @Test
    void oneFailingBrochureDoesNotDiscardTheOthers() throws SourceFetchException {
        String shelf = shelfOf(
                brochure("brochure", "lidl-1", "Lidl", "Lidl", "Ring", "1", "61118"),
                brochure("brochure", "lidl-2", "Lidl", "Lidl", "Ring", "1", "61118"));
        FakeHttpTransport transport = new FakeHttpTransport().respond(SHELF_URL, 200, shelf)
                .respond(OFFERS_URL + "brochureId=lidl-1", 500, "boom")
                .respond(OFFERS_URL + "brochureId=lidl-2", 200, offersOf(offer("o2", "Neu", SIMPLE_PRICES)));

        List<RawOffer> offers = source(transport).fetch(
                new FetchContext("61118", 50.1, 8.7, List.of(), Set.of(Chain.LIDL)));

        assertThat(offers).extracting(RawOffer::title).containsExactly("Neu");
    }

    @Test
    void allBrochuresFailingThrows() {
        String shelf = shelfOf(
                brochure("brochure", "lidl-1", "Lidl", "Lidl", "Ring", "1", "61118"),
                brochure("brochure", "lidl-2", "Lidl", "Lidl", "Ring", "1", "61118"));
        FakeHttpTransport transport = new FakeHttpTransport().respond(SHELF_URL, 200, shelf)
                .respond(OFFERS_URL + "brochureId=lidl-1", 500, "boom")
                .respond(OFFERS_URL + "brochureId=lidl-2", 200, "<html>kaputt</html>");
        KaufdaSource source = source(transport);
        FetchContext ctx = new FetchContext("61118", 50.1, 8.7, List.of(), Set.of(Chain.LIDL));

        assertThatThrownBy(() -> source.fetch(ctx)).isInstanceOf(SourceFetchException.class);
    }
}
