package com.household.manager.blaulicht.source;

import com.household.manager.blaulicht.settings.TestBlaulichtSettings;
import com.household.manager.blaulicht.BlaulichtFixtures;
import com.household.manager.blaulicht.BlaulichtProperties;
import com.household.manager.repository.BlaulichtEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresseportalSourceTest {

    private static final String PM = "https://www.presseportal.de/blaulicht/pm/154582/";

    private final BlaulichtProperties properties = new BlaulichtProperties();
    private final FakeTransport transport = new FakeTransport();
    private final BlaulichtEventRepository repository = mock(BlaulichtEventRepository.class);
    private PresseportalSource source;

    @BeforeEach
    void setUp() {
        transport.bodies.put(properties.getPresseportal().getFeedUrl(),
                BlaulichtFixtures.read("presseportal-154582.rss2"));
        for (String id : List.of("6397918", "6396365")) {
            transport.bodies.put(PM + id, BlaulichtFixtures.read("presseportal-detail-" + id + ".html"));
        }
        source = new PresseportalSource(new BlaulichtHttp(transport, "test", 1000), properties, repository,
                TestBlaulichtSettings.defaults(), Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("Europe/Berlin")));
    }

    @Test
    void liestNurWetterauMeldungenUndJedeNurEinmal() throws Exception {
        List<RawReport> reports = source.fetchNew();

        assertThat(reports).extracting(RawReport::guid).containsExactly(PM + "6397918", PM + "6396365");
        assertThat(reports.get(0).paragraphs()).isNotEmpty();
        assertThat(transport.requests).allSatisfy(r ->
                assertThat(r.headers().firstValue("User-Agent")).contains("test"));

        assertThat(source.fetchNew()).isEmpty();
        assertThat(transport.count(PM + "6397918")).isEqualTo(1);
    }

    @Test
    void schonGespeicherteMeldungWirdNichtGelesen() throws Exception {
        when(repository.existsForGuid(any(), anyString())).thenReturn(true);

        assertThat(source.fetchNew()).isEmpty();
        assertThat(transport.count(PM + "6397918")).isZero();
    }

    @Test
    void budgetVerschiebtDenRestInDenNaechstenLauf() throws Exception {
        properties.getPresseportal().setMaxDetailFetchesPerRun(1);

        assertThat(source.fetchNew()).extracting(RawReport::guid).containsExactly(PM + "6397918");
        assertThat(source.fetchNew()).extracting(RawReport::guid).containsExactly(PM + "6396365");
    }

    @Test
    void einzelnerDetailfehlerKipptDenLaufNicht() throws Exception {
        transport.bodies.remove(PM + "6396365");

        assertThat(source.fetchNew()).extracting(RawReport::guid).containsExactly(PM + "6397918");
    }

    @Test
    void nurFehlerIstEinAusfall() {
        transport.bodies.remove(PM + "6397918");
        transport.bodies.remove(PM + "6396365");

        assertThatThrownBy(source::fetchNew).isInstanceOf(BlaulichtFetchException.class);
    }

    @Test
    void unveraenderterFeedLiefertNichts() throws Exception {
        transport.etag = "\"v1\"";
        source.fetchNew();

        assertThat(source.fetchNew()).isEmpty();
        assertThat(transport.requests.get(transport.requests.size() - 1).headers()
                .firstValue("If-None-Match")).contains("\"v1\"");
    }
}
