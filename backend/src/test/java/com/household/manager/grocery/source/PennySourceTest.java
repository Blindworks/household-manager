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

class PennySourceTest {

    private static final String REGION = "15A-05-67";

    private final GroceryProperties properties = new GroceryProperties();

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/grocery/penny/" + name), StandardCharsets.UTF_8);
    }

    private PennySource source(FakeHttpTransport transport) {
        return new PennySource(new GroceryHttp(transport), properties);
    }

    private static MarketCandidate market(String wwIdent, String region) {
        return new MarketCandidate(Chain.PENNY, wwIdent, "Penny Bad Vilbel", "Friedberger Str. 77, 61118 Bad Vilbel",
                50.1892, 8.74159, region);
    }

    private static FetchContext context(MarketCandidate... markets) {
        return new FetchContext("61118", 50.17, 8.73, List.of(markets), Set.of(Chain.PENNY));
    }

    private static String page(String... categories) {
        StringBuilder html = new StringBuilder(
                "<span class=\"js-weeks\" data-current-week=\"2026-47\" data-next-week=\"2026-48\"></span>");
        for (String category : categories) {
            html.append("<section id=\"ab-montag--").append(category).append("\" data-category-name=\"")
                    .append(category).append("\"></section>");
        }
        return html.toString();
    }

    @Test
    void nearestMarketIsBadVilbel() throws Exception {
        List<MarketCandidate> markets = source(new FakeHttpTransport())
                .parseMarkets(fixture("markets_3.json"), 50.17, 8.73);

        assertThat(markets).hasSize(3);
        MarketCandidate nearest = markets.get(0);
        assertThat(nearest.externalId()).isEqualTo("230372");
        assertThat(nearest.chain()).isEqualTo(Chain.PENNY);
        assertThat(nearest.region()).isEqualTo(REGION);
        assertThat(nearest.address()).isEqualTo("Friedberger Str. 77, 61118 Bad Vilbel");
        assertThat(nearest.lat()).isEqualTo(50.1892);
        assertThat(markets.get(2).externalId()).isEqualTo("530027");
    }

    @Test
    void parsesWeekAndCategories() throws Exception {
        PennySource.WeekInfo info = source(new FakeHttpTransport()).parseOffersPage(fixture("angebote.html"));

        assertThat(info.currentWeek()).isEqualTo("2026-47");
        assertThat(info.categories()).hasSize(25).contains("getraenke1", "top-angebote").doesNotHaveDuplicates();
    }

    @Test
    void parsesCocaColaWithBenefitPrice() throws Exception {
        PennySource source = source(new FakeHttpTransport());
        MarketCandidate market = market("230372", REGION);

        RawOffer cola = source.parseCategory(fixture("cat_getraenke1.json"), market, "2026-47").stream()
                .filter(o -> o.title().startsWith("COCA-COLA")).findFirst().orElseThrow();
        assertThat(cola.price()).isEqualByComparingTo("8.49");
        assertThat(cola.packaging()).isEqualTo("je 18 x 0,33 l");
        assertThat(cola.basePrice().amount()).isEqualByComparingTo("1.430");
        assertThat(cola.basePrice().unit()).isEqualTo(BaseUnit.LITER);
        assertThat(cola.sourceOfferId()).isEqualTo("3193368f-f28d-4997-9ee5-30e41d5c6c60");
        assertThat(cola.marketExternalId()).isEqualTo("230372");
        assertThat(cola.source()).isEqualTo(SourceKey.PENNY);
        assertThat(cola.validFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(cola.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));

        RawOffer monster = source.parseCategory(fixture("cat_getraenke.json"), market, "2026-47").stream()
                .filter(o -> o.title().startsWith("MONSTER")).findFirst().orElseThrow();
        assertThat(monster.price()).isEqualByComparingTo("0.99");
        assertThat(monster.loyaltyPrice()).isEqualByComparingTo("0.89");
        assertThat(monster.imageUrl()).contains("imwidth=400");
    }

    @Test
    void promoTileKeepsNormalPriceAndAppPriceSeparate() throws Exception {
        RawOffer desperados = source(new FakeHttpTransport())
                .parseCategory(fixture("cat_getraenke.json"), market("230372", REGION), "2026-47").stream()
                .filter(o -> o.title().startsWith("DESPERADOS")).findFirst().orElseThrow();

        assertThat(desperados.price()).isEqualByComparingTo("13.99");
        assertThat(desperados.loyaltyPrice()).isEqualByComparingTo("12.99");
        assertThat(desperados.oldPrice()).isNull();
        assertThat(desperados.basePrice().amount()).isEqualByComparingTo("3.530");
    }

    @Test
    void promoTileWithoutAppMarkerIsAnOrdinaryReduction() {
        String json = """
                {"offerTiles":[
                 {"primaryType":"promo","title":"Kaffee*","quantity":"je 500 g","price":"4.99","crossOutPrice":"6.49","basePrice":"(1 kg = 9.98)","uuid":"k"}
                ]}""";

        RawOffer coffee = source(new FakeHttpTransport()).parseCategory(json, market("1", REGION), "2026-47").get(0);

        assertThat(coffee.price()).isEqualByComparingTo("4.99");
        assertThat(coffee.oldPrice()).isEqualByComparingTo("6.49");
        assertThat(coffee.loyaltyPrice()).isNull();
    }

    @Test
    void uvpIsNotOldPriceButCrossedOutPriceIs() {
        String json = """
                {"offerTiles":[
                 {"primaryType":"offer","title":"A*","quantity":"je 1 l","price":"0.79","originalPrice":"1.14","originalPriceType":"uvp","uuid":"a"},
                 {"primaryType":"offer","title":"B*","quantity":"je 1 l","price":"1.99","originalPrice":"2.49","originalPriceType":"normal","uuid":"b"},
                 {"primaryType":"offer","title":"C*","quantity":"je 1 l","price":"1.00","crossOutPrice":"1.50","lowestPrice30Days":"1.20","uuid":"c"}
                ]}""";

        List<RawOffer> offers = source(new FakeHttpTransport()).parseCategory(json, market("1", REGION), "2026-47");

        assertThat(offers).extracting(RawOffer::title).containsExactly("A", "B", "C");
        assertThat(offers.get(0).oldPrice()).isNull();
        assertThat(offers.get(1).oldPrice()).isEqualByComparingTo("2.49");
        assertThat(offers.get(2).oldPrice()).isEqualByComparingTo("1.50");
        assertThat(offers.get(2).lowestPrice30Days()).isEqualByComparingTo("1.20");
    }

    @Test
    void dropsContentTeasersAndTilesWithoutPrice() {
        String json = """
                {"offerTiles":[
                 {"primaryType":"contentTeaser","title":"Werbung","uuid":"t"},
                 {"primaryType":"offer","title":"Ohne Preis","price":null,"uuid":"n"},
                 {"primaryType":"offer","title":"Mit Preis","price":"1.11","uuid":"p"}
                ]}""";

        List<RawOffer> offers = source(new FakeHttpTransport()).parseCategory(json, market("1", REGION), "2026-47");

        assertThat(offers).extracting(RawOffer::title).containsExactly("Mit Preis");
    }

    @Test
    void appendsRegionParameter() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.penny.de/angebote", 200, page("getraenke1"))
                .respond("https://www.penny.de/.rest/offers/by-category/", 200, fixture("cat_getraenke1.json"));

        List<RawOffer> offers = source(transport).fetch(context(market("230372", REGION)));

        assertThat(transport.requestedUrls()).contains(
                "https://www.penny.de/.rest/offers/by-category/2026-47/getraenke1?region=" + REGION);
        assertThat(offers).hasSize(5);
    }

    @Test
    void fetchesEachRegionOnceAndDuplicatesOffersPerMarket() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.penny.de/angebote", 200, page("getraenke1"))
                .respond("https://www.penny.de/.rest/offers/by-category/", 200, fixture("cat_getraenke1.json"));

        List<RawOffer> offers = source(transport)
                .fetch(context(market("230372", REGION), market("230373", REGION)));

        assertThat(transport.requestedUrls().stream().filter(u -> u.contains("by-category"))).hasSize(1);
        assertThat(offers).hasSize(10);
        assertThat(offers).extracting(RawOffer::marketExternalId).containsOnly("230372", "230373");
    }

    @Test
    void thursdaySectionStartsOnThursday() throws Exception {
        String html = "<span class=\"js-weeks\" data-current-week=\"2026-47\" data-next-week=\"2026-48\"></span>"
                + "<section id=\"ab-donnerstag--getraenke1\" data-category-name=\"getraenke1\"></section>";
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.penny.de/angebote", 200, html)
                .respond("https://www.penny.de/.rest/offers/by-category/", 200, fixture("cat_getraenke1.json"));

        List<RawOffer> offers = source(transport).fetch(context(market("230372", REGION)));

        assertThat(offers).allSatisfy(o -> {
            assertThat(o.validFrom()).isEqualTo(LocalDate.of(2026, 11, 19));
            assertThat(o.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
        });
    }

    @Test
    void failedCategoryDoesNotLoseTheOthers() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.penny.de/angebote", 200, page("getraenke", "getraenke1"))
                .respond("https://www.penny.de/.rest/offers/by-category/2026-47/getraenke?", 503, "")
                .respond("https://www.penny.de/.rest/offers/by-category/2026-47/getraenke1?", 200,
                        fixture("cat_getraenke1.json"));

        List<RawOffer> offers = source(transport).fetch(context(market("230372", REGION)));

        assertThat(offers).hasSize(5);
    }

    @Test
    void throwsWhenNoCategoryWorks() {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.penny.de/angebote", 200, page("getraenke1"))
                .respond("https://www.penny.de/.rest/offers/by-category/", 500, "");

        assertThatThrownBy(() -> source(transport).fetch(context(market("230372", REGION))))
                .isInstanceOf(SourceFetchException.class);
    }

    @Test
    void throwsWhenWeekPageHasNoWeek() {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.penny.de/angebote", 200, "<html>nichts</html>");

        assertThatThrownBy(() -> source(transport).fetch(context(market("230372", REGION))))
                .isInstanceOf(SourceFetchException.class);
    }

    @Test
    void unreadableCategoryResponseIsAFailure() {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.penny.de/angebote", 200, page("getraenke1"))
                .respond("https://www.penny.de/.rest/offers/by-category/", 200, "<html>kaputt</html>");

        assertThatThrownBy(() -> source(transport).fetch(context(market("230372", REGION))))
                .isInstanceOf(SourceFetchException.class);
    }

    @Test
    void ignoresOtherChainsAndDisabledChain() throws Exception {
        MarketCandidate rewe = new MarketCandidate(Chain.REWE, "1", "REWE", "x", 1.0, 1.0, null);
        FakeHttpTransport transport = new FakeHttpTransport();

        assertThat(source(transport).fetch(context(rewe))).isEmpty();
        assertThat(transport.requests()).isEmpty();
        assertThat(source(transport).fetch(new FetchContext("1", 1, 1, List.of(market("1", REGION)), Set.of())))
                .isEmpty();
    }

    @Test
    void searchReturnsNearestFiveFromFullList() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond("https://www.penny.de/.rest/market", 200, fixture("markets_3.json"));

        List<MarketCandidate> markets = source(transport).search("61118", 50.17, 8.73);

        assertThat(markets).extracting(MarketCandidate::externalId).startsWith("230372");
    }

    @Test
    void parsesDecimalCommaPrices() {
        String json = "{\"offerTiles\":[{\"primaryType\":\"offer\",\"title\":\"X\",\"price\":\"1,49\","
                + "\"lowestPrice30Days\":\"1,29\",\"uuid\":\"x\"}]}";

        RawOffer offer = source(new FakeHttpTransport()).parseCategory(json, market("1", REGION), "2026-47").get(0);

        assertThat(offer.price()).isEqualByComparingTo(new BigDecimal("1.49"));
        assertThat(offer.lowestPrice30Days()).isEqualByComparingTo("1.29");
    }
}
