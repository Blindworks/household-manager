package com.household.manager.laundry;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LaundryClimateReaderTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 18, 0);

    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private final LaundryProperties properties = new LaundryProperties();
    private LaundryClimateReader reader;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(BERLIN).toInstant(), BERLIN);
        reader = new LaundryClimateReader(entityStateService, properties, clock);
    }

    @Test
    void liefertWerteMitAelteremMesszeitpunkt() {
        stub(properties.getTemperatureEntity(), "16.2", NOW.minusMinutes(5));
        stub(properties.getHumidityEntity(), "61.5", NOW.minusMinutes(20));

        KellerClimate climate = reader.current().orElseThrow();

        assertThat(climate.tempC()).isEqualTo(16.2);
        assertThat(climate.humidity()).isEqualTo(61.5);
        assertThat(climate.measuredAt()).isEqualTo(NOW.minusMinutes(20));
    }

    @Test
    void unavailableIstLeer() {
        stub(properties.getTemperatureEntity(), "unavailable", NOW);
        stub(properties.getHumidityEntity(), "61.5", NOW);

        assertThat(reader.current()).isEmpty();
    }

    @Test
    void nichtEndlicheZahlIstLeer() {
        stub(properties.getTemperatureEntity(), "16.2", NOW);
        stub(properties.getHumidityEntity(), "NaN", NOW);

        assertThat(reader.current()).isEmpty();
    }

    @Test
    void fehlenderFuehlerIstLeer() {
        stub(properties.getTemperatureEntity(), "16.2", NOW);
        when(entityStateService.getByEntityId(properties.getHumidityEntity())).thenReturn(Optional.empty());

        assertThat(reader.current()).isEmpty();
    }

    @Test
    void aelterAlsEineStundeIstLeer() {
        stub(properties.getTemperatureEntity(), "16.2", NOW);
        stub(properties.getHumidityEntity(), "61.5", NOW.minusMinutes(61));

        assertThat(reader.current()).isEmpty();
    }

    private void stub(String entityId, String state, LocalDateTime lastUpdated) {
        EntityState entity = new EntityState();
        entity.setEntityId(entityId);
        entity.setState(state);
        entity.setLastUpdated(lastUpdated);
        when(entityStateService.getByEntityId(entityId)).thenReturn(Optional.of(entity));
    }
}
