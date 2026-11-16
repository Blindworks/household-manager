package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BaseUnit;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormaSourceTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);

    private final GroceryProperties properties = new GroceryProperties();

    private static String fixture() throws Exception {
        return Files.readString(Path.of("src/test/resources/grocery/norma/day_ab-freitag.html"),
                StandardCharsets.UTF_8);
    }

    private NormaSource source(FakeHttpTransport transport) {
        return new NormaSource(new GroceryHttp(transport), properties);
    }

    private static FetchContext context(Set<Chain> chains) {
        return new FetchContext("60386", 50.13, 8.75, List.of(), chains);
    }

    private static final String OVERVIEW = "<nav>"
            + "<a href=\"/de/angebote/ab-montag,-16.11.26/\">Montag</a>"
            + "<a href=\"/de/angebote/ab-freitag,-20.11.26/\">Freitag</a>"
            + "<a href=\"/de/angebote/ab-freitag,-20.11.26/wochenend-spezial-t-394165/\">Thema</a>"
            + "<a href=\"/de/angebote/ab-montag,-16.11.26/\">Montag nochmal</a>"
            + "<a href=\"/de/filialen/\">Filialen</a></nav>";

    private static RawOffer byId(List<RawOffer> offers, String id) {
        return offers.stream().filter(o -> o.sourceOfferId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void parsesDayPageArticles() throws Exception {
        List<RawOffer> offers = source(new FakeHttpTransport()).parseDayPage(fixture(), FRIDAY);

        assertThat(offers).hasSize(19);
        RawOffer cola = byId(offers, "394851");
        assertThat(cola.chain()).isEqualTo(Chain.NORMA);
        assertThat(cola.marketExternalId()).isNull();
        assertThat(cola.brand()).isEqualTo("Coca-Cola");
        assertThat(cola.price()).isEqualByComparingTo("7.99");
        assertThat(cola.oldPrice()).isNull();
        assertThat(cola.loyaltyPrice()).isNull();
        assertThat(cola.basePrice().unit()).isEqualTo(BaseUnit.LITER);
        assertThat(cola.basePrice().amount()).isEqualByComparingTo("1.350");
        assertThat(cola.validFrom()).isEqualTo(FRIDAY);
        assertThat(cola.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(cola.imageUrl()).startsWith("https://www.norma-online.de/ext/img/");
    }

    @Test
    void appCouponPriceIsLoyaltyPrice() throws Exception {
        List<RawOffer> offers = source(new FakeHttpTransport()).parseDayPage(fixture(), FRIDAY);

        RawOffer lindt = byId(offers, "394933");
        assertThat(lindt.price()).isEqualByComparingTo("3.49");
        assertThat(lindt.loyaltyPrice()).isEqualByComparingTo("2.99");
        assertThat(lindt.oldPrice()).isNull();
        assertThat(lindt.basePrice().unit()).isEqualTo(BaseUnit.KILOGRAM);
        assertThat(lindt.basePrice().amount()).isEqualByComparingTo("34.90");
        assertThat(lindt.basePriceText()).isEqualTo("1 kg = 34,90");
    }

    @Test
    void wasPriceBecomesOldPriceButUvpDoesNot() {
        String html = "<article class=\"produktBoxContainer\" id=\"of_1\"><h3 class=\"produktBox-txt-headline\">A</h3>"
                + "<li class=\"produktBox-cont-wrapper-uvp\">statt 3,65</li>"
                + "<li class=\"produktBox-cont-wrapper-price\"><span aria-label=\"2,99 Euro\">2,99</span></li></article>"
                + "<article class=\"produktBoxContainer\" id=\"of_2\"><h3 class=\"produktBox-txt-headline\">B</h3>"
                + "<li class=\"produktBox-cont-wrapper-uvp\">UVP = 3,65</li>"
                + "<li class=\"produktBox-cont-wrapper-price\"><span aria-label=\"2,99 Euro\">2,99</span></li></article>";

        List<RawOffer> offers = assertThatNoException(html);

        assertThat(byId(offers, "1").oldPrice()).isEqualByComparingTo("3.65");
        assertThat(byId(offers, "2").oldPrice()).isNull();
    }

    private List<RawOffer> assertThatNoException(String html) {
        try {
            return source(new FakeHttpTransport()).parseDayPage(html, FRIDAY);
        } catch (SourceFetchException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readsDayPageLinksFromOverview() {
        List<String> links = source(new FakeHttpTransport()).dayLinks(OVERVIEW);

        assertThat(links).containsExactly("/de/angebote/ab-montag,-16.11.26/", "/de/angebote/ab-freitag,-20.11.26/");
    }

    @Test
    void fetchRequestsEveryDayPageAndSurvivesPartialFailure() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond("https://www.norma-online.de/de/angebote/", 200, OVERVIEW);
        transport.respond("https://www.norma-online.de/de/angebote/ab-montag,-16.11.26/", 200, "<html>kaputt</html>");
        transport.respond("https://www.norma-online.de/de/angebote/ab-freitag,-20.11.26/", 200, fixture());

        List<RawOffer> offers = source(transport).fetch(context(Set.of(Chain.NORMA)));

        assertThat(offers).hasSize(19).allMatch(o -> o.validFrom().equals(FRIDAY));
    }

    @Test
    void allDayPagesFailingFails() {
        FakeHttpTransport transport = new FakeHttpTransport();
        transport.respond("https://www.norma-online.de/de/angebote/", 200, OVERVIEW);
        transport.respond("https://www.norma-online.de/de/angebote/ab-", 200, "<html>kaputt</html>");

        assertThatThrownBy(() -> source(transport).fetch(context(Set.of(Chain.NORMA))))
                .isInstanceOf(SourceFetchException.class);
    }

    @Test
    void doesNothingWithoutNormaEnabled() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();

        assertThat(source(transport).fetch(context(Set.of(Chain.REWE)))).isEmpty();
        assertThat(transport.requests()).isEmpty();
    }
}
