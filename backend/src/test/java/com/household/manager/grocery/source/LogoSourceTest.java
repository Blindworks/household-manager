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

class LogoSourceTest {

    private final GroceryProperties properties = new GroceryProperties();

    private static String fixture() throws Exception {
        return Files.readString(Path.of("src/test/resources/grocery/logo/angebote.html"), StandardCharsets.UTF_8);
    }

    private LogoSource source(FakeHttpTransport transport) {
        return new LogoSource(new GroceryHttp(transport), properties);
    }

    private static FetchContext context(Set<Chain> chains) {
        return new FetchContext("61118", 50.18, 8.73, List.of(), chains);
    }

    private static RawOffer byTitle(List<RawOffer> offers, String title) {
        return offers.stream().filter(o -> o.title().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void parsesWeeklyOffersOnceEach() throws Exception {
        List<RawOffer> offers = source(new FakeHttpTransport()).parse(fixture());

        // 45 Karten, davon 4 doppelt im Abschnitt „Wochenangebote“
        assertThat(offers).hasSize(41);
        RawOffer pils = byTitle(offers, "König Pilsener");
        assertThat(pils.sourceOfferId()).isEqualTo("81325");
        assertThat(pils.chain()).isEqualTo(Chain.LOGO);
        assertThat(pils.source()).isEqualTo(SourceKey.LOGO);
        assertThat(pils.marketExternalId()).isNull();
        assertThat(pils.price()).isEqualByComparingTo("11.99");
        assertThat(pils.oldPrice()).isEqualByComparingTo("16.99");
        assertThat(pils.basePrice().unit()).isEqualTo(BaseUnit.LITER);
        assertThat(pils.basePrice().amount()).isEqualByComparingTo("1.20");
        assertThat(pils.validFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(pils.validTo()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(pils.imageUrl()).endsWith("/81325.jpg");
    }

    @Test
    void offerWithoutStrikePriceAndRegionalStarIsKept() throws Exception {
        List<RawOffer> offers = source(new FakeHttpTransport()).parse(fixture());

        RawOffer veltins = byTitle(offers, "Veltins Helles Lager");
        assertThat(veltins.oldPrice()).isNull();
        assertThat(veltins.basePrice().amount()).isEqualByComparingTo("2.12");
        assertThat(byTitle(offers, "Schlappeseppel Bier").description()).isEqualTo("diverse Sorten");
    }

    @Test
    void basePriceFallsBackToPackagingWithLiterAbbreviation() {
        assertThat(LogoSource.basePrice("0,5 Ltr. Flasche", null, new java.math.BigDecimal("2.00")))
                .hasValueSatisfying(bp -> {
                    assertThat(bp.unit()).isEqualTo(BaseUnit.LITER);
                    assertThat(bp.amount()).isEqualByComparingTo("4.00");
                });
    }

    @Test
    void weekAcrossNewYearTakesPreviousYearForStart() {
        LocalDate[] week = LogoSource.week("28.12.-03.01.2027");
        assertThat(week[0]).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(week[1]).isEqualTo(LocalDate.of(2027, 1, 3));
        assertThat(LogoSource.week("demnächst")).containsExactly(null, null);
    }

    @Test
    void pageWithoutArticlesIsAnError() {
        assertThatThrownBy(() -> source(new FakeHttpTransport()).parse("<html><body>Wartung</body></html>"))
                .isInstanceOf(SourceFetchException.class);
    }

    @Test
    void disabledChainFetchesNothing() throws Exception {
        FakeHttpTransport transport = new FakeHttpTransport();
        assertThat(source(transport).fetch(context(Set.of(Chain.LIDL)))).isEmpty();
    }
}
