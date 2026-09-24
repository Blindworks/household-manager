package com.household.manager.entitystate;

import java.util.List;
import java.util.Map;

/**
 * Katalog der Haus-Modi für die Modus-Leiste des Dashboards.
 * Listen-Reihenfolge = Anzeige-Reihenfolge. Modus-Entities tragen das
 * Marker-Attribut {@link #ATTR_MODE}, über das Modus- und Schalter-API
 * sie von gewöhnlichen Boolean-Helfern unterscheiden.
 */
public final class HouseModes {

    /** Marker-Attribut: kennzeichnet eine INPUT_BOOLEAN-Entity als Haus-Modus. */
    public static final String ATTR_MODE = "mode";

    /** Feste Modus-Definitionen in Anzeige-Reihenfolge. */
    public static final List<HouseModeDefinition> CATALOG = List.of(
            new HouseModeDefinition("Abwesend", "exit_to_app"),
            new HouseModeDefinition("Toni allein", "pets"),
            new HouseModeDefinition("Nachtmodus", "nights_stay"),
            new HouseModeDefinition("Morgenmodus", "wb_twilight"),
            new HouseModeDefinition("Bewegungssensoren aus", "sensors_off")
    );

    /**
     * Ehemalige Modi, die der {@link HouseModeInitializer} beim Start löscht, solange
     * sie noch das Modus-Marker-Attribut tragen:
     * <ul>
     *   <li>„Ausschalten" — ersetzt durch den Reboot-Aktions-Button im Dashboard.</li>
     *   <li>„Bewegungssensoren" — umgedreht zu „Bewegungssensoren aus": Normalzustand
     *       sind aktive Melder, der Modus ist nur in Sonderfällen an. Der Zustand wird
     *       bewusst nicht übernommen, der neue Modus startet aus (= Melder aktiv).</li>
     * </ul>
     */
    public static final List<String> RETIRED_MODE_ENTITY_IDS = List.of(
            "input_boolean.manual_ausschalten",
            "input_boolean.manual_bewegungssensoren"
    );

    private HouseModes() {
    }

    /** Stabile Entity-ID eines Katalog-Modus, z. B. {@code input_boolean.manual_toni_allein}. */
    public static String entityId(HouseModeDefinition definition) {
        return EntityIds.build(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL, definition.name(), null);
    }

    /** True, wenn die (bereits geparsten) Attribute eine Entity als Modus kennzeichnen. */
    public static boolean isMode(Map<String, Object> attributes) {
        return Boolean.TRUE.equals(attributes.get(ATTR_MODE));
    }

    /** Name und Material-Symbols-Icon eines Haus-Modus. */
    public record HouseModeDefinition(String name, String icon) {
    }
}
