package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.tractive.GeoZone;
import com.household.manager.tractive.TractiveHomeResolver;
import com.household.manager.tractive.TractivePetSnapshot;
import com.household.manager.tractive.TractiveProperties;
import com.household.manager.tractive.TractiveZoneResolver;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TractiveEntityMapperTest {

    private TractiveEntityMapper mapper;

    @BeforeEach
    void setUp() {
        TractiveProperties properties = new TractiveProperties();
        properties.setHomeLatitude(48.2082);
        properties.setHomeLongitude(16.3738);
        properties.setHomeRadiusMeters(100);
        // Home-Koordinaten sind zugleich die Fallback-Zone des TractiveZoneResolver.
        mapper = new TractiveEntityMapper(new TractiveZoneResolver(properties),
                new TractiveHomeResolver(properties));
    }

    private TractiveTrackableDto bello() {
        return new TractiveTrackableDto("trk-1", "dev-9",
                new TractiveTrackableDto.Details("Bello", "DOG"));
    }

    private EntityStateUpdate byId(List<EntityStateUpdate> updates, String entityId) {
        return updates.stream()
                .filter(update -> update.entityId().equals(entityId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entitaet fehlt: " + entityId));
    }

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void positionInsideZoneBecomesZoneName() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS",
                        NOW.getEpochSecond()),
                new TractiveHardwareDto(87, "NOT_CHARGING"),
                List.of(new GeoZone("Garten", 48.2082, 16.3738, 100)));

        var location = byId(mapper.map(snapshot, NOW), "sensor.tractive_dev_9_location");

        assertEquals("Garten", location.state());
        assertEquals(EntitySource.TRACTIVE, location.source());
        assertEquals(48.2082, location.attributes().get("latitude"));
        assertEquals(16.3738, location.attributes().get("longitude"));
        assertEquals("GPS", location.attributes().get("sensorUsed"));
        assertEquals("Bello", location.friendlyName());
    }

    @Test
    void positionOutsideAllZonesBecomesAway() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.3000, 16.3738), 12.0, "GPS",
                        NOW.getEpochSecond()),
                null,
                List.of(new GeoZone("Garten", 48.2082, 16.3738, 100)));

        assertEquals("away", byId(mapper.map(snapshot, NOW), "sensor.tractive_dev_9_location").state());
    }

    @Test
    void missingPositionBecomesUnknown() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(null, null, null, null), null, List.of());

        var location = byId(mapper.map(snapshot, NOW), "sensor.tractive_dev_9_location");

        assertEquals("unknown", location.state());
        assertFalse(location.attributes().containsKey("latitude"));
    }

    @Test
    void batteryAndChargingAreMapped() {
        var snapshot = new TractivePetSnapshot(bello(), null,
                new TractiveHardwareDto(87, "CHARGING"), List.of());

        List<EntityStateUpdate> updates = mapper.map(snapshot, NOW);

        assertEquals("87", byId(updates, "sensor.tractive_dev_9_battery").state());
        assertEquals("battery",
                byId(updates, "sensor.tractive_dev_9_battery").attributes().get("deviceClass"));
        assertEquals("on", byId(updates, "binary_sensor.tractive_dev_9_charging").state());
    }

    @Test
    void withoutHardwareNoBatteryEntityIsReported() {
        var snapshot = new TractivePetSnapshot(bello(), null, null, List.of());

        List<EntityStateUpdate> updates = mapper.map(snapshot, NOW);

        assertTrue(updates.stream().noneMatch(u -> u.entityId().contains("battery")));
        assertTrue(updates.stream().noneMatch(u -> u.entityId().contains("charging")));
    }

    /** Ein frischer Bericht am Home-Punkt: die Entitaet steht auf 'on'. */
    @Test
    void homeEntityIsReportedForAFreshPositionAtHome() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", NOW.getEpochSecond()),
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());

        var home = byId(mapper.map(snapshot, NOW), "binary_sensor.tractive_dev_9_home");

        assertEquals("on", home.state());
        assertEquals("Bello zu Hause", home.friendlyName());
        assertEquals("presence", home.attributes().get("deviceClass"));
        assertEquals("position", home.attributes().get("basis"));
        assertEquals(false, home.attributes().get("stale"));
        assertTrue(home.attributes().containsKey("distanceMeters"));
        assertTrue(home.attributes().containsKey("positionTime"));
    }

    /** Position bewusst weit weg: 'on' beweist, dass Laden die Position schlaegt. */
    @Test
    void chargingIsReportedAsAtHomeWithItsOwnBasis() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.3000, 16.5000), 12.0, "GPS",
                        NOW.getEpochSecond()),
                new TractiveHardwareDto(50, "CHARGING"), List.of());

        var home = byId(mapper.map(snapshot, NOW), "binary_sensor.tractive_dev_9_home");

        assertEquals("on", home.state());
        assertEquals("charging", home.attributes().get("basis"));
    }

    /** Ohne verwertbare Daten entsteht kein Update – der letzte Wert bleibt so stehen. */
    @Test
    void withoutAnyVerdictNoHomeEntityIsReported() {
        var snapshot = new TractivePetSnapshot(bello(), null,
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());

        List<EntityStateUpdate> updates = mapper.map(snapshot, NOW);

        assertTrue(updates.stream().noneMatch(u -> u.entityId().endsWith("_home")));
    }

    @Test
    void isHomeEntityRecognisesOnlyTheHomeEntity() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", NOW.getEpochSecond()),
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());
        List<EntityStateUpdate> updates = mapper.map(snapshot, NOW);

        assertTrue(mapper.isHomeEntity(byId(updates, "binary_sensor.tractive_dev_9_home")));
        assertFalse(mapper.isHomeEntity(byId(updates, "sensor.tractive_dev_9_location")));
        assertFalse(mapper.isHomeEntity(byId(updates, "sensor.tractive_dev_9_battery")));
    }

    /** Der Poller ruft das im Ausfallpfad auf – hier darf nie eine Exception entstehen. */
    @Test
    void isHomeEntityReturnsFalseForAnIncompleteUpdate() {
        var incomplete = EntityStateUpdate.builder()
                .entityId("binary_sensor.tractive__home")
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.TRACTIVE)
                .sourceRef(null)
                .friendlyName("kaputt")
                .state("on")
                .attributes(Map.of())
                .build();

        assertFalse(mapper.isHomeEntity(incomplete));
    }

    /** Stiller Bericht, gesunder Akku, Heimnaehe: Regel 4 traegt Basis, stale und Alter mit. */
    @Test
    void aStalePositionNearHomeIsReportedAsPoweredOff() {
        long twoHoursAgo = NOW.minus(2, ChronoUnit.HOURS).getEpochSecond();
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.2109, 16.3738), 12.0, "GPS", twoHoursAgo),
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());

        var home = byId(mapper.map(snapshot, NOW), "binary_sensor.tractive_dev_9_home");

        assertEquals("on", home.state());
        assertEquals("powered_off", home.attributes().get("basis"));
        assertEquals(true, home.attributes().get("stale"));
        assertEquals(120L, home.attributes().get("positionAgeMinutes"));
    }
}
