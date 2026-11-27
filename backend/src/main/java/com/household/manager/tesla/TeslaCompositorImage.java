package com.household.manager.tesla;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Baut die Bildadresse aus Teslas Konfigurator ({@code static-assets.tesla.com/v1/compositor/}),
 * dieselbe Quelle wie das Bild unter „Meine Produkte“ auf tesla.com. Einzige Stelle, die
 * Modellkürzel, Ansicht und Lackcodes kennt.
 *
 * <p>Die Parameter sind nicht offiziell dokumentiert (von der Community entschlüsselt). Grundlage
 * sind die Ausstattungscodes aus Tessie ({@code option_codes}); fehlt dort ein Lackcode, wird er
 * aus {@code exterior_color} ergänzt. Ohne bekanntes Modell gibt es keine Adresse — dann zeigt die
 * Kachel weiter die Zeichnung, und auf der Admin-Seite lässt sich eine eigene Adresse eintragen.
 */
public final class TeslaCompositorImage {

    static final String BASE_URL = "https://static-assets.tesla.com/v1/compositor/";
    static final String VIEW = "STUD_3QTR";
    static final int SIZE = 1024;

    private static final Map<String, String> MODELS = Map.of(
            "model3", "m3",
            "modely", "my",
            "models", "ms",
            "models2", "ms",
            "modelx", "mx",
            "cybertruck", "ct");

    /** {@code exterior_color} der Tesla-API → Lackcode (nur, wenn die Ausstattungscodes keinen tragen). */
    private static final Map<String, String> PAINTS = Map.ofEntries(
            Map.entry("pearlwhite", "PPSW"),
            Map.entry("solidblack", "PBSB"),
            Map.entry("obsidianblack", "PMBL"),
            Map.entry("midnightsilver", "PMNG"),
            Map.entry("silvermetallic", "PMSS"),
            Map.entry("deepblue", "PPSB"),
            Map.entry("redmulticoat", "PPMR"),
            Map.entry("stealthgrey", "PN01"),
            Map.entry("ultrared", "PR01"),
            Map.entry("quicksilver", "PN00"),
            Map.entry("midnightcherryred", "PR00"),
            Map.entry("diamondblack", "PX02"));

    /**
     * {@code vehicle_config.wheel_type} → compositor wheel code. Without a wheel code the compositor
     * draws the car with empty wheel arches, so a wheel is always set (see {@link #DEFAULT_WHEELS}).
     * Keys are lower case letters and digits only.
     */
    private static final Map<String, String> WHEELS = Map.ofEntries(
            Map.entry("pinwheel18", "W38B"),
            Map.entry("pinwheel18cap", "W38B"),
            Map.entry("aeroturbine18", "W38B"),
            Map.entry("pinwheelrefresh18", "W38A"),
            Map.entry("photon18", "W38A"),
            Map.entry("stiletto19", "W39B"),
            Map.entry("sportwheel19", "W39B"),
            Map.entry("nova19", "W39S"),
            Map.entry("stiletto20", "W32P"),
            Map.entry("performancewheel20", "W32P"),
            Map.entry("apollo19", "WY19B"),
            Map.entry("gemini19", "WY19B"),
            Map.entry("induction20", "WY20P"),
            Map.entry("uberturbine21", "WY21P"),
            Map.entry("tempest19", "WS90"),
            Map.entry("arachnid21", "WS10"),
            Map.entry("cyberstream20", "WX00"),
            Map.entry("turbine22", "WX20"));

    /** Fallback wheel per model when {@code wheel_type} is missing or unknown. */
    private static final Map<String, String> DEFAULT_WHEELS = Map.of(
            "m3", "W38B",
            "my", "WY19B",
            "ms", "WS90",
            "mx", "WX00");

    private TeslaCompositorImage() {
    }

    /** Adresse für das Fahrzeug oder {@code null}, wenn das Modell unbekannt ist. */
    public static String urlFor(TeslaVehicleSnapshot vehicle) {
        if (vehicle == null || vehicle.carType() == null) {
            return null;
        }
        String model = MODELS.get(vehicle.carType().toLowerCase(Locale.ROOT));
        if (model == null) {
            return null;
        }
        Set<String> options = new LinkedHashSet<>(parseOptionCodes(vehicle.optionCodes()));
        // Tesla's option codes are often stale defaults; their wheel code may not exist in the
        // compositor (or be missing entirely), which renders the car without wheels.
        options.removeIf(TeslaCompositorImage::isWheelCode);
        String wheel = wheelCode(model, vehicle.wheelType());
        if (wheel != null) {
            options.add(wheel);
        }
        boolean hasPaint = options.stream().anyMatch(TeslaCompositorImage::isPaintCode);
        if (!hasPaint && vehicle.exteriorColor() != null) {
            String paint = PAINTS.get(vehicle.exteriorColor().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""));
            if (paint != null) {
                options.add(paint);
            }
        }
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("?model=").append(model)
                .append("&view=").append(VIEW)
                .append("&size=").append(SIZE)
                .append("&bkba_opt=1");
        if (!options.isEmpty()) {
            url.append("&options=").append(String.join(",", options.stream().map(o -> "$" + o).toList()));
        }
        return url.toString();
    }

    /**
     * Ausstattungscodes kommen kommagetrennt ({@code "AD15,MDL3,PBSB"}); ohne Komma als lückenlose
     * Folge von Vierercodes. Alles außer Großbuchstaben und Ziffern wird verworfen.
     */
    static List<String> parseOptionCodes(String raw) {
        List<String> codes = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return codes;
        }
        if (raw.contains(",")) {
            for (String part : raw.split(",")) {
                String code = part.trim().replace("$", "").toUpperCase(Locale.ROOT);
                if (code.matches("[A-Z0-9]{2,6}")) {
                    codes.add(code);
                }
            }
            return codes;
        }
        String compact = raw.trim().toUpperCase(Locale.ROOT);
        if (compact.matches("([A-Z0-9]{4})+")) {
            for (int i = 0; i < compact.length(); i += 4) {
                codes.add(compact.substring(i, i + 4));
            }
        }
        return codes;
    }

    static String wheelCode(String model, String wheelType) {
        if (wheelType != null) {
            String wheel = WHEELS.get(wheelType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
            if (wheel != null) {
                return wheel;
            }
        }
        return DEFAULT_WHEELS.get(model);
    }

    /** Wheel codes: W + digit (Model 3: W38B) or W + model letter + digits (Model Y: WY19B). */
    static boolean isWheelCode(String code) {
        return code.matches("W[0-9][0-9A-Z]{2}") || code.matches("W[YSX][0-9]{2}[A-Z]?");
    }

    /** Lackcodes: P + Lackart (B solid, M metallic, P pearl/multicoat, N/R/X neuere) + zwei Zeichen. */
    static boolean isPaintCode(String code) {
        return PAINTS.containsValue(code) || code.matches("P[BMPNRX][A-Z0-9]{2}");
    }
}
