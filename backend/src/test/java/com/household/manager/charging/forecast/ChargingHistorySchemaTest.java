package com.household.manager.charging.forecast;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChargingHistorySchemaTest {

    private static final Path CHANGES = Path.of("src/main/resources/db/changelog/changes");
    private static final String HISTORY = "20261102-0080-create-charging-occupancy-history.xml";
    private static final String OBSERVATION = "20261102-0081-create-charging-observation.xml";
    private static final String UNIQUE = "20261102-0082-add-charging-observation-unique.xml";

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void eachChangesetFileContainsExactlyOneChangeSet() throws IOException {
        for (String name : new String[]{HISTORY, OBSERVATION, UNIQUE}) {
            String xml = read(CHANGES.resolve(name));
            assertThat(xml.split("<changeSet", -1).length - 1).as(name).isEqualTo(1);
        }
    }

    @Test
    void uniqueChangesetDefinesStationHourConstraint() throws IOException {
        assertThat(read(CHANGES.resolve(UNIQUE))).contains("uk_charging_observation_station_hour");
    }

    @Test
    void masterIncludesAllThreeInOrder() throws IOException {
        String master = read(Path.of("src/main/resources/db/changelog/db.changelog-master.xml"));
        int a = master.indexOf(HISTORY);
        int b = master.indexOf(OBSERVATION);
        int c = master.indexOf(UNIQUE);
        assertThat(a).isPositive();
        assertThat(b).isGreaterThan(a);
        assertThat(c).isGreaterThan(b);
    }
}
