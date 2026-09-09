package com.household.manager.service;

import com.household.manager.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Umrechnungsfaktor fuer Gas (kWh je m³) in {@code application_settings}, Kategorie
 * UTILITY_PRICING. Der Gaszaehler zaehlt m³, der Gaspreis ist €/kWh — ohne den Faktor
 * waere die Kostenkurve um rund Faktor 10 zu niedrig.
 *
 * <p>Lesen wirft nie: der Serien-Service laeuft bei jedem Tablet-Abruf, ein Tippfehler
 * in der Datenbank darf ihn nicht lahmlegen (Muster {@code PresenceSettingsService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UtilityPricingSettingsService {

    static final String CATEGORY = "UTILITY_PRICING";
    static final String KEY_GAS_KWH_PER_M3 = "gas_kwh_per_m3";
    public static final BigDecimal DEFAULT_GAS_KWH_PER_M3 = new BigDecimal("10.0");
    /**
     * Brennwert × Zustandszahl liegt in Deutschland real bei etwa 8 bis 12; die
     * Schranken sind bewusst grosszuegiger (5 bis 15), um plausible Ausreisser
     * (z. B. bei besonderer Hoehenlage) nicht faelschlich zu verwerfen — nur ein
     * klarer Tippfehler wie 100 soll abgefangen werden.
     */
    public static final BigDecimal MIN_GAS_KWH_PER_M3 = new BigDecimal("5");
    public static final BigDecimal MAX_GAS_KWH_PER_M3 = new BigDecimal("15");

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    public BigDecimal getGasKwhPerM3() {
        String raw;
        try {
            raw = applicationSettings.getSettingsByCategory(CATEGORY).get(KEY_GAS_KWH_PER_M3);
        } catch (Exception ex) {
            log.warn("Gasfaktor konnte nicht gelesen werden, nutze {}", DEFAULT_GAS_KWH_PER_M3, ex);
            return DEFAULT_GAS_KWH_PER_M3;
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_GAS_KWH_PER_M3;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (!isPlausible(value)) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, KEY_GAS_KWH_PER_M3,
                        DEFAULT_GAS_KWH_PER_M3);
                return DEFAULT_GAS_KWH_PER_M3;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, KEY_GAS_KWH_PER_M3,
                    DEFAULT_GAS_KWH_PER_M3);
            return DEFAULT_GAS_KWH_PER_M3;
        }
    }

    public static boolean isPlausible(BigDecimal value) {
        return value.compareTo(MIN_GAS_KWH_PER_M3) >= 0 && value.compareTo(MAX_GAS_KWH_PER_M3) <= 0;
    }

    /**
     * Persistiert ohne eigene Bereichspruefung — die Validierung ist Aufgabe der
     * API-Grenze (Controller), Muster {@code PresenceSettingsService}.
     */
    public void saveGasKwhPerM3(BigDecimal value) {
        String text = value.stripTrailingZeros().toPlainString();
        applicationSettings.saveSettings(CATEGORY, Map.of(KEY_GAS_KWH_PER_M3, text));
        auditService.record("utility-pricing.settings.update", KEY_GAS_KWH_PER_M3 + "=" + text);
    }
}
