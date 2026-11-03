package com.household.manager.dirigera;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DirigeraButtonEventMapperTest {

    private static final String TOP = "4ab740b2-efb5-4301-8bf5-63c702722938_1";
    private static final DirigeraButton TOP_BUTTON =
            new DirigeraButton(TOP, "Button Treppenhaus", "Treppenhaus", "BILRESA dual button", "top", 100);

    private final DirigeraEntityMapper mapper = new DirigeraEntityMapper();

    @Test
    void entityIdHaengtAnDerGeraeteIdJeTaste() {
        assertThat(DirigeraEntityMapper.buttonEntityId(TOP))
                .isEqualTo("event.dirigera_4ab740b2efb543018bf563c702722938_1_action");
        assertThat(DirigeraEntityMapper.buttonEntityId("4ab740b2-efb5-4301-8bf5-63c702722938_2"))
                .isEqualTo("event.dirigera_4ab740b2efb543018bf563c702722938_2_action");
    }

    @Test
    void klickmusterWerdenAufDasTasterVokabularAbgebildet() {
        assertThat(state("singlePress")).isEqualTo("single");
        assertThat(state("doublePress")).isEqualTo("double");
        assertThat(state("longPress")).isEqualTo("hold");
        // unbekanntes Muster geht roh durch, statt verworfen oder geraten zu werden
        assertThat(state("tripplePress")).isEqualTo("tripplePress");
    }

    @Test
    void ereignisTraegtNameTasteUndRohesMuster() {
        EntityStateUpdate update = mapper.buttonEvent(
                new DirigeraRemotePress(TOP, "singlePress", Instant.parse("2026-11-03T17:28:58.700Z")), TOP_BUTTON);

        assertThat(update.domain()).isEqualTo(EntityDomain.EVENT);
        assertThat(update.source()).isEqualTo(EntitySource.DIRIGERA);
        assertThat(update.sourceRef()).isEqualTo("4ab740b2efb543018bf563c702722938_1");
        assertThat(update.friendlyName()).isEqualTo("Button Treppenhaus oben");
        assertThat(update.attributes())
                .containsEntry("deviceClass", "button")
                .containsEntry("clickPattern", "singlePress")
                .containsEntry("button", "top")
                .containsEntry("room", "Treppenhaus")
                .containsEntry("model", "BILRESA dual button")
                .containsEntry("batteryPercent", 100)
                .containsEntry("pressedAt", "2026-11-03T17:28:58.700Z");
    }

    @Test
    void unbekannteTasteBekommtDieGeraeteIdAlsNamen() {
        EntityStateUpdate update = mapper.buttonEvent(new DirigeraRemotePress(TOP, "singlePress", null), null);

        assertThat(update.friendlyName()).isEqualTo(TOP + " Taster");
        assertThat(update.attributes()).containsOnlyKeys("deviceClass", "clickPattern");
    }

    @Test
    void untereTasteHeisstUnten() {
        DirigeraButton bottom = new DirigeraButton("x_2", "Button Treppenhaus", null, null, "bottom", null);

        assertThat(mapper.buttonEvent(new DirigeraRemotePress("x_2", "singlePress", null), bottom).friendlyName())
                .isEqualTo("Button Treppenhaus unten");
    }

    private String state(String clickPattern) {
        return mapper.buttonEvent(new DirigeraRemotePress(TOP, clickPattern, null), TOP_BUTTON).state();
    }
}
