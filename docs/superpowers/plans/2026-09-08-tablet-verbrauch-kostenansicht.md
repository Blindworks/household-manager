# Kostenansicht in der Tablet-Verbrauchsübersicht – Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jede Kachel in `/tablet/consumption` lässt sich einzeln zwischen Verbrauch und verbrauchsabhängigen Kosten umschalten; Wasserpreise werden freigeschaltet und der Gas-Umrechnungsfaktor ist pflegbar.

**Architecture:** Das Backend liefert die Kosten im bestehenden Serien-Endpunkt mit (`ConsumptionPoint.cost`, nullable). Ein neuer `MeterCostCalculator` ist die einzige Definition von „was kostet Menge X am Datum Y" (Preis am Ablesedatum, Gas × pflegbarer Faktor aus `application_settings`). Das Frontend schaltet rein lokal um; Kopf und Diagramm folgen dem Modus.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / JUnit 5 + Mockito + AssertJ; Angular 19 standalone / ngx-echarts / Karma-Jasmine.

**Spec:** `docs/superpowers/specs/2026-09-08-tablet-verbrauch-kostenansicht-design.md`

**Build-Hinweise (Windows, Git Bash):**
- Backend: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` vor jedem `mvn`, aus `backend/`. `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` sind lokal rot (keine Test-DB) – vorbestehend, ignorieren.
- Frontend: `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`. Baseline: 3 vorbestehende Fails (`AppComponent` ×2, `HeroComponent should create`), gelegentliche `SmartDeviceListComponent`-Flake.
- Commits: Message per `-F -` mit Heredoc (Anführungszeichen im PowerShell-Here-String zerlegen den Aufruf), Abschluss `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## Dateiübersicht

**Backend – neu**
- `backend/src/main/resources/db/changelog/changes/20260908-0052-allow-water-utility-prices.xml` – Check-Constraint um WATER erweitern
- `backend/src/main/java/com/household/manager/service/UtilityPricingSettingsService.java` – Gasfaktor in `application_settings`
- `backend/src/main/java/com/household/manager/dto/UtilityPricingSettingsDto.java` – `{ gasKwhPerM3 }`
- `backend/src/main/java/com/household/manager/service/MeterCostCalculator.java` – Preiszuordnung + Kostenrechnung
- `backend/src/test/java/com/household/manager/service/UtilityPricingSettingsServiceTest.java`
- `backend/src/test/java/com/household/manager/service/MeterCostCalculatorTest.java`
- `backend/src/test/java/com/household/manager/service/UtilityPriceServiceTest.java`
- `backend/src/test/java/com/household/manager/controller/UtilityPricingSettingsControllerTest.java`

**Backend – ändern**
- `db.changelog-master.xml` – Include
- `service/UtilityPriceService.java` – `validateMeterType` entfernen
- `controller/UtilityPriceController.java` – `GET/PUT /settings`
- `security/SecurityConfig.java` – ADMIN-Matcher für `/v1/utility-prices/settings`
- `dto/ConsumptionPoint.java` – `cost`
- `dto/MeterConsumptionSeries.java` – `currency`
- `service/MeterConsumptionSeriesService.java` – Kosten je Woche, Summe je Periode
- Tests: `SecurityRulesTest`, `MeterConsumptionSeriesServiceTest`, `MeterReadingSeriesControllerTest`

**Frontend – ändern**
- `models/meter-consumption-series.model.ts` – `cost`, `currency`
- `shared/consumption-view.util.ts` (+ spec) – `formatCost`, Wertselektor in `compareToPrevious`
- `pages/tablet-consumption/tablet-consumption.component.{ts,html,scss,spec.ts}` – Modus je Kachel
- `models/utility-price.model.ts`, `services/utility-price.service.ts` – Settings-Aufrufe
- `pages/utility-prices/utility-prices.component.{ts,html}` – Wasser + Gasfaktor-Feld
- `components/utility-price-form/utility-price-form.component.ts` – Wasser
- `CLAUDE.md` – Doku-Absatz

---

### Task 1: Wasserpreise freischalten (Changeset + Service)

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260908-0052-allow-water-utility-prices.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (nach Zeile 131)
- Modify: `backend/src/main/java/com/household/manager/service/UtilityPriceService.java`
- Create: `backend/src/test/java/com/household/manager/service/UtilityPriceServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.service;

import com.household.manager.dto.UtilityPriceRequest;
import com.household.manager.dto.UtilityPriceResponse;
import com.household.manager.model.entity.MeterType;
import com.household.manager.model.entity.UtilityPrice;
import com.household.manager.repository.UtilityPriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UtilityPriceServiceTest {

    @Mock
    private UtilityPriceRepository repository;
    @InjectMocks
    private UtilityPriceService service;

    /**
     * Wasser war bisher ausgeschlossen (nur Strom und Gas). Fuer die Kostenansicht
     * des Tablets braucht auch Wasser einen Preis.
     */
    @Test
    void nimmtEinenWasserpreisAn() {
        when(repository.findOverlappingPrices(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> {
            UtilityPrice p = inv.getArgument(0);
            p.setId(7L);
            return p;
        });
        UtilityPriceRequest request = new UtilityPriceRequest();
        request.setMeterType(MeterType.WATER);
        request.setPrice(new BigDecimal("4.5000"));
        request.setValidFrom(LocalDate.of(2026, 1, 1));

        UtilityPriceResponse response = service.createUtilityPrice(request);

        assertThat(response.getMeterType()).isEqualTo(MeterType.WATER);
        assertThat(response.getId()).isEqualTo(7L);
    }
}
```

Prüfe vorher die Setter-Namen in `dto/UtilityPriceRequest.java` (Lombok `@Data` ⇒ `setMeterType`, `setPrice`, `setValidFrom`); passe den Test an, falls das DTO ein Record ist.

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn -q test -Dtest=UtilityPriceServiceTest
```
Erwartet: FAIL mit `IllegalArgumentException: Utility prices are only supported for ELECTRICITY and GAS`.

- [ ] **Step 3: `validateMeterType` samt Aufrufen entfernen**

In `UtilityPriceService.java`: die Methode `validateMeterType` (Zeilen ~140–153) löschen und die drei Aufrufe in `createUtilityPrice`, `getUtilityPricesByMeterType`, `getCurrentPriceForMeterType` entfernen. Klassen-Javadoc „for electricity and gas" auf „for electricity, gas and water" ändern.

- [ ] **Step 4: Changeset anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <!-- Wasserpreise fuer die Kostenansicht des Wandtablets: der Check aus
         20260206-0005 liess nur Strom und Gas zu. -->
    <changeSet id="20260908-0052" author="household-manager">
        <comment>Allow WATER in utility_prices meter_type check constraint</comment>

        <sql dbms="mariadb,mysql">
            ALTER TABLE utility_prices
            DROP CONSTRAINT chk_utility_price_meter_type
        </sql>

        <sql>
            ALTER TABLE utility_prices
            ADD CONSTRAINT chk_utility_price_meter_type
            CHECK (meter_type IN ('ELECTRICITY', 'GAS', 'WATER'))
        </sql>

        <rollback>
            <sql dbms="mariadb,mysql">
                ALTER TABLE utility_prices
                DROP CONSTRAINT chk_utility_price_meter_type
            </sql>
            <sql dbms="mariadb,mysql">
                ALTER TABLE utility_prices
                ADD CONSTRAINT chk_utility_price_meter_type
                CHECK (meter_type IN ('ELECTRICITY', 'GAS'))
            </sql>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

In `db.changelog-master.xml` nach dem Include von `20260828-0051-generalize-pet-supplies.xml`:
```xml
    <include file="db/changelog/changes/20260908-0052-allow-water-utility-prices.xml"/>
```

- [ ] **Step 5: Tests laufen lassen**

```bash
mvn -q test -Dtest=UtilityPriceServiceTest
```
Erwartet: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/service/UtilityPriceService.java backend/src/test/java/com/household/manager/service/UtilityPriceServiceTest.java
git commit -F - <<'EOF'
feat(prices): Wasserpreise zulassen

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 2: `UtilityPricingSettingsService` (Gasfaktor)

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/UtilityPricingSettingsService.java`
- Create: `backend/src/test/java/com/household/manager/service/UtilityPricingSettingsServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben**

```java
package com.household.manager.service;

import com.household.manager.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UtilityPricingSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private UtilityPricingSettingsService service;

    @Test
    void ohneEintragGiltDerDefault() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING")).thenReturn(Map.of());
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.0");
    }

    @Test
    void gespeicherterWertWirdGelesen() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING"))
                .thenReturn(Map.of("gas_kwh_per_m3", "10.63"));
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.63");
    }

    @Test
    void unlesbarerWertFaelltAufDenDefaultZurueck() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING"))
                .thenReturn(Map.of("gas_kwh_per_m3", "zehn"));
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.0");
    }

    /** Ein Faktor 100 waere ein Tippfehler und verzehnfachte still die Gaskosten. */
    @Test
    void unplausiblerWertFaelltAufDenDefaultZurueck() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING"))
                .thenReturn(Map.of("gas_kwh_per_m3", "100"));
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.0");
    }

    /** Der Serien-Service laeuft bei jedem Tablet-Abruf; ein DB-Fehler darf ihn nicht kippen. */
    @Test
    void leseFehlerWirftNicht() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING"))
                .thenThrow(new RuntimeException("db weg"));
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.0");
    }

    @Test
    void speichernSchreibtUndAuditiert() {
        service.saveGasKwhPerM3(new BigDecimal("11.2"));
        verify(applicationSettings).saveSettings("UTILITY_PRICING", Map.of("gas_kwh_per_m3", "11.2"));
        verify(auditService).record(eq("utility-pricing.settings.update"), eq("gas_kwh_per_m3=11.2"));
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

```bash
mvn -q test -Dtest=UtilityPricingSettingsServiceTest
```
Erwartet: Compile-Fehler „cannot find symbol UtilityPricingSettingsService".

- [ ] **Step 3: Service implementieren**

```java
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
    /** Brennwert × Zustandszahl liegt in Deutschland zwischen etwa 8 und 12. */
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
```

- [ ] **Step 4: Tests laufen lassen**

```bash
mvn -q test -Dtest=UtilityPricingSettingsServiceTest
```
Erwartet: 6 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/UtilityPricingSettingsService.java backend/src/test/java/com/household/manager/service/UtilityPricingSettingsServiceTest.java
git commit -F - <<'EOF'
feat(prices): pflegbarer Gas-Umrechnungsfaktor kWh je m3

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 3: Settings-Endpunkt + Security

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/UtilityPricingSettingsDto.java`
- Modify: `backend/src/main/java/com/household/manager/controller/UtilityPriceController.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java` (ADMIN-Block, Zeile ~151)
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`
- Create: `backend/src/test/java/com/household/manager/controller/UtilityPricingSettingsControllerTest.java`

- [ ] **Step 1: Security-Tests schreiben (in `SecurityRulesTest`, neben den `presence/settings`-Tests ab Zeile ~608)**

```java
    /**
     * /v1/utility-prices/settings liegt unter /v1/utility-prices/**, dessen GET fuer
     * KIOSK offen ist. Der methodenlose ADMIN-Matcher muss VOR dieser Zeile stehen,
     * sonst laese das Wandtablet den Gasfaktor mit.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDenGasfaktorNichtLesen() throws Exception {
        mockMvc.perform(get("/v1/utility-prices/settings")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfDenGasfaktorNichtSchreiben() throws Exception {
        mockMvc.perform(put("/v1/utility-prices/settings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": 10.5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminDarfDenGasfaktorLesenUndSchreiben() throws Exception {
        // Kein UtilityPriceController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst.
        mockMvc.perform(get("/v1/utility-prices/settings")).andExpect(status().isNotFound());
        mockMvc.perform(put("/v1/utility-prices/settings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": 10.5}"))
                .andExpect(status().isNotFound());
    }

    /** Die generische GET-Regel fuer Preise bleibt: das Wandtablet liest weiter Preise. */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfPreiseWeiterLesen() throws Exception {
        mockMvc.perform(get("/v1/utility-prices")).andExpect(status().isNotFound());
    }
```

Prüfe, ob `SecurityRulesTest` den `UtilityPriceController` im Slice hat (dann 200 mit Mock statt 404) — passe die Erwartung entsprechend an (`isOk` bzw. `isNotFound`), Muster der Nachbartests.

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

```bash
mvn -q test -Dtest=SecurityRulesTest
```
Erwartet: `kioskDarfDenGasfaktorNichtLesen` FAIL (404 statt 403), `memberDarfDenGasfaktorNichtSchreiben` FAIL (404 statt 403).

- [ ] **Step 3: Matcher in `SecurityConfig` ergänzen**

Im ADMIN-Block die Zeile
```java
"/v1/tractive/home-settings", "/v1/presence/settings").hasRole("ADMIN")
```
ändern zu
```java
"/v1/tractive/home-settings", "/v1/presence/settings",
// Gasfaktor: liegt unter /v1/utility-prices/**, dessen GET weiter unten KIOSK
// ist — muss deshalb hier vor dieser Regel stehen.
"/v1/utility-prices/settings").hasRole("ADMIN")
```

- [ ] **Step 4: Security-Tests laufen lassen**

```bash
mvn -q test -Dtest=SecurityRulesTest
```
Erwartet: PASS.

- [ ] **Step 5: Controller-Test schreiben**

```java
package com.household.manager.controller;

import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import com.household.manager.service.UtilityPriceService;
import com.household.manager.service.UtilityPricingSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UtilityPriceController.class)
@AutoConfigureMockMvc(addFilters = false)
class UtilityPricingSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private UtilityPriceService utilityPriceService;
    @MockitoBean
    private UtilityPricingSettingsService settingsService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    @Test
    void liefertDenGasfaktor() throws Exception {
        when(settingsService.getGasKwhPerM3()).thenReturn(new BigDecimal("10.63"));
        mockMvc.perform(get("/v1/utility-prices/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gasKwhPerM3").value(10.63));
    }

    @Test
    void speichertEinenPlausiblenGasfaktor() throws Exception {
        when(settingsService.getGasKwhPerM3()).thenReturn(new BigDecimal("11.2"));
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": 11.2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gasKwhPerM3").value(11.2));
        verify(settingsService).saveGasKwhPerM3(new BigDecimal("11.2"));
    }

    @Test
    void lehntFehlendenWertAb() throws Exception {
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(settingsService, never()).saveGasKwhPerM3(any());
    }

    /** Jackson macht aus dem String "NaN" klaglos ein Double.NaN; jeder Vergleich damit ist false. */
    @Test
    void lehntNaNAb() throws Exception {
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": \"NaN\"}"))
                .andExpect(status().isBadRequest());
        verify(settingsService, never()).saveGasKwhPerM3(any());
    }

    @Test
    void lehntWerteAusserhalbDesBereichsAb() throws Exception {
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": 100}"))
                .andExpect(status().isBadRequest());
        verify(settingsService, never()).saveGasKwhPerM3(any());
    }
}
```

Die `@MockitoBean`-Liste an `MeterReadingSeriesControllerTest` angleichen (dort stehen alle Beans, die der Slice braucht — kopiere die vollständige Liste von dort).

- [ ] **Step 6: Test laufen lassen, muss fehlschlagen**

```bash
mvn -q test -Dtest=UtilityPricingSettingsControllerTest
```
Erwartet: Compile-Fehler (DTO fehlt) bzw. 404.

- [ ] **Step 7: DTO und Endpunkte implementieren**

`dto/UtilityPricingSettingsDto.java`:
```java
package com.household.manager.dto;

/** Pflegbare Preis-Einstellungen. {@code gasKwhPerM3}: Brennwert × Zustandszahl. */
public record UtilityPricingSettingsDto(Double gasKwhPerM3) {
}
```

In `UtilityPriceController`: Feld `private final UtilityPricingSettingsService settingsService;` ergänzen und die Endpunkte:
```java
    @GetMapping("/settings")
    public ResponseEntity<UtilityPricingSettingsDto> getSettings() {
        return ResponseEntity.ok(currentSettings());
    }

    /**
     * Die Bereichspruefung braucht {@code Double.isFinite}: Jackson erzeugt aus dem
     * String "NaN" klaglos ein Double.NaN, und jeder Vergleich damit ist false.
     */
    @PutMapping("/settings")
    public ResponseEntity<UtilityPricingSettingsDto> updateSettings(
            @RequestBody UtilityPricingSettingsDto request) {
        Double value = request.gasKwhPerM3();
        if (value == null || !Double.isFinite(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Der Gasfaktor fehlt.");
        }
        BigDecimal factor = BigDecimal.valueOf(value);
        if (!UtilityPricingSettingsService.isPlausible(factor)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Gasfaktor muss zwischen " + UtilityPricingSettingsService.MIN_GAS_KWH_PER_M3
                            + " und " + UtilityPricingSettingsService.MAX_GAS_KWH_PER_M3 + " kWh je m³ liegen.");
        }
        settingsService.saveGasKwhPerM3(factor);
        return ResponseEntity.ok(currentSettings());
    }

    private UtilityPricingSettingsDto currentSettings() {
        return new UtilityPricingSettingsDto(settingsService.getGasKwhPerM3().doubleValue());
    }
```
Imports: `java.math.BigDecimal`, `org.springframework.web.server.ResponseStatusException`, `com.household.manager.dto.UtilityPricingSettingsDto`, `com.household.manager.service.UtilityPricingSettingsService`.

**Achtung Pfadkonflikt:** Der Controller hat vermutlich `GET /{meterType}`. `/settings` ist ein literales Segment und gewinnt bei Spring gegen die Pfadvariable — der Test `liefertDenGasfaktor` hält das fest (sonst käme 400 „No enum constant MeterType.settings").

- [ ] **Step 8: Tests laufen lassen**

```bash
mvn -q test -Dtest='UtilityPricingSettingsControllerTest,SecurityRulesTest'
```
Erwartet: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/UtilityPricingSettingsDto.java backend/src/main/java/com/household/manager/controller/UtilityPriceController.java backend/src/main/java/com/household/manager/security/SecurityConfig.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java backend/src/test/java/com/household/manager/controller/UtilityPricingSettingsControllerTest.java
git commit -F - <<'EOF'
feat(prices): Endpunkt fuer den Gasfaktor (ADMIN)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 4: `MeterCostCalculator`

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/MeterCostCalculator.java`
- Create: `backend/src/test/java/com/household/manager/service/MeterCostCalculatorTest.java`

- [ ] **Step 1: Failing Tests schreiben**

```java
package com.household.manager.service;

import com.household.manager.model.entity.MeterType;
import com.household.manager.model.entity.UtilityPrice;
import com.household.manager.repository.UtilityPriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeterCostCalculatorTest {

    @Mock
    private UtilityPriceRepository prices;
    @Mock
    private UtilityPricingSettingsService settings;

    private MeterCostCalculator calculator() {
        return new MeterCostCalculator(prices, settings);
    }

    private static UtilityPrice price(MeterType type, String price, LocalDate from, LocalDate to) {
        return UtilityPrice.builder().meterType(type).price(new BigDecimal(price))
                .validFrom(from).validTo(to).build();
    }

    @Test
    void bewertetMitDemAmDatumGueltigenPreis() {
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.ELECTRICITY)).thenReturn(List.of(
                price(MeterType.ELECTRICITY, "0.40", LocalDate.of(2026, 7, 1), null),
                price(MeterType.ELECTRICITY, "0.30", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1))));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.ELECTRICITY);

        assertThat(book.costOf(new BigDecimal("10"), LocalDate.of(2026, 6, 30)))
                .contains(new BigDecimal("3.00"));
        assertThat(book.costOf(new BigDecimal("10"), LocalDate.of(2026, 7, 1)))
                .contains(new BigDecimal("4.00"));
    }

    @Test
    void ohnePassendenPreisKeineKosten() {
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.WATER)).thenReturn(List.of(
                price(MeterType.WATER, "4.50", LocalDate.of(2026, 7, 1), null)));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.WATER);

        assertThat(book.costOf(new BigDecimal("2"), LocalDate.of(2026, 6, 1))).isEmpty();
    }

    /** validTo ist exklusiv, ein offenes Ende (null) gilt unbegrenzt. */
    @Test
    void validToIstExklusiv() {
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.WATER)).thenReturn(List.of(
                price(MeterType.WATER, "4.50", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1))));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.WATER);

        assertThat(book.costOf(new BigDecimal("1"), LocalDate.of(2026, 6, 30))).isPresent();
        assertThat(book.costOf(new BigDecimal("1"), LocalDate.of(2026, 7, 1))).isEmpty();
    }

    /** Gas: der Zaehler zaehlt m³, der Preis ist €/kWh — dazwischen steht der Faktor. */
    @Test
    void rechnetGasUeberDenKwhFaktorUm() {
        when(settings.getGasKwhPerM3()).thenReturn(new BigDecimal("10.5"));
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.GAS)).thenReturn(List.of(
                price(MeterType.GAS, "0.10", LocalDate.of(2026, 1, 1), null)));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.GAS);

        // 2 m³ × 10,5 kWh/m³ × 0,10 €/kWh = 2,10 €
        assertThat(book.costOf(new BigDecimal("2"), LocalDate.of(2026, 8, 1)))
                .contains(new BigDecimal("2.100"));
    }

    /** Ein Fehler beim Preisladen darf die Verbrauchsserie nicht mitreissen. */
    @Test
    void ladeFehlerErgibtEinLeeresPreisbuch() {
        when(prices.findByMeterTypeOrderByValidFromDesc(any())).thenThrow(new RuntimeException("db weg"));

        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.ELECTRICITY);

        assertThat(book.costOf(BigDecimal.ONE, LocalDate.of(2026, 8, 1))).isEmpty();
    }

    /** Der Preis wird je Typ EINMAL geladen, nicht je Woche. */
    @Test
    void laedtPreiseEinmalJeBuch() {
        when(prices.findByMeterTypeOrderByValidFromDesc(MeterType.ELECTRICITY)).thenReturn(List.of(
                price(MeterType.ELECTRICITY, "0.40", LocalDate.of(2026, 1, 1), null)));
        MeterCostCalculator.PriceBook book = calculator().priceBookFor(MeterType.ELECTRICITY);

        book.costOf(BigDecimal.ONE, LocalDate.of(2026, 8, 1));
        book.costOf(BigDecimal.ONE, LocalDate.of(2026, 8, 8));

        org.mockito.Mockito.verify(prices, org.mockito.Mockito.times(1))
                .findByMeterTypeOrderByValidFromDesc(MeterType.ELECTRICITY);
    }
}
```

Hinweis: `costOf` liefert **unrundet** (die Rundung passiert erst nach der Perioden-Summe im Serien-Service). `contains(new BigDecimal("3.00"))` vergleicht per `equals` inkl. Scale — `10 × 0.30` ergibt Scale 2, `2 × 10.5 × 0.10` Scale 3. Wenn ein Vergleich an der Scale scheitert, `usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)` verwenden statt die Implementierung zu runden.

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

```bash
mvn -q test -Dtest=MeterCostCalculatorTest
```
Erwartet: Compile-Fehler.

- [ ] **Step 3: Implementieren**

```java
package com.household.manager.service;

import com.household.manager.model.entity.MeterType;
import com.household.manager.model.entity.UtilityPrice;
import com.household.manager.repository.UtilityPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Einzige Definition von "was kostet Menge X eines Zaehlertyps am Datum Y".
 *
 * <p>Die Preise eines Typs werden EINMAL je {@link PriceBook} geladen und in Java
 * zugeordnet — bei 24 Monaten sind das bis zu 104 Ablesewochen, eine Query je Woche
 * waere unnoetig. Gas zaehlt m³, der Gaspreis ist €/kWh: dazwischen steht der
 * pflegbare Faktor aus {@link UtilityPricingSettingsService}.
 *
 * <p>Nur der Arbeitspreis: Grundgebuehr, Netzentgelte und Steuern sind nicht Teil
 * des Modells — das Ergebnis sind verbrauchsabhaengige Kosten, kein Rechnungsbetrag.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MeterCostCalculator {

    private final UtilityPriceRepository priceRepository;
    private final UtilityPricingSettingsService settings;

    /**
     * Preisbuch eines Typs. Ein Ladefehler ergibt ein leeres Buch (alle Kosten
     * {@code empty}) statt einer Ausnahme — die Verbrauchsserie kommt trotzdem.
     */
    public PriceBook priceBookFor(MeterType type) {
        try {
            List<UtilityPrice> prices = priceRepository.findByMeterTypeOrderByValidFromDesc(type);
            BigDecimal unitFactor = type == MeterType.GAS ? settings.getGasKwhPerM3() : BigDecimal.ONE;
            return new PriceBook(prices, unitFactor);
        } catch (Exception e) {
            log.warn("Preise fuer {} konnten nicht geladen werden, Kosten entfallen: {}", type, e.toString());
            return new PriceBook(List.of(), BigDecimal.ONE);
        }
    }

    /** Alle Preise eines Typs plus Einheitenfaktor; unveraenderlich. */
    public static final class PriceBook {
        private final List<UtilityPrice> prices;
        private final BigDecimal unitFactor;

        PriceBook(List<UtilityPrice> prices, BigDecimal unitFactor) {
            this.prices = List.copyOf(prices);
            this.unitFactor = unitFactor;
        }

        /** Kosten der Menge zum am Datum gueltigen Preis, unrundet; leer ohne Preis. */
        public Optional<BigDecimal> costOf(BigDecimal amount, LocalDate date) {
            return priceAt(date).map(price -> amount.multiply(unitFactor).multiply(price));
        }

        private Optional<BigDecimal> priceAt(LocalDate date) {
            return prices.stream()
                    .filter(p -> !p.getValidFrom().isAfter(date))
                    .filter(p -> p.getValidTo() == null || p.getValidTo().isAfter(date))
                    .map(UtilityPrice::getPrice)
                    .findFirst();
        }
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

```bash
mvn -q test -Dtest=MeterCostCalculatorTest
```
Erwartet: 6 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/MeterCostCalculator.java backend/src/test/java/com/household/manager/service/MeterCostCalculatorTest.java
git commit -F - <<'EOF'
feat(consumption): MeterCostCalculator als einzige Definition der Kostenrechnung

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 5: Kosten im Serien-Endpunkt

**Files:**
- Modify: `backend/src/main/java/com/household/manager/dto/ConsumptionPoint.java`
- Modify: `backend/src/main/java/com/household/manager/dto/MeterConsumptionSeries.java`
- Modify: `backend/src/main/java/com/household/manager/service/MeterConsumptionSeriesService.java`
- Modify: `backend/src/test/java/com/household/manager/service/MeterConsumptionSeriesServiceTest.java`
- Modify: `backend/src/test/java/com/household/manager/controller/MeterReadingSeriesControllerTest.java` (Zeilen 50–52)

- [ ] **Step 1: DTOs erweitern**

`ConsumptionPoint`:
```java
/**
 * ...
 * @param cost        Kosten der Periode in EUR (Arbeitspreis × Menge, 2 Nachkommastellen);
 *                    null, wenn fuer mindestens eine beitragende Ablesewoche kein Preis
 *                    hinterlegt ist — eine Teilsumme saehe aus wie ein billiger Monat
 */
public record ConsumptionPoint(
        LocalDate periodStart,
        String label,
        BigDecimal consumption,
        boolean estimated,
        BigDecimal cost
) {
}
```

`MeterConsumptionSeries`: Parameter `String currency` nach `unit` einfügen (Javadoc: `@param currency Waehrung der Kosten, immer "EUR"`).

`MeterReadingSeriesControllerTest` Zeilen 50–52 auf die neuen Konstruktoren anpassen:
```java
new MeterConsumptionSeries(MeterType.ELECTRICITY, "kWh", "EUR", List.of(
        new ConsumptionPoint(LocalDate.of(2026, 8, 21), "KW 34",
                new BigDecimal("38.1"), false, new BigDecimal("11.43"))))
```
und eine Erwartung ergänzen: `.andExpect(jsonPath("$[0].currency").value("EUR"))` sowie `.andExpect(jsonPath("$[0].points[0].cost").value(11.43))`.

- [ ] **Step 2: Failing Tests im Serien-Service-Test schreiben**

Setup ändern: Service bekommt den Calculator.
```java
    @Mock
    private MeterCostCalculator costCalculator;
    @Mock
    private MeterCostCalculator.PriceBook priceBook;

    @BeforeEach
    void setUp() {
        service = new MeterConsumptionSeriesService(repository, costCalculator, () -> TODAY);
        when(repository.findByMeterTypeOrderByReadingDateAsc(any())).thenReturn(List.of());
        when(costCalculator.priceBookFor(any())).thenReturn(priceBook);
        when(priceBook.costOf(any(), any())).thenReturn(Optional.empty());
    }
```
`PriceBook` ist `final` — Mockito 5 (Spring Boot 3.4) mockt finale Klassen per Inline-Mock-Maker standardmäßig. Falls das Projekt einen älteren Mock-Maker nutzt (Fehler „Cannot mock final class"), `final` an `PriceBook` entfernen.

Mit `MockitoExtension` strict stubs: `lenient()` für die beiden Default-Stubs im `setUp`, sonst meckert Mockito bei Tests, die sie nicht benutzen (`isoliertFehlerJeZaehlertyp` etc.).

Neue Tests:
```java
    /** Kosten je Ablesewoche zum Preis am Ablesedatum, gerundet auf 2 Nachkommastellen. */
    @Test
    void bepreistJedeAblesewocheAmAblesedatum() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1038", false));
        when(priceBook.costOf(new BigDecimal("38"), LocalDate.of(2026, 8, 14)))
                .thenReturn(Optional.of(new BigDecimal("11.4266")));

        MeterConsumptionSeries series = strom(ConsumptionRange.WEEKS_8);

        assertThat(series.currency()).isEqualTo("EUR");
        assertThat(series.points().get(0).cost()).isEqualByComparingTo("11.43");
        assertThat(series.points().get(0).cost().scale()).isEqualTo(2);
    }

    @Test
    void ohnePreisBleibenDieKostenLeer() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1038", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.WEEKS_8);

        assertThat(series.points().get(0).cost()).isNull();
        assertThat(series.points().get(0).consumption()).isEqualByComparingTo("38");
    }

    /** Monatsregel: fehlt EINER Woche der Preis, entfaellt der ganze Monatswert. */
    @Test
    void monatOhneVollstaendigePreiseHatKeineKosten() {
        stromAblesungen(
                reading(LocalDate.of(2026, 7, 31), "1000", false),
                reading(LocalDate.of(2026, 8, 7), "1010", false),
                reading(LocalDate.of(2026, 8, 14), "1020", false));
        when(priceBook.costOf(new BigDecimal("10"), LocalDate.of(2026, 8, 7)))
                .thenReturn(Optional.of(new BigDecimal("3.00")));
        // 14.08. bleibt ohne Preis (Default-Stub)

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);
        ConsumptionPoint august = series.points().get(series.points().size() - 1);

        assertThat(august.consumption()).isEqualByComparingTo("20");
        assertThat(august.cost()).isNull();
    }

    /** Rundung erst nach der Summe: 1,005 + 1,005 = 2,01, nicht 1,01 + 1,01 = 2,02. */
    @Test
    void rundetMonatskostenErstNachDerSumme() {
        stromAblesungen(
                reading(LocalDate.of(2026, 7, 31), "1000", false),
                reading(LocalDate.of(2026, 8, 7), "1010", false),
                reading(LocalDate.of(2026, 8, 14), "1020", false));
        when(priceBook.costOf(new BigDecimal("10"), LocalDate.of(2026, 8, 7)))
                .thenReturn(Optional.of(new BigDecimal("1.005")));
        when(priceBook.costOf(new BigDecimal("10"), LocalDate.of(2026, 8, 14)))
                .thenReturn(Optional.of(new BigDecimal("1.005")));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);
        ConsumptionPoint august = series.points().get(series.points().size() - 1);

        assertThat(august.cost()).isEqualByComparingTo("2.01");
    }

    /** Der Calculator wird je Serie EINMAL befragt, nicht je Woche. */
    @Test
    void holtDasPreisbuchEinmalJeSerie() {
        stromAblesungen(
                reading(LocalDate.of(2026, 7, 31), "1000", false),
                reading(LocalDate.of(2026, 8, 7), "1010", false),
                reading(LocalDate.of(2026, 8, 14), "1020", false));

        service.getSeries(ConsumptionRange.WEEKS_8);

        verify(costCalculator, times(1)).priceBookFor(MeterType.ELECTRICITY);
    }
```
Imports ergänzen: `ConsumptionPoint`, `Optional`, `verify`, `times`, `lenient`.

Spring-Instanziierungs-Test anpassen:
```java
            context.registerBean(MeterReadingRepository.class, () -> repository);
            context.registerBean(MeterCostCalculator.class, () -> costCalculator);
            context.registerBean(MeterConsumptionSeriesService.class);
```

- [ ] **Step 3: Test laufen lassen, muss fehlschlagen**

```bash
mvn -q test -Dtest=MeterConsumptionSeriesServiceTest
```
Erwartet: Compile-Fehler (Konstruktor).

- [ ] **Step 4: Service umbauen**

Konstruktoren:
```java
    private final MeterReadingRepository repository;
    private final MeterCostCalculator costCalculator;
    private final Supplier<LocalDate> today;

    @Autowired
    public MeterConsumptionSeriesService(MeterReadingRepository repository,
                                         MeterCostCalculator costCalculator) {
        this(repository, costCalculator, LocalDate::now);
    }

    MeterConsumptionSeriesService(MeterReadingRepository repository,
                                  MeterCostCalculator costCalculator,
                                  Supplier<LocalDate> today) {
        this.repository = repository;
        this.costCalculator = costCalculator;
        this.today = today;
    }
```

`seriesFor`: `new MeterConsumptionSeries(type, unitOf(type), CURRENCY, points)` mit `private static final String CURRENCY = "EUR";`.

`points()`: vor der Schleife `MeterCostCalculator.PriceBook priceBook = costCalculator.priceBookFor(type);`, und in der Schleife nach der `from`-Prüfung:
```java
            BigDecimal cost = priceBook.costOf(consumption, date).orElse(null);
            weekly.add(new ConsumptionPoint(date, weekLabel(date), consumption,
                    current.isEstimated(), cost));
```

`mergeGroup`: Kosten summieren, `null` propagiert, Rundung nach der Summe:
```java
        BigDecimal cost = sumCosts(group);
        ...
        return new ConsumptionPoint(first.periodStart(), first.label(), consumption, estimated, cost);
        ...
        return new ConsumptionPoint(periodStart, MONTH_LABEL.format(periodStart), consumption, estimated, cost);
```
```java
    /**
     * Summe der Kosten einer Periode, gerundet erst NACH der Summe. Fehlt einem
     * Mitglied der Preis, ist die Summe null: eine Teilsumme saehe aus wie ein
     * billiger Monat.
     */
    private static BigDecimal sumCosts(List<ConsumptionPoint> group) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ConsumptionPoint point : group) {
            if (point.cost() == null) {
                return null;
            }
            sum = sum.add(point.cost());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }
```
Import `java.math.RoundingMode`. Klassen-Javadoc um einen Satz ergänzen: „Kosten je Ablesewoche zum Preis am Ablesedatum (`MeterCostCalculator`), Monatsbalken summieren; fehlt einer Woche der Preis, entfällt der Monatswert."

- [ ] **Step 5: Alle betroffenen Tests laufen lassen**

```bash
mvn -q test -Dtest='MeterConsumptionSeriesServiceTest,MeterReadingSeriesControllerTest,MeterCostCalculatorTest'
```
Erwartet: PASS.

- [ ] **Step 6: Gesamten Backend-Testlauf**

```bash
mvn -q test
```
Erwartet: nur die zwei vorbestehenden DB-Fails (`contextLoads`, `HealthControllerTest`).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto backend/src/main/java/com/household/manager/service/MeterConsumptionSeriesService.java backend/src/test/java/com/household/manager/service/MeterConsumptionSeriesServiceTest.java backend/src/test/java/com/household/manager/controller/MeterReadingSeriesControllerTest.java
git commit -F - <<'EOF'
feat(consumption): Kosten je Balken im Serien-Endpunkt

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 6: Frontend-Modell und Util (`formatCost`, Wertselektor)

**Files:**
- Modify: `frontend/src/app/models/meter-consumption-series.model.ts`
- Modify: `frontend/src/app/shared/consumption-view.util.ts`
- Modify: `frontend/src/app/shared/consumption-view.util.spec.ts`

- [ ] **Step 1: Modell erweitern**

In `ConsumptionPoint`:
```ts
  /**
   * Kosten der Periode in der Waehrung der Serie (Arbeitspreis × Menge), null wenn
   * fuer mindestens eine beitragende Ablesewoche kein Preis hinterlegt ist.
   */
  readonly cost: number | null;
```
In `MeterConsumptionSeries` nach `unit`:
```ts
  /** Waehrung der Kosten, heute immer "EUR". */
  readonly currency: string;
```

- [ ] **Step 2: Failing Tests schreiben (in `consumption-view.util.spec.ts`)**

Den `point`-Helper erweitern:
```ts
  function point(consumption: number, periodStart = '2026-08-21', cost: number | null = null): ConsumptionPoint {
    return { periodStart, label: 'KW 34', consumption, estimated: false, cost };
  }
```
Neue Tests:
```ts
  describe('compareToPrevious mit Wertselektor', () => {
    it('vergleicht auf Kostenbasis, wenn ein Selektor uebergeben wird', () => {
      const points = [point(10, WEEK_A, 4), point(20, WEEK_B, 5)];
      expect(compareToPrevious(points, 'WEEK', p => p.cost)).toBe('+25 % ggü. Vorwoche');
    });

    it('gibt nichts zurueck, wenn der gewaehlte Wert fehlt', () => {
      const points = [point(10, WEEK_A, null), point(20, WEEK_B, 5)];
      expect(compareToPrevious(points, 'WEEK', p => p.cost)).toBeNull();
    });
  });

  describe('formatCost', () => {
    it('zeigt zwei Nachkommastellen mit Euro-Zeichen', () => {
      expect(formatCost(12.4, 'EUR')).toBe('12,40 €');
    });

    it('zeigt bei fehlendem Wert einen Platzhalter', () => {
      expect(formatCost(null, 'EUR')).toBe('–');
    });
  });
```
Import `formatCost` ergänzen. Alle bestehenden `point(...)`-Aufrufe bleiben gültig (Default `cost: null`).

- [ ] **Step 3: Test laufen lassen, muss fehlschlagen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/consumption-view.util.spec.ts'
```
Erwartet: Compile-Fehler (`formatCost` fehlt, drittes Argument unbekannt).

- [ ] **Step 4: Util erweitern**

`compareToPrevious` verallgemeinern:
```ts
/** Liest den zu vergleichenden Wert eines Balkens; null = kein Wert. */
export type PointValueSelector = (point: ConsumptionPoint) => number | null;

const CONSUMPTION_OF: PointValueSelector = p => p.consumption;

export function compareToPrevious(
  points: readonly ConsumptionPoint[],
  resolution: ConsumptionResolution,
  valueOf: PointValueSelector = CONSUMPTION_OF
): string | null {
  if (points.length < 2) {
    return null;
  }
  const previousPoint = points[points.length - 2];
  const currentPoint = points[points.length - 1];
  const previous = valueOf(previousPoint);
  const current = valueOf(currentPoint);
  if (previous === null || current === null || previous === 0) {
    return null;
  }
  const percent = Math.round(((current - previous) / previous) * 100);
  const sign = percent >= 0 ? '+' : '';
  const reference = isAdjacent(previousPoint, currentPoint, resolution)
    ? PREVIOUS_LABEL[resolution]
    : GAPPED_LABEL;
  return `${sign}${percent} % ggü. ${reference}`;
}
```
Neue Funktion:
```ts
/** Kostenwert mit Waehrungszeichen, zwei Nachkommastellen, deutsches Komma. */
export function formatCost(value: number | null, currency: string): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '–';
  }
  return value.toLocaleString('de-DE', { style: 'currency', currency });
}
```
`toLocaleString('de-DE', { style: 'currency', currency: 'EUR' })` liefert `12,40 €` (mit geschütztem Leerzeichen U+00A0). Der Test vergleicht mit `'12,40 €'` — prüfe, ob Karma/Chrome das geschützte Leerzeichen liefert; wenn ja, im Test `'12,40 €'` erwarten.

- [ ] **Step 5: Tests laufen lassen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/consumption-view.util.spec.ts'
```
Erwartet: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/meter-consumption-series.model.ts frontend/src/app/shared/consumption-view.util.ts frontend/src/app/shared/consumption-view.util.spec.ts
git commit -F - <<'EOF'
feat(tablet): Kostenformat und Wertselektor fuer den Vorperiodenvergleich

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 7: Tablet-Kachel mit Modus-Umschalter

**Files:**
- Modify: `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.ts`
- Modify: `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.html`
- Modify: `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.scss`
- Modify: `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.spec.ts`

- [ ] **Step 1: Fixtures im Spec erweitern und failing Tests schreiben**

Fixtures: `strom` bekommt `currency: 'EUR'` und Punkte mit `cost: 10.2` bzw. `cost: 11.43`; `wasser` bekommt `currency: 'EUR'` und `cost: null`.

Neue Tests (nach `meldet, ob ueberhaupt ein Schaetzwert im Bild ist`):
```ts
  it('startet jede Kachel im Verbrauchsmodus', () => {
    expect(component.tiles.every(t => t.mode === 'consumption')).toBeTrue();
  });

  it('zeigt im Kostenmodus Kopfwert und Vergleich in Euro', () => {
    component.setMode(MeterType.ELECTRICITY, 'cost');

    const tile = component.tiles[0];
    expect(tile.mode).toBe('cost');
    expect(tile.currentLabel).toContain('11,43');
    expect(tile.currentLabel).toContain('€');
    expect(tile.comparison).toBe('+12 % ggü. Vorwoche');
  });

  it('beschriftet die Y-Achse im Kostenmodus in Euro', () => {
    component.setMode(MeterType.ELECTRICITY, 'cost');
    const options = component.tiles[0].options as { yAxis: { axisLabel: { formatter: string } } };
    expect(options.yAxis.axisLabel.formatter).toBe('{value} €');
  });

  it('laesst im Kostenmodus Balken ohne Preis weg und nennt ihre Zahl', () => {
    component.setMode(MeterType.WATER, 'cost');
    const tile = component.tiles[1];
    const options = tile.options as { series: { data: unknown[] }[] };
    expect(options.series[0].data.length).toBe(0);
    expect(tile.missingPriceCount).toBe(1);
    expect(tile.hasNoCost).toBeTrue();
  });

  it('zeigt bei fehlenden Preisen "Kein Preis hinterlegt" statt des Diagramms', () => {
    component.setMode(MeterType.WATER, 'cost');
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('.tablet-consumption__card');
    expect(cards[1].textContent).toContain('Kein Preis hinterlegt');
    expect(cards[1].querySelector('.tablet-consumption__chart')).toBeNull();
  });

  it('laesst den Modus einer Kachel den Refresh ueberleben', () => {
    component.setMode(MeterType.ELECTRICITY, 'cost');
    component.reload();
    expect(component.tiles[0].mode).toBe('cost');
    expect(component.tiles[1].mode).toBe('consumption');
  });

  it('laesst den Modus einer Kachel den Zeitraumwechsel ueberleben', () => {
    component.setMode(MeterType.ELECTRICITY, 'cost');
    component.setRange('WEEKS_8');
    expect(component.tiles[0].mode).toBe('cost');
  });

  it('schaltet ueber die Knoepfe im Kachelkopf um', () => {
    const card = fixture.nativeElement.querySelector('.tablet-consumption__card') as HTMLElement;
    const buttons = card.querySelectorAll('.tablet-consumption__mode-btn');
    expect(buttons.length).toBe(2);
    (buttons[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(component.tiles[0].mode).toBe('cost');
    expect(buttons[1].classList).toContain('tablet-consumption__mode-btn--active');
  });

  it('schaltet ohne Nachladen um', () => {
    serviceSpy.getSeries.calls.reset();
    component.setMode(MeterType.ELECTRICITY, 'cost');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });
```
Vergleichsrechnung: `(11.43 − 10.2) / 10.2 = 12,06 %` ⇒ `+12 %`.

Der Höhenketten-Test (`gibt den Graphen die Bildschirmhoehe ...`) bleibt unverändert und muss weiter grün sein — der Umschalter sitzt im Kopf mit `flex: 0 0 auto`.

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-consumption.component.spec.ts'
```
Erwartet: Compile-Fehler (`setMode`, `mode`, `missingPriceCount`, `hasNoCost` fehlen).

- [ ] **Step 3: Komponente umbauen**

Typen und Tile:
```ts
/** Was eine Kachel zeigt: Menge oder verbrauchsabhaengige Kosten. */
export type TileMode = 'consumption' | 'cost';

interface ModeOption {
  readonly value: TileMode;
  readonly label: string;
}

interface ConsumptionTile {
  readonly key: string;
  readonly meterType: MeterType;
  readonly name: string;
  readonly mode: TileMode;
  readonly currentLabel: string;
  readonly comparison: string | null;
  readonly hasEstimated: boolean;
  /** Im Kostenmodus: Balken, die mangels Preis entfallen sind. */
  readonly missingPriceCount: number;
  /** Im Kostenmodus: kein einziger Balken hat einen Preis. */
  readonly hasNoCost: boolean;
  readonly options: Record<string, unknown>;
}
```
Felder:
```ts
  readonly modes: ModeOption[] = [
    { value: 'consumption', label: 'Verbrauch' },
    { value: 'cost', label: 'Kosten' }
  ];
  /** Zuletzt geladene Serien - Grundlage fuer ein Umschalten ohne Nachladen. */
  private series: MeterConsumptionSeries[] = [];
  /**
   * Modus je Zaehlertyp. Lebt ausserhalb der Kacheln, weil Refresh und
   * Zeitraumwechsel die Kacheln neu bauen; nach einem Neuladen der Seite steht
   * bewusst alles wieder auf Verbrauch (wie Zeitraum und Aufloesung).
   */
  private readonly modeByType = new Map<MeterType, TileMode>();
```
Im `next:` des `load`:
```ts
        this.series = series;
        this.tiles = this.buildTiles(resolution);
```
Neue Methoden:
```ts
  /** Schaltet eine Kachel um - rein lokal, kein Nachladen. */
  setMode(meterType: MeterType, mode: TileMode): void {
    if (this.modeOf(meterType) === mode) {
      return;
    }
    this.modeByType.set(meterType, mode);
    this.tiles = this.buildTiles(this.activeResolution);
  }

  private modeOf(meterType: MeterType): TileMode {
    return this.modeByType.get(meterType) ?? 'consumption';
  }

  private buildTiles(resolution: ConsumptionResolution): ConsumptionTile[] {
    return this.series.map(s => this.toTile(s, resolution, this.modeOf(s.meterType)));
  }
```
`toTile` ersetzen:
```ts
  private toTile(
    series: MeterConsumptionSeries,
    resolution: ConsumptionResolution,
    mode: TileMode
  ): ConsumptionTile {
    const isCost = mode === 'cost';
    const points = isCost ? series.points.filter(p => p.cost !== null) : series.points;
    const last = points.length > 0 ? points[points.length - 1] : null;
    return {
      key: series.meterType,
      meterType: series.meterType,
      name: MeterTypeUtils.getLabel(series.meterType),
      mode,
      currentLabel: isCost
        ? formatCost(last?.cost ?? null, series.currency)
        : formatConsumption(last?.consumption ?? null, series.unit),
      comparison: isCost
        ? compareToPrevious(points, resolution, p => p.cost)
        : compareToPrevious(points, resolution),
      hasEstimated: points.some(p => p.estimated),
      missingPriceCount: isCost ? series.points.length - points.length : 0,
      hasNoCost: isCost && points.length === 0,
      options: this.chartOptionsFor(series, points, mode)
    };
  }
```
`chartOptionsFor(series, points, mode)`:
```ts
    const isCost = mode === 'cost';
    const unitLabel = isCost ? '€' : series.unit;
    const valueOf = (p: ConsumptionPoint): number | null => (isCost ? p.cost : p.consumption);
    const format = (value: number) =>
      isCost ? formatCost(value, series.currency) : formatConsumption(value, series.unit);
```
und darin `tooltip.valueFormatter: format`, `yAxis.axisLabel.formatter: \`{value} ${unitLabel}\``, `xAxis.data: points.map(p => p.label)`, `series[0].data: points.map(p => ({ value: [p.label, valueOf(p)], itemStyle: {...} }))`.
Imports: `ConsumptionPoint`, `MeterType`, `formatCost`.

**Auf `MeterType` in `models/meter-reading.model.ts` achten:** Enum, kein String-Union — die `Map`-Schlüssel und der `key` der Kachel bleiben kompatibel.

- [ ] **Step 4: Template anpassen**

Kachelkopf:
```html
            <header class="tablet-consumption__card-head">
              <div class="tablet-consumption__title-row">
                <h3 class="tablet-consumption__card-title">{{ tile.name }}</h3>
                <div class="tablet-consumption__modes" role="group" [attr.aria-label]="'Ansicht ' + tile.name">
                  @for (mode of modes; track mode.value) {
                    <button
                      type="button"
                      class="tablet-consumption__mode-btn"
                      [class.tablet-consumption__mode-btn--active]="mode.value === tile.mode"
                      [attr.aria-pressed]="mode.value === tile.mode"
                      (click)="setMode(tile.meterType, mode.value)">
                      {{ mode.label }}
                    </button>
                  }
                </div>
              </div>
              <div class="tablet-consumption__figures">
                <span class="tablet-consumption__current">{{ tile.currentLabel }}</span>
                @if (tile.comparison) {
                  <span class="tablet-consumption__comparison">{{ tile.comparison }}</span>
                }
              </div>
            </header>
```
Diagramm und Hinweise:
```html
            @if (tile.hasNoCost) {
              <p class="tablet-consumption__empty">Kein Preis hinterlegt</p>
            } @else {
              <div
                echarts
                class="tablet-consumption__chart"
                [options]="tile.options"
                [autoResize]="true"></div>
            }
            @if (tile.hasEstimated || tile.missingPriceCount > 0) {
              <p class="tablet-consumption__legend">
                @if (tile.hasEstimated) {
                  <span>Blasse Balken sind Schätzwerte</span>
                }
                @if (tile.missingPriceCount > 0) {
                  <span>{{ tile.missingPriceCount }} Perioden ohne hinterlegten Preis</span>
                }
              </p>
            }
```

- [ ] **Step 5: Styles ergänzen (in `.tablet-consumption`)**

```scss
  &__title-row {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    min-width: 0;
  }

  // Kompakter als die Seitenknoepfe: sitzt in jedem Kachelkopf.
  &__modes {
    display: inline-flex;
    gap: 0.15rem;
    padding: 0.15rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 9px;
    background: rgba(255, 255, 255, 0.04);
    flex: 0 0 auto;
  }

  &__mode-btn {
    border: none;
    background: transparent;
    padding: 0.3rem 0.7rem;
    border-radius: 7px;
    cursor: pointer;
    font: inherit;
    font-size: 0.8rem;
    color: rgba(228, 226, 228, 0.7);

    &--active {
      background: rgba(170, 199, 255, 0.16);
      color: #aac7ff;
    }
  }

  &__empty {
    flex: 1 1 auto;
    display: grid;
    place-items: center;
    margin: 0;
    color: #94a3b8;
    font-size: 1.05rem;
  }

  &__legend {
    display: flex;
    gap: 1rem;
    flex-wrap: wrap;
  }
```
Bei `&__legend` die bestehenden Regeln (`flex: 0 0 auto`, `margin`, `font-size`, `color`) beibehalten und nur `display/gap/flex-wrap` ergänzen. `&__card-head` bekommt `align-items: flex-start` statt `baseline`, damit der Umschalter neben dem Titel nicht springt.

- [ ] **Step 6: Tests laufen lassen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-consumption.component.spec.ts'
```
Erwartet: alle PASS, inklusive Höhenketten-Test.

- [ ] **Step 7: Optisch prüfen**

Backend und Frontend starten (`mvn spring-boot:run` mit `ZIGBEE_MQTT_CLIENT_ID=household-manager-zigbee-dev`, `npm start`), `http://localhost:4200/tablet/consumption` im Browser bei ~1280×800 öffnen, umschalten, Screenshot an den Nutzer senden.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/pages/tablet-consumption
git commit -F - <<'EOF'
feat(tablet): Verbrauch/Kosten je Kachel in der Verbrauchsansicht umschaltbar

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 8: Preisseite: Wasser und Gasfaktor

**Files:**
- Modify: `frontend/src/app/models/utility-price.model.ts`
- Modify: `frontend/src/app/services/utility-price.service.ts`
- Modify: `frontend/src/app/components/utility-price-form/utility-price-form.component.ts` (Zeile 34)
- Modify: `frontend/src/app/pages/utility-prices/utility-prices.component.ts` (Zeile 43) und `.html`
- Create: `frontend/src/app/pages/utility-prices/utility-prices.component.spec.ts`

- [ ] **Step 1: Modell und Service**

Modell:
```ts
/** Pflegbare Preis-Einstellungen (GET/PUT /v1/utility-prices/settings). */
export interface UtilityPricingSettings {
  /** Gas: kWh je m³ (Brennwert × Zustandszahl). */
  gasKwhPerM3: number;
}
```
Service:
```ts
  getSettings(): Observable<UtilityPricingSettings> {
    return this.http.get<UtilityPricingSettings>(`${this.baseUrl}/settings`).pipe(
      catchError(this.handleError)
    );
  }

  updateSettings(settings: UtilityPricingSettings): Observable<UtilityPricingSettings> {
    return this.http.put<UtilityPricingSettings>(`${this.baseUrl}/settings`, settings).pipe(
      catchError(this.handleError)
    );
  }
```

- [ ] **Step 2: Failing Spec schreiben**

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { UtilityPricesComponent } from './utility-prices.component';
import { UtilityPriceService } from '../../services/utility-price.service';
import { AuthService } from '../../services/auth.service';
import { MeterType } from '../../models/meter-reading.model';

describe('UtilityPricesComponent', () => {
  let fixture: ComponentFixture<UtilityPricesComponent>;
  let component: UtilityPricesComponent;
  let serviceSpy: jasmine.SpyObj<UtilityPriceService>;

  function setup(isAdmin: boolean): void {
    serviceSpy = jasmine.createSpyObj('UtilityPriceService',
      ['getAllPrices', 'getSettings', 'updateSettings', 'deletePrice']);
    serviceSpy.getAllPrices.and.returnValue(of([]));
    serviceSpy.getSettings.and.returnValue(of({ gasKwhPerM3: 10.63 }));
    serviceSpy.updateSettings.and.returnValue(of({ gasKwhPerM3: 11 }));

    TestBed.configureTestingModule({
      imports: [UtilityPricesComponent],
      providers: [
        { provide: UtilityPriceService, useValue: serviceSpy },
        { provide: AuthService, useValue: { isAdmin: signal(isAdmin) } }
      ]
    });
    fixture = TestBed.createComponent(UtilityPricesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('fuehrt Wasser als dritten Zaehlertyp', () => {
    setup(true);
    expect(component.meterTypes).toEqual([MeterType.ELECTRICITY, MeterType.GAS, MeterType.WATER]);
  });

  it('laedt den Gasfaktor und zeigt ihn dem Admin', () => {
    setup(true);
    expect(component.gasKwhPerM3).toBe(10.63);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.pricing-settings')).not.toBeNull();
  });

  it('versteckt den Gasfaktor vor Nicht-Admins und laedt ihn nicht', () => {
    setup(false);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.pricing-settings')).toBeNull();
    expect(serviceSpy.getSettings).not.toHaveBeenCalled();
  });

  it('speichert den Gasfaktor', () => {
    setup(true);
    component.gasKwhPerM3 = 11;
    component.saveGasFactor();
    expect(serviceSpy.updateSettings).toHaveBeenCalledWith({ gasKwhPerM3: 11 });
    expect(component.successMessage).toContain('Gasfaktor');
  });
});
```
Prüfe, ob `AuthService.isAdmin` ein `computed`-Signal ist (Zeile 17 in `auth.service.ts`: ja) — der Stub mit `signal(isAdmin)` deckt das ab. Falls `UtilityPriceFormComponent` weitere Provider braucht (HttpClient), `provideHttpClient()` + `provideHttpClientTesting()` ergänzen.

- [ ] **Step 3: Test laufen lassen, muss fehlschlagen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/utility-prices.component.spec.ts'
```
Erwartet: FAIL (`meterTypes` hat zwei Einträge, `gasKwhPerM3`/`saveGasFactor` fehlen).

- [ ] **Step 4: Komponente und Formular erweitern**

`utility-price-form.component.ts` Zeile 34 und `utility-prices.component.ts` Zeile 43:
```ts
  readonly meterTypes = [MeterType.ELECTRICITY, MeterType.GAS, MeterType.WATER];
```
In `UtilityPricesComponent`:
```ts
  private readonly authService = inject(AuthService);
  readonly isAdmin = this.authService.isAdmin;

  /** Gas: kWh je m³ - nur fuer ADMIN geladen und sichtbar. */
  gasKwhPerM3: number | null = null;
  isSavingSettings = false;

  ngOnInit(): void {
    this.loadPrices();
    if (this.isAdmin()) {
      this.loadSettings();
    }
  }

  private loadSettings(): void {
    this.utilityPriceService.getSettings().subscribe({
      next: settings => (this.gasKwhPerM3 = settings.gasKwhPerM3),
      error: () => (this.errorMessage = 'Der Gasfaktor konnte nicht geladen werden.')
    });
  }

  saveGasFactor(): void {
    if (this.gasKwhPerM3 === null) {
      return;
    }
    this.isSavingSettings = true;
    this.utilityPriceService.updateSettings({ gasKwhPerM3: this.gasKwhPerM3 }).subscribe({
      next: settings => {
        this.gasKwhPerM3 = settings.gasKwhPerM3;
        this.successMessage = 'Gasfaktor gespeichert.';
        this.isSavingSettings = false;
      },
      error: (error: Error) => {
        this.errorMessage = error.message || 'Der Gasfaktor konnte nicht gespeichert werden.';
        this.isSavingSettings = false;
      }
    });
  }
```
`FormsModule` zu `imports` ergänzen (für `[(ngModel)]`), `AuthService` importieren.

Template: Untertitel auf „Erfassen und verwalten Sie hier Ihre Strom-, Gas- und Wasserpreise zur Kostenberechnung"; vor `<div class="content-grid">`:
```html
    <section class="pricing-settings" *ngIf="isAdmin()">
      <h2 class="section-title">
        <app-icon name="flame" [size]="20"></app-icon>
        Gas: Umrechnung
      </h2>
      <p class="pricing-settings__hint">
        Der Gaszähler zählt m³, der Gaspreis gilt je kWh. Der Faktor (Brennwert × Zustandszahl)
        steht auf der Gasrechnung, meist zwischen 9 und 12.
      </p>
      <div class="pricing-settings__row">
        <label for="gasKwhPerM3">kWh je m³</label>
        <input id="gasKwhPerM3" type="number" step="0.01" min="5" max="15"
               [(ngModel)]="gasKwhPerM3" name="gasKwhPerM3">
        <button type="button" class="btn btn--primary"
                [disabled]="isSavingSettings || gasKwhPerM3 === null"
                (click)="saveGasFactor()">
          Speichern
        </button>
      </div>
    </section>
```
Prüfe, ob das Icon `flame` in `IconComponent` existiert; sonst ein vorhandenes (z. B. `settings`) nehmen. SCSS: `.pricing-settings` als Karte im Stil der bestehenden `.form-section` (Abstand unten 1.5rem, `__row` als Flex mit `gap: 0.75rem`, Input `width: 8rem`).

- [ ] **Step 5: Tests laufen lassen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/utility-prices.component.spec.ts'
```
Erwartet: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/utility-price.model.ts frontend/src/app/services/utility-price.service.ts frontend/src/app/components/utility-price-form frontend/src/app/pages/utility-prices
git commit -F - <<'EOF'
feat(prices): Wasser auf der Preisseite, Gasfaktor pflegbar

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 9: Doku, Gesamtläufe

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Tablet-Ansichten", nach der Vierten Ansicht `/tablet/consumption`)

- [ ] **Step 1: CLAUDE.md-Absatz ergänzen**

Nach dem letzten Aufzählungspunkt zur Verbrauchsansicht („Der Höhenketten-Test dieser Ansicht misst bei 900 und 1200 px …"):

```markdown
- **Kostenmodus je Kachel (2026-09-08):** jede Kachel schaltet einzeln zwischen „Verbrauch" und „Kosten"; Kopfwert und Vorperiodenvergleich folgen dem Modus. Der Modus lebt in einer `Map` je Zählertyp in der Komponente (überlebt Refresh und Zeitraumwechsel), wird aber **nicht** gespeichert — nach dem Neuladen steht alles auf Verbrauch, wie Zeitraum und Auflösung. Spec: `docs/superpowers/specs/2026-09-08-tablet-verbrauch-kostenansicht-design.md`
- **Die Kosten kommen aus dem Serien-Endpunkt** (`ConsumptionPoint.cost`, nullable; `MeterConsumptionSeries.currency`), das Umschalten lädt nichts nach. `MeterCostCalculator` ist die einzige Definition von „was kostet Menge X am Datum Y": jede Ablesewoche zum am **Ablesedatum** gültigen Preis, Monatsbalken summieren, Rundung auf 2 Nachkommastellen erst **nach** der Summe. Die Preise eines Typs werden einmal je Serie geladen, nicht je Woche
- **Ein Balken ohne Preis entfällt** in der Kostenansicht (keine erfundene 0 €). **Monatsregel:** fehlt einer beitragenden Woche der Preis, entfällt der ganze Monatsbalken — eine Teilsumme sähe aus wie ein billiger Monat. Die Kachel nennt die Zahl der entfallenen Perioden; sind alle ohne Preis, steht „Kein Preis hinterlegt" statt des Diagramms
- **Gas: der Zähler zählt m³, der Preis ist €/kWh.** Dazwischen steht der pflegbare Faktor `gas_kwh_per_m3` (`application_settings`, Kategorie `UTILITY_PRICING`, `UtilityPricingSettingsService`, Default 10,0, plausibel 5–15, defensives Lesen wirft nie). Gepflegt auf der Preisseite, Endpunkt `GET/PUT /v1/utility-prices/settings` — ein **methodenloser ADMIN-Matcher vor** der KIOSK-GET-Regel für `/v1/utility-prices/**`, `SecurityRulesTest` hält es fest. Audit `utility-pricing.settings.update`. Der Faktor gilt für die **gesamte Historie**: ändert der Versorger den Brennwert, verschiebt sich rückwirkend die ganze Gaskurve
- **Wasserpreise sind seit Changeset `20260908-0052` erlaubt** (vorher nur Strom und Gas, `validateMeterType` ist entfernt); Preis in €/m³
- **Nur Arbeitspreis:** Grundgebühr, Netzentgelte und Steuern fehlen — der Wert ist „verbrauchsabhängige Kosten", kein Rechnungsbetrag. Die Website-Seite `/meter-readings` kennt den Kostenmodus nicht
```

- [ ] **Step 2: Kompletter Backend-Lauf**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn -q test
```
Erwartet: nur die zwei vorbestehenden DB-Fails.

- [ ] **Step 3: Kompletter Frontend-Lauf**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
Erwartet: „3 FAILED" (Baseline), Rest grün; bei `SmartDeviceListComponent`-Flake erneut laufen lassen.

- [ ] **Step 4: Production-Build (Budget-Prüfung)**

```bash
cd frontend && npx ng build --configuration production
```
Erwartet: erfolgreich; der bekannte `dashboard.component.scss`-Budget-ERROR ist vorbestehend (siehe Memory `dashboard-scss-budget`), neue Budget-Fehler an `tablet-consumption.component.scss` wären eine Regression.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -F - <<'EOF'
docs: Kostenansicht der Tablet-Verbrauchsuebersicht in CLAUDE.md

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

- [ ] **Step 6: Abschluss**

`superpowers:finishing-a-development-branch` aufrufen (Branch mergen/PR). Rollout-Hinweis an den Nutzer: nach dem Deploy Wasserpreis erfassen und Gasfaktor aus der letzten Gasrechnung eintragen, Kostenkurven gegen die Abrechnung plausibilisieren.
