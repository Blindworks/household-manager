package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BaseUnit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReweSourceTest {

    private static final String PAGE_URL = "https://www.rewe.de/angebote/";
    private static final String TILES_URL = "https://www.rewe.de/api/frontend-includes";

    private final GroceryProperties properties = new GroceryProperties();

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/grocery/rewe/" + name), StandardCharsets.UTF_8);
    }

    private ReweSource source(FakeHttpTransport transport) {
        return new ReweSource(new GroceryHttp(transport), properties);
    }

    private static MarketCandidate market(String wwIdent) {
        return new MarketCandidate(Chain.REWE, wwIdent, "REWE Südbahnhof", "Am Südbahnhof 2-10, 61118 Bad Vilbel",
                50.17863, 8.73443, null);
    }

    private static FetchContext context(MarketCandidate... markets) {
        return new FetchContext("61118", 50.17, 8.73, List.of(markets), Set.of(Chain.REWE));
    }

    private static String pageWithNans(int count) {
        String tiles = IntStream.range(0, count).mapToObj(i ->
                "<div data-offer-nan=\"" + (1000 + i) + "\" data-offer-wwident=\"240767\" data-offer-week=\"2026/47\"></div>")
                .collect(Collectors.joining());
        return "<html><body>" + tiles + "</body></html>";
    }

    @Test
    void parseMarketsReadsWwIdent() throws Exception {
        List<MarketCandidate> markets = source(new FakeHttpTransport()).parseMarkets(fixture("marketsearch.json"));

        MarketCandidate suedbahnhof = markets.stream().filter(m -> m.externalId().equals("240767")).findFirst().orElseThrow();
        assertThat(suedbahnhof.chain()).isEqualTo(Chain.REWE);
        assertThat(suedbahnhof.address()).isEqualTo("Am Südbahnhof 2-10, 61118 Bad Vilbel");
        assertThat(suedbahnhof.lat()).isEqualTo(50.17863);
        assertThat(suedbahnhof.lng()).isEqualTo(8.73443);
    }

    @Test
    void parseOfferPageReadsWeekAndNans() throws Exception {
        ReweSource.PageInfo info = source(new FakeHttpTransport()).parseOfferPage(fixture("offers_page.html"));

        assertThat(info.week()).isEqualTo("2026/47");
        assertThat(info.nans()).hasSizeGreaterThanOrEqualTo(10).doesNotHaveDuplicates();
    }

    @Test
    void parseTilesReadsPriceBasePriceBonus() throws Exception {
        List<RawOffer> offers = source(new FakeHttpTransport()).parseTiles(fixture("tiles_0.json"), "240767");

        RawOffer cola = offers.stream().filter(o -> "Coca-Cola".equals(o.title())).findFirst().orElseThrow();
        assertThat(cola.price()).isEqualByComparingTo("1.19");
        assertThat(cola.loyaltyPrice()).isNull();
        assertThat(cola.basePrice().amount()).isEqualByComparingTo("0.950");
        assertThat(cola.basePrice().unit()).isEqualTo(BaseUnit.LITER);
        assertThat(cola.marketExternalId()).isEqualTo("240767");
        assertThat(cola.chain()).isEqualTo(Chain.REWE);
        assertThat(cola.source()).isEqualTo(SourceKey.REWE);
        assertThat(cola.imageUrl()).startsWith("https://img.rewe-static.de/");
        // 11 Kacheln, zwei davon nur mit Bonus-Guthaben und ohne Preis
        assertThat(offers).hasSize(9);
        assertThat(offers).extracting(RawOffer::sourceOfferId).doesNotContain("240767-8931513", "240767-7571068");
    }

    @Test
    void requestsTilesInBatchesOf80() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond(PAGE_URL, 200, pageWithNans(243))
                .respond(TILES_URL, 200, "[]");

        List<RawOffer> offers = source(transport).fetch(context(market("240767")));

        assertThat(offers).isEmpty();
        long posts = transport.requests().stream().filter(r -> r.method().equals("POST")).count();
        assertThat(posts).isEqualTo(4);
    }

    @Test
    void validityIsMondayToSaturdayOfTheWeek() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond(PAGE_URL, 200, pageWithNans(1))
                .respond(TILES_URL, 200, fixture("tiles_0.json"));

        List<RawOffer> offers = source(transport).fetch(context(market("240767")));

        assertThat(offers).isNotEmpty();
        assertThat(offers).allSatisfy(o -> {
            assertThat(o.validFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
            assertThat(o.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
        });
    }

    @Test
    void stopsOnChallenge() {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond(PAGE_URL, 403, "", Map.of("Cf-Mitigated", List.of("challenge")));

        assertThatThrownBy(() -> source(transport).fetch(context(market("240767"))))
                .isInstanceOf(SourceFetchException.class);
    }

    @Test
    void oneFailingMarketKeepsTheOthersOffers() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport()
                .respond(PAGE_URL + "bad-vilbel/111111/", 500, "")
                .respond(PAGE_URL + "bad-vilbel/240767/", 200, pageWithNans(1))
                .respond(TILES_URL, 200, fixture("tiles_0.json"));

        List<RawOffer> offers = source(transport).fetch(context(market("111111"), market("240767")));

        assertThat(offers).isNotEmpty().allMatch(o -> o.marketExternalId().equals("240767"));
    }

    @Test
    void failsWhenAllMarketsFail() {
        FakeHttpTransport transport = new FakeHttpTransport().respond(PAGE_URL, 500, "");

        assertThatThrownBy(() -> source(transport).fetch(context(market("111111"), market("240767"))))
                .isInstanceOf(SourceFetchException.class);
    }
}
