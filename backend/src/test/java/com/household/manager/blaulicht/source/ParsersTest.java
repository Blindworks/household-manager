package com.household.manager.blaulicht.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.blaulicht.BlaulichtFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParsersTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rssFeedLiefertAlleEintraege() {
        List<PresseportalRssParser.Item> items =
                PresseportalRssParser.parse(BlaulichtFixtures.read("presseportal-154582.rss2"), BERLIN);

        assertThat(items).hasSize(15);
        assertThat(items).anySatisfy(item -> {
            assertThat(item.guid()).isEqualTo("https://www.presseportal.de/blaulicht/pm/154582/6397918");
            assertThat(item.title()).contains("WETTERAUKREIS");
            assertThat(item.url()).isEqualTo(item.guid());
            assertThat(item.publishedAt()).isNotNull();
        });
    }

    @Test
    void detailseiteOhneKopfUndAnbieterzeile() {
        PresseportalDetailParser.Detail detail = BlaulichtFixtures.detail("6382768");

        assertThat(detail.title()).contains("WETTERAUKREIS");
        assertThat(detail.publishedAt()).isNotNull();
        assertThat(detail.paragraphs()).isNotEmpty();
        assertThat(detail.paragraphs()).noneMatch(p -> p.contains("(ots)"));
        assertThat(String.join("\n", detail.paragraphs())).contains("Bad Vilbel");
    }

    @Test
    void ninaDashboardAusKartendaten() throws Exception {
        List<NinaParser.DashboardEntry> entries =
                NinaParser.parseDashboard(mapper, BlaulichtFixtures.read("nina-mowas-mapdata.json"), BERLIN);

        assertThat(entries).hasSize(10).allSatisfy(e -> assertThat(e.provider()).isEqualTo("mow"));
        assertThat(entries.get(0).startDate()).isEqualTo(LocalDateTime.of(2026, 9, 4, 13, 5, 28));
        assertThat(entries.get(0).title()).startsWith("1. AKTUALISIERUNG");
    }

    @Test
    void ninaWarnungInBayernBetrifftBadVilbelNicht() throws Exception {
        NinaParser.Warning warning = NinaParser.parseWarning(mapper,
                BlaulichtFixtures.read("nina-warning-mow.DE-BY-R-SE084-20261103-84-000.json"), BERLIN).orElseThrow();

        assertThat(warning.msgType()).isEqualTo("Alert");
        assertThat(warning.headline()).startsWith("Trinkwasserverschmutzung");
        assertThat(warning.sent()).isEqualTo(LocalDateTime.of(2026, 11, 3, 17, 4, 19));
        assertThat(warning.areaCodes()).contains("093725318150");
        assertThat(NinaParser.affects(warning, "064400003003")).isFalse();
    }

    @Test
    void uebergeordneteGebieteBetreffenBadVilbel() {
        assertThat(NinaParser.affects(warning("064400000000"), "064400003003")).isTrue();
        assertThat(NinaParser.affects(warning("060000000000"), "064400003003")).isTrue();
        assertThat(NinaParser.affects(warning("064400003003"), "064400003003")).isTrue();
        assertThat(NinaParser.affects(warning("064350000000"), "064400003003")).isFalse();
        assertThat(NinaParser.affects(warning(), "064400003003")).isTrue();
    }

    private static NinaParser.Warning warning(String... codes) {
        return new NinaParser.Warning("x", "Alert", "h", null, null, null, null, List.of(codes));
    }
}
