package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TractivePetServiceTest {

    @Mock
    private TractivePollingService pollingService;
    @Mock
    private TractiveZoneResolver zoneResolver;

    /** Bewusst die echte Klasse: der Test soll die reale Home-Definition pruefen. */
    private TractiveHomeResolver homeResolver() {
        TractiveProperties properties = new TractiveProperties();
        properties.setHomeLatitude(48.2082);
        properties.setHomeLongitude(16.3738);
        properties.setHomeRadiusMeters(100);
        return new TractiveHomeResolver(properties);
    }

    @Test
    void petsAreReturnedForTheMap() {
        Instant now = Instant.now();
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS",
                        now.getEpochSecond()),
                new TractiveHardwareDto(87, "NOT_CHARGING"),
                List.of(new GeoZone("Garten", 48.2082, 16.3738, 100)));
        when(pollingService.lastPolledAt()).thenReturn(now);
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));
        when(zoneResolver.resolve(48.2082, 16.3738, snapshot.zones())).thenReturn("Garten");

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets).hasSize(1);
        TractivePetDto pet = pets.get(0);
        assertThat(pet.name()).isEqualTo("Bello");
        assertThat(pet.trackerId()).isEqualTo("dev-9");
        assertThat(pet.latitude()).isEqualTo(48.2082);
        assertThat(pet.batteryPercent()).isEqualTo(87);
        assertThat(pet.charging()).isFalse();
        assertThat(pet.zone()).isEqualTo("Garten");
        assertThat(pet.atHome()).isTrue();
    }

    @Test
    void petWithoutPositionOmitsCoordinates() {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                null, null, List.of());
        when(pollingService.lastPolledAt()).thenReturn(Instant.now());
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets).hasSize(1);
        TractivePetDto pet = pets.get(0);
        assertThat(pet.latitude()).isNull();
        assertThat(pet.longitude()).isNull();
        assertThat(pet.zone()).isEqualTo(TractiveZoneResolver.UNKNOWN);
    }

    @Test
    void emptyCacheYieldsEmptyList() {
        when(pollingService.lastPolledAt()).thenReturn(Instant.now());
        when(pollingService.latestSnapshots()).thenReturn(List.of());

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets).isEmpty();
    }

    /** Vor dem ersten erfolgreichen Poll gibt es weder Snapshots noch einen Bewertungszeitpunkt. */
    @Test
    void listPetsIsEmptyWhenNoPollHasSucceededYet() {
        when(pollingService.lastPolledAt()).thenReturn(null);

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets).isEmpty();
    }

    @Test
    void withoutAnyVerdictAtHomeIsNull() {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                null, null, List.of());
        when(pollingService.lastPolledAt()).thenReturn(Instant.now());
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets.get(0).atHome()).isNull();
    }

    @Test
    void aPetFarFromHomeIsNotAtHome() {
        Instant now = Instant.now();
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                new TractivePositionDto(List.of(48.3000, 16.5000), 12.0, "GPS",
                        now.getEpochSecond()),
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());
        when(pollingService.lastPolledAt()).thenReturn(now);
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));
        when(zoneResolver.resolve(48.3000, 16.5000, snapshot.zones())).thenReturn("away");

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets.get(0).atHome()).isFalse();
    }

    /**
     * Regression: waere hier "jetzt" statt des Poll-Zeitpunkts massgeblich, wuerde derselbe
     * eingefrorene Snapshot ueber die Stille-Schwelle laufen und Regel 4 (powered_off) statt
     * Regel 3 (frischer Bericht, zu weit weg) liefern - die Entitaet haette aber laengst mit
     * Regel 3 geantwortet, weil sie beim Poll noch "frisch" war. Kachel und Entitaet muessen
     * hier uebereinstimmen.
     */
    @Test
    void apiJudgesTheSnapshotAtThePollInstantNotNow() {
        Instant pollInstant = Instant.now().minus(65, ChronoUnit.MINUTES);
        Instant freshReportAt = pollInstant.minus(5, ChronoUnit.MINUTES);
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                new TractivePositionDto(List.of(48.2109, 16.3738), 12.0, "GPS",
                        freshReportAt.getEpochSecond()),
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());
        when(pollingService.lastPolledAt()).thenReturn(pollInstant);
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets.get(0).atHome()).isFalse();
    }
}
