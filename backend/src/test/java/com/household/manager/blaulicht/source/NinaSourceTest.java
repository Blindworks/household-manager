package com.household.manager.blaulicht.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.blaulicht.BlaulichtCategory;
import com.household.manager.blaulicht.BlaulichtFixtures;
import com.household.manager.blaulicht.BlaulichtProperties;
import com.household.manager.repository.BlaulichtEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NinaSourceTest {

    private static final String MOW_ID = "mow.DE-BY-R-SE084-20261103-84-000";

    private final BlaulichtProperties properties = new BlaulichtProperties();
    private final FakeTransport transport = new FakeTransport();
    private NinaSource source;
    private String base;

    @BeforeEach
    void setUp() {
        base = properties.getNina().getBaseUrl();
        source = new NinaSource(new BlaulichtHttp(transport, "test", 1000), properties,
                mock(BlaulichtEventRepository.class), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("Europe/Berlin")));
    }

    private void dashboard(String... ids) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":\"").append(ids[i])
                    .append("\",\"type\":\"Alert\",\"startDate\":\"2026-11-03T17:04:19+01:00\",")
                    .append("\"i18nTitle\":{\"de\":\"Titel\"}}");
        }
        transport.bodies.put(base + "/dashboard/" + properties.getNina().getDashboardArs() + ".json",
                json.append("]").toString());
    }

    @Test
    void warnungFuerAnderesGebietWirdVerworfen() throws Exception {
        dashboard(MOW_ID);
        transport.bodies.put(base + "/warnings/" + MOW_ID + ".json",
                BlaulichtFixtures.read("nina-warning-mow.DE-BY-R-SE084-20261103-84-000.json"));

        assertThat(source.fetchNew()).isEmpty();
    }

    @Test
    void warnungFuerDenKreisWirdGemeldet() throws Exception {
        dashboard(MOW_ID);
        transport.bodies.put(base + "/warnings/" + MOW_ID + ".json",
                BlaulichtFixtures.read("nina-warning-mow.DE-BY-R-SE084-20261103-84-000.json")
                        .replace("093725318150,093750209209,093755336120,093755336210", "064400000000"));

        assertThat(source.fetchNew()).singleElement().satisfies(report -> {
            assertThat(report.guid()).isEqualTo(MOW_ID);
            assertThat(report.fixedCategory()).isEqualTo(BlaulichtCategory.WARNUNG);
            assertThat(report.fixedLocality()).isEqualTo("Bad Vilbel");
            assertThat(report.title()).startsWith("Trinkwasserverschmutzung");
            assertThat(report.url()).startsWith("http");
        });
        assertThat(source.fetchNew()).isEmpty();
    }

    @Test
    void wetterdienstIstAusgenommen() throws Exception {
        dashboard("dwdmap.2.49.0.0.276.0.DWD.PVW.1");

        assertThat(source.fetchNew()).isEmpty();
        assertThat(transport.requests).noneMatch(r -> r.uri().toString().contains("/warnings/"));
    }
}
