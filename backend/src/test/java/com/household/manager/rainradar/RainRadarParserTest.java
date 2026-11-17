package com.household.manager.rainradar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RainRadarParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/rainradar/radar-plain.json")) {
            return mapper.readTree(in);
        }
    }

    @Test
    void echteAntwortLiefertMessungUndVorhersageFuerDasHauspixel() throws IOException {
        List<RainRadarFrame> frames = RainRadarParser.parse(fixture());

        assertThat(frames).hasSize(25);
        // Erstes Bild 13:25 MEZ = 12:25Z, gemessen; Hauspixel [1][1] = 17 ⇒ 2,04 mm/h
        assertThat(frames.get(0).time()).isEqualTo(Instant.parse("2026-11-17T12:25:00Z"));
        assertThat(frames.get(0).forecast()).isFalse();
        assertThat(frames.get(0).mmPerHour()).isCloseTo(2.04, org.assertj.core.data.Offset.offset(0.001));
        // Juengste Messung 13:25Z, danach Vorhersage desselben Laufs
        assertThat(frames.stream().filter(f -> !f.forecast()).map(RainRadarFrame::time).max(Instant::compareTo))
                .contains(Instant.parse("2026-11-17T13:25:00Z"));
        assertThat(frames.stream().filter(RainRadarFrame::forecast)).hasSize(12);
    }

    @Test
    void ortImRasterKommtAusLatlonPosition() throws IOException {
        String json = """
                {"latlon_position": {"x": 2.4, "y": 0.9},
                 "radar": [{"timestamp": "2026-11-17T13:25:00+00:00",
                            "source": "RADARCOMP::RV::2026-11-17T13:25:00+00:00",
                            "precipitation_5": [[0, 0, 7], [0, 0, 0], [0, 0, 0]]}]}
                """;

        List<RainRadarFrame> frames = RainRadarParser.parse(mapper.readTree(json));

        assertThat(frames).singleElement().satisfies(f -> {
            assertThat(f.mmPerHour()).isCloseTo(0.84, org.assertj.core.data.Offset.offset(0.001));
            assertThat(f.forecast()).isFalse();
        });
    }

    @Test
    void unlesbaresBildWirdUebersprungenNichtGeraten() throws IOException {
        String json = """
                {"latlon_position": {"x": 1.5, "y": 1.5},
                 "radar": [{"timestamp": "kaputt", "source": "x", "precipitation_5": [[0]]},
                           {"timestamp": "2026-11-17T13:25:00+00:00", "source": "RADARCOMP::RV::2026-11-17T13:25:00+00:00",
                            "precipitation_5": [[1]]}]}
                """;

        assertThat(RainRadarParser.parse(mapper.readTree(json))).isEmpty();
    }

    @Test
    void ohnePositionWirftDerParser() throws IOException {
        assertThatThrownBy(() -> RainRadarParser.parse(mapper.readTree("{\"radar\": []}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
