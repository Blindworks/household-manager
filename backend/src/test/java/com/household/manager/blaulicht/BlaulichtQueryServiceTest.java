package com.household.manager.blaulicht;

import com.household.manager.blaulicht.dto.BlaulichtBriefingDto;
import com.household.manager.repository.BlaulichtEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.household.manager.blaulicht.NightWindowTest.at;
import static com.household.manager.blaulicht.NightWindowTest.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

class BlaulichtQueryServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private final BlaulichtEventRepository repository = mock(BlaulichtEventRepository.class);
    private final List<BlaulichtEvent> stored = new ArrayList<>();

    private BlaulichtQueryService serviceAt(LocalDateTime now) {
        doAnswer(inv -> {
            LocalDateTime from = inv.getArgument(0);
            return stored.stream().filter(e -> !e.getPublishedAt().isBefore(from)).toList();
        }).when(repository).findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any());
        return new BlaulichtQueryService(repository, new BlaulichtProperties(),
                Clock.fixed(now.atZone(BERLIN).toInstant(), BERLIN));
    }

    private BlaulichtEvent add(BlaulichtCategory category, String place, boolean nearby,
                               LocalDateTime occurred, LocalDateTime published) {
        BlaulichtEvent e = event(occurred, null, published);
        e.setCategory(category);
        e.setLocality(place);
        e.setNearby(nearby);
        stored.add(e);
        return e;
    }

    @Test
    void ruhigeNacht() {
        BlaulichtBriefingDto briefing = serviceAt(LocalDateTime.of(2026, 11, 25, 8, 1)).briefing();

        assertThat(briefing.text()).isEqualTo("Heute Nacht war es in Bad Vilbel ruhig.");
        assertThat(briefing.cardVisible()).isTrue();
        assertThat(briefing.addendumVisible()).isFalse();
    }

    @Test
    void morgensZaehlenNurVorAchtVeroeffentlichteMeldungen() {
        add(BlaulichtCategory.EINBRUCH, "Dortelweil", false, at(24, 23), at(25, 2));
        add(BlaulichtCategory.EINBRUCH, "Dortelweil", false, at(25, 1), at(25, 3));
        add(BlaulichtCategory.BRAND, "Karben", true, at(25, 1), at(25, 3));

        BlaulichtBriefingDto morning = serviceAt(LocalDateTime.of(2026, 11, 25, 8, 30)).briefing();
        assertThat(morning.text()).isEqualTo("Heute Nacht: 2 Einbrüche in Dortelweil. Dazu 1 Meldung aus Nachbarorten.");
        assertThat(morning.mainCount()).isEqualTo(2);
        assertThat(morning.nearbyCount()).isEqualTo(1);

        add(BlaulichtCategory.UNFALL, "Bad Vilbel", false, at(25, 2), at(25, 13));
        add(BlaulichtCategory.DIEBSTAHL, "Gronau", false, at(22, 10), at(25, 14));
        BlaulichtBriefingDto afternoon = serviceAt(LocalDateTime.of(2026, 11, 25, 16, 0)).briefing();
        assertThat(afternoon.addendumText()).isEqualTo("Nachtrag zur Nacht: 1 Unfall in Bad Vilbel.");
        assertThat(afternoon.addendumVisible()).isTrue();
        assertThat(afternoon.olderNewCount()).isEqualTo(1);
        assertThat(afternoon.text()).endsWith("Außerdem 1 neue Meldung zu früheren Tagen.");
        assertThat(afternoon.events()).hasSize(4);
    }

    @Test
    void karteVerschwindetAbends() {
        BlaulichtBriefingDto evening = serviceAt(LocalDateTime.of(2026, 11, 25, 20, 30)).briefing();

        assertThat(evening.cardVisible()).isFalse();
        assertThat(evening.nightEnd()).isEqualTo(LocalDateTime.of(2026, 11, 26, 8, 0));
    }

    @Test
    void listeFiltertUndMarkiertNeueMeldungen() {
        add(BlaulichtCategory.EINBRUCH, "Dortelweil", false, at(25, 1), at(25, 3));
        add(BlaulichtCategory.BRAND, "Bad Vilbel", false, at(23, 1), at(23, 13));

        BlaulichtQueryService service = serviceAt(LocalDateTime.of(2026, 11, 25, 9, 0));
        assertThat(service.list(null, null, null)).extracting(d -> d.newSinceEvening()).containsExactly(true, false);
        assertThat(service.list(null, BlaulichtCategory.BRAND, null)).hasSize(1);
        assertThat(service.list(null, null, "dortel")).hasSize(1);
    }
}
