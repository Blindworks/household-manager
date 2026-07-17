# Dashboard-Modi Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die vier Modus-Knöpfe im Lumina-Dashboard (Abwesend, Toni allein, Nachtmodus, Ausschalten) werden echte, umschaltbare Haus-Modi auf Basis von INPUT_BOOLEAN-Entities.

**Architecture:** Backend seedet vier `MANUAL`-INPUT_BOOLEAN-Entities mit Marker-Attribut `"mode": true` beim Start, bietet `GET/POST /api/v1/modes` an und blendet Modi aus der Schalter-API aus. Frontend bekommt einen `ModeService` und bindet die Modus-Leiste mit optimistischem Toggle, Polling und Aktiv-Styling an. Was in einem Modus passiert, definiert der Nutzer per Flow (`entity-state-trigger`) — kein weiterer Code.

**Tech Stack:** Spring Boot 3.4 (Java 21, Lombok, Mockito/AssertJ), Angular 19 standalone (RxJS, Karma/Jasmine, SCSS).

**Spec:** `docs/superpowers/specs/2026-07-17-dashboard-modes-design.md`

**Umgebung:** Maven braucht JDK 21: vor jedem `mvn` in Bash `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` (PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'`). Aus `backend/` heraus ausführen. Die lokalen Tests `HouseholdManagerApplicationTests` und `HealthControllerTest` schlagen mangels DB umgebungsbedingt fehl — ignorieren, gezielt mit `-Dtest=...` testen.

---

## Dateistruktur

**Backend (neu):**
- `backend/src/main/java/com/household/manager/entitystate/HouseModes.java` — Katalog (Name, Icon, Reihenfolge), Marker-Konstante, `isMode`-Helfer
- `backend/src/main/java/com/household/manager/entitystate/HouseModeInitializer.java` — idempotentes Seeding beim Start
- `backend/src/main/java/com/household/manager/entitystate/HouseModeQueryService.java` — Modi in Katalog-Reihenfolge
- `backend/src/main/java/com/household/manager/entitystate/mapper/ModeResponseMapper.java` — EntityState → ModeResponse
- `backend/src/main/java/com/household/manager/dto/ModeResponse.java` — API-DTO
- `backend/src/main/java/com/household/manager/controller/ModeController.java` — `GET /v1/modes`, `POST /v1/modes/{id}/toggle`

**Backend (ändern):**
- `backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java` — Modi ausfiltern

**Frontend (neu):**
- `frontend/src/app/models/mode.model.ts`, `frontend/src/app/services/mode.service.ts` (+ Spec)

**Frontend (ändern):**
- `frontend/src/app/pages/dashboard/dashboard.component.ts|html|scss|spec.ts` — Modus-Leiste anbinden

---

### Task 1: HouseModes-Katalog + HouseModeInitializer (Backend-Seeding)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/HouseModes.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/HouseModeInitializer.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/HouseModeInitializerTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseModeInitializerTest {

    @Mock
    private EntityStateService entityStateService;

    private HouseModeInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new HouseModeInitializer(entityStateService,
                new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState modeEntity(String entityId, String state, String attributes) {
        return EntityState.builder()
                .entityId(entityId)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef(entityId.substring("input_boolean.manual_".length()))
                .friendlyName("Bestand")
                .state(state)
                .attributes(attributes)
                .build();
    }

    @Test
    void legt_fehlende_modi_mit_marker_und_icon_an() {
        when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());

        initializer.seedHouseModes();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(4)).reportState(captor.capture());
        assertThat(captor.getAllValues()).extracting(EntityStateUpdate::entityId).containsExactly(
                "input_boolean.manual_abwesend",
                "input_boolean.manual_toni_allein",
                "input_boolean.manual_nachtmodus",
                "input_boolean.manual_ausschalten");
        EntityStateUpdate first = captor.getAllValues().get(0);
        assertThat(first.friendlyName()).isEqualTo("Abwesend");
        assertThat(first.state()).isEqualTo("off");
        assertThat(first.attributes())
                .containsEntry("mode", true)
                .containsEntry("icon", "exit_to_app");
    }

    @Test
    void ergaenzt_nur_den_marker_bei_vorhandener_entity_ohne_marker() {
        when(entityStateService.getByEntityId(anyString()))
                .thenAnswer(invocation -> Optional.of(modeEntity(
                        invocation.getArgument(0), "on", "{\"mode\":true}")));
        when(entityStateService.getByEntityId("input_boolean.manual_nachtmodus"))
                .thenReturn(Optional.of(modeEntity(
                        "input_boolean.manual_nachtmodus", "on", "{\"icon\":\"bedtime\"}")));

        initializer.seedHouseModes();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(1)).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("input_boolean.manual_nachtmodus");
        // Zustand, Name und vorhandene Attribute bleiben unangetastet:
        assertThat(update.state()).isEqualTo("on");
        assertThat(update.friendlyName()).isEqualTo("Bestand");
        assertThat(update.attributes())
                .containsEntry("icon", "bedtime")
                .containsEntry("mode", true);
    }

    @Test
    void laesst_vollstaendig_markierte_modi_unangetastet() {
        when(entityStateService.getByEntityId(anyString()))
                .thenAnswer(invocation -> Optional.of(modeEntity(
                        invocation.getArgument(0), "off", "{\"icon\":\"pets\",\"mode\":true}")));

        initializer.seedHouseModes();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void ein_fehler_bei_einem_modus_stoppt_die_uebrigen_nicht() {
        when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());
        when(entityStateService.getByEntityId("input_boolean.manual_abwesend"))
                .thenThrow(new RuntimeException("DB nicht erreichbar"));

        initializer.seedHouseModes();

        verify(entityStateService, times(3)).reportState(any());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen (Klassen existieren nicht)**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=HouseModeInitializerTest
```
Erwartet: Compile-Fehler (`HouseModes`/`HouseModeInitializer` unbekannt).

- [ ] **Step 3: `HouseModes.java` implementieren**

```java
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
            new HouseModeDefinition("Ausschalten", "power_settings_new")
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
```

- [ ] **Step 4: `HouseModeInitializer.java` implementieren**

```java
package com.household.manager.entitystate;

import com.household.manager.entitystate.HouseModes.HouseModeDefinition;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Legt die Haus-Modi aus {@link HouseModes#CATALOG} beim Start an (idempotent).
 * Vorhandene Entities behalten Zustand, Namen und Attribute; fehlt nur das
 * Marker-Attribut (z. B. früher manuell angelegter Helfer gleichen Namens),
 * wird es ergänzt. Fehler eines Modus verhindern das Seeding der übrigen nicht.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HouseModeInitializer {

    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void seedHouseModes() {
        for (HouseModeDefinition definition : HouseModes.CATALOG) {
            try {
                seed(definition);
            } catch (Exception ex) {
                log.warn("Haus-Modus {} konnte nicht angelegt werden: {}", definition.name(), ex.getMessage());
            }
        }
    }

    private void seed(HouseModeDefinition definition) {
        String entityId = HouseModes.entityId(definition);
        EntityState existing = entityStateService.getByEntityId(entityId).orElse(null);
        if (existing == null) {
            report(entityId, EntityIds.slug(definition.name()), definition.name(),
                    ManualEntityService.STATE_OFF, newModeAttributes(definition.icon()));
            log.info("Haus-Modus angelegt: {}", entityId);
            return;
        }
        Map<String, Object> attributes =
                new LinkedHashMap<>(responseMapper.parseAttributes(existing.getAttributes()));
        if (HouseModes.isMode(attributes)) {
            return;
        }
        attributes.put(HouseModes.ATTR_MODE, true);
        report(entityId, existing.getSourceRef(), existing.getFriendlyName(), existing.getState(), attributes);
        log.info("Haus-Modus-Marker ergänzt: {}", entityId);
    }

    private Map<String, Object> newModeAttributes(String icon) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("icon", icon);
        attributes.put(HouseModes.ATTR_MODE, true);
        return attributes;
    }

    private void report(String entityId, String sourceRef, String friendlyName, String state,
                        Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(entityId)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef(sourceRef)
                .friendlyName(friendlyName)
                .state(state)
                .attributes(attributes)
                .build());
    }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=HouseModeInitializerTest
```
Erwartet: 4 Tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/HouseModes.java \
        backend/src/main/java/com/household/manager/entitystate/HouseModeInitializer.java \
        backend/src/test/java/com/household/manager/entitystate/HouseModeInitializerTest.java
git commit -m "feat(modes): Haus-Modi beim Start idempotent anlegen"
```

---

### Task 2: Modus-API (`GET /v1/modes`, `POST /v1/modes/{id}/toggle`)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/ModeResponse.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/mapper/ModeResponseMapper.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/HouseModeQueryService.java`
- Create: `backend/src/main/java/com/household/manager/controller/ModeController.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/HouseModeQueryServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseModeQueryServiceTest {

    @Mock
    private EntityStateRepository entityStateRepository;

    private HouseModeQueryService service;

    @BeforeEach
    void setUp() {
        EntityStateResponseMapper entityMapper = new EntityStateResponseMapper(new ObjectMapper());
        service = new HouseModeQueryService(entityStateRepository, entityMapper,
                new ModeResponseMapper(entityMapper));
    }

    private EntityState manualBoolean(String ref, String name, String state, String attributes) {
        return EntityState.builder()
                .entityId("input_boolean.manual_" + ref)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .build();
    }

    @Test
    void liefert_nur_marker_entities_in_katalog_reihenfolge() {
        // Repository liefert nach entityId sortiert: ausschalten < nachtmodus < urlaub < zieltemp...
        when(entityStateRepository.findByDomainAndSourceOrderByEntityIdAsc(any(), any())).thenReturn(List.of(
                manualBoolean("ausschalten", "Ausschalten", "off", "{\"icon\":\"power_settings_new\",\"mode\":true}"),
                manualBoolean("nachtmodus", "Nachtmodus", "on", "{\"icon\":\"nights_stay\",\"mode\":true}"),
                manualBoolean("urlaub", "Urlaub", "off", "{\"mode\":true}"),
                manualBoolean("gewoehnlich", "Gewöhnlicher Helfer", "on", "{\"icon\":\"toggle_on\"}")
        ));

        List<ModeResponse> modes = service.listModes();

        // Katalog-Modi zuerst in Katalog-Reihenfolge, unbekannte Marker-Entities dahinter;
        // der Helfer ohne Marker fehlt.
        assertThat(modes).extracting(ModeResponse::entityId).containsExactly(
                "input_boolean.manual_nachtmodus",
                "input_boolean.manual_ausschalten",
                "input_boolean.manual_urlaub");
    }

    @Test
    void bildet_name_icon_und_zustand_ab_mit_icon_fallback() {
        when(entityStateRepository.findByDomainAndSourceOrderByEntityIdAsc(any(), any())).thenReturn(List.of(
                manualBoolean("nachtmodus", "Nachtmodus", "on", "{\"icon\":\"nights_stay\",\"mode\":true}"),
                manualBoolean("urlaub", "Urlaub", "off", "{\"mode\":true}")
        ));

        List<ModeResponse> modes = service.listModes();

        assertThat(modes.get(0).displayName()).isEqualTo("Nachtmodus");
        assertThat(modes.get(0).icon()).isEqualTo("nights_stay");
        assertThat(modes.get(0).state()).isEqualTo("on");
        assertThat(modes.get(1).icon()).isEqualTo("flag");
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=HouseModeQueryServiceTest
```
Erwartet: Compile-Fehler (Klassen existieren nicht).

- [ ] **Step 3: `ModeResponse.java` implementieren**

```java
package com.household.manager.dto;

import lombok.Builder;

/** API-Repräsentation eines Haus-Modus für die Modus-Leiste des Dashboards. */
@Builder
public record ModeResponse(
        String entityId,
        String displayName,
        String icon,
        String state
) {
}
```

- [ ] **Step 4: `ModeResponseMapper.java` implementieren**

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.dto.ModeResponse;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bildet eine Modus-Entity auf die API-{@link ModeResponse} ab. */
@Component
@RequiredArgsConstructor
public class ModeResponseMapper {

    private static final String DEFAULT_ICON = "flag";
    private static final String ATTR_ICON = "icon";

    private final EntityStateResponseMapper entityStateResponseMapper;

    public ModeResponse toResponse(EntityState entity) {
        Object icon = entityStateResponseMapper.parseAttributes(entity.getAttributes()).get(ATTR_ICON);
        return ModeResponse.builder()
                .entityId(entity.getEntityId())
                .displayName(entityStateResponseMapper.displayName(entity))
                .icon(icon instanceof String text && !text.isBlank() ? text : DEFAULT_ICON)
                .state(entity.getState())
                .build();
    }
}
```

- [ ] **Step 5: `HouseModeQueryService.java` implementieren**

```java
package com.household.manager.entitystate;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** Liefert die Haus-Modi für die Modus-Leiste in Katalog-Reihenfolge. */
@Service
@RequiredArgsConstructor
public class HouseModeQueryService {

    private final EntityStateRepository entityStateRepository;
    private final EntityStateResponseMapper entityStateResponseMapper;
    private final ModeResponseMapper modeResponseMapper;

    @Transactional(readOnly = true)
    public List<ModeResponse> listModes() {
        return entityStateRepository
                .findByDomainAndSourceOrderByEntityIdAsc(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)
                .stream()
                .filter(entity -> HouseModes.isMode(
                        entityStateResponseMapper.parseAttributes(entity.getAttributes())))
                .sorted(Comparator.comparingInt(this::catalogIndex))
                .map(modeResponseMapper::toResponse)
                .toList();
    }

    /**
     * Katalog-Position eines Modus; unbekannte Marker-Entities landen dahinter
     * (die stabile Sortierung erhält deren alphabetische Repository-Reihenfolge).
     */
    private int catalogIndex(EntityState entity) {
        for (int i = 0; i < HouseModes.CATALOG.size(); i++) {
            if (HouseModes.entityId(HouseModes.CATALOG.get(i)).equals(entity.getEntityId())) {
                return i;
            }
        }
        return HouseModes.CATALOG.size();
    }
}
```

- [ ] **Step 6: `ModeController.java` implementieren**

```java
package com.household.manager.controller;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-API der Haus-Modi (Modus-Leiste im Dashboard). Das Umschalten delegiert
 * an {@link ManualEntityService}, damit Events für die Flow-Engine über den
 * gewohnten Weg publiziert werden.
 */
@RestController
@RequestMapping("/v1/modes")
@RequiredArgsConstructor
public class ModeController {

    private final HouseModeQueryService houseModeQueryService;
    private final ManualEntityService manualEntityService;
    private final ModeResponseMapper modeResponseMapper;

    @GetMapping
    public List<ModeResponse> listModes() {
        return houseModeQueryService.listModes();
    }

    @PostMapping("/{entityId}/toggle")
    public ModeResponse toggle(@PathVariable String entityId) {
        return modeResponseMapper.toResponse(manualEntityService.toggle(entityId));
    }
}
```

- [ ] **Step 7: Test laufen lassen — muss grün sein**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=HouseModeQueryServiceTest
```
Erwartet: 2 Tests PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/ModeResponse.java \
        backend/src/main/java/com/household/manager/entitystate/mapper/ModeResponseMapper.java \
        backend/src/main/java/com/household/manager/entitystate/HouseModeQueryService.java \
        backend/src/main/java/com/household/manager/controller/ModeController.java \
        backend/src/test/java/com/household/manager/entitystate/HouseModeQueryServiceTest.java
git commit -m "feat(modes): REST-API fuer Haus-Modi (Liste + Toggle)"
```

---

### Task 3: Schalter-API blendet Haus-Modi aus

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java`

- [ ] **Step 1: Failing Test ergänzen** (in der bestehenden Testklasse; zusätzlich `setUp` anpassen, weil der Service einen weiteren Konstruktor-Parameter bekommt)

`setUp` neu:

```java
    @BeforeEach
    void setUp() {
        EntityStateResponseMapper entityMapper = new EntityStateResponseMapper(new ObjectMapper());
        service = new SwitchQueryService(entityStateRepository, entityUsageService,
                new SwitchResponseMapper(entityMapper), entityMapper);
    }
```

Neuer Test (ans Ende der Klasse):

```java
    @Test
    void filtert_haus_modi_heraus_behaelt_gewoehnliche_helfer() {
        EntityState mode = EntityState.builder()
                .entityId("input_boolean.manual_nachtmodus")
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state("off")
                .attributes("{\"icon\":\"nights_stay\",\"mode\":true}")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        EntityState helper = EntityState.builder()
                .entityId("input_boolean.manual_urlaub")
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("urlaub")
                .friendlyName("Urlaub")
                .state("off")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(mode, helper));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Urlaub");
    }
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=SwitchQueryServiceTest
```
Erwartet: Compile-Fehler im `setUp` (Konstruktor hat noch 3 Parameter) bzw. nach Signatur-Fix FAIL des neuen Tests.

- [ ] **Step 3: `SwitchQueryService` anpassen** — Feld + Filter:

```java
    private final EntityStateRepository entityStateRepository;
    private final EntityUsageService entityUsageService;
    private final SwitchResponseMapper switchResponseMapper;
    private final EntityStateResponseMapper entityStateResponseMapper;
```

Import ergänzen: `import com.household.manager.entitystate.mapper.EntityStateResponseMapper;`

In `listSwitches` den Stream erweitern (Haus-Modi haben eine eigene Leiste und API):

```java
        List<EntityState> switchable = entityStateRepository
                .findByDomainInOrderByEntityIdAsc(SwitchableEntities.SWITCHABLE_DOMAINS).stream()
                .filter(SwitchableEntities::isSwitchable)
                // Haus-Modi haben eine eigene Leiste im Dashboard und die Modus-API.
                .filter(entity -> !HouseModes.isMode(
                        entityStateResponseMapper.parseAttributes(entity.getAttributes())))
                .toList();
```

- [ ] **Step 4: Tests laufen lassen — alle grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=SwitchQueryServiceTest
```
Erwartet: 8 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java \
        backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java
git commit -m "feat(modes): Haus-Modi aus der Schalter-API ausblenden"
```

---

### Task 4: Frontend `ModeService` + Modell

**Files:**
- Create: `frontend/src/app/models/mode.model.ts`
- Create: `frontend/src/app/services/mode.service.ts`
- Test: `frontend/src/app/services/mode.service.spec.ts`

- [ ] **Step 1: Failing Spec schreiben**

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ModeService } from './mode.service';
import { ModeEntity } from '../models/mode.model';

describe('ModeService', () => {
  let service: ModeService;
  let httpMock: HttpTestingController;

  const mode: ModeEntity = {
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(ModeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt die Haus-Modi', () => {
    service.getModes().subscribe(result => expect(result).toEqual([mode]));

    const req = httpMock.expectOne('/api/v1/modes');
    expect(req.request.method).toBe('GET');
    req.flush([mode]);
  });

  it('schaltet einen Modus um', () => {
    service.toggle(mode.entityId).subscribe(result => expect(result.state).toBe('on'));

    const req = httpMock.expectOne('/api/v1/modes/input_boolean.manual_nachtmodus/toggle');
    expect(req.request.method).toBe('POST');
    req.flush({ ...mode, state: 'on' });
  });

  it('meldet einen Fehler als Error weiter', () => {
    let failed = false;
    service.toggle(mode.entityId).subscribe({ error: () => (failed = true) });

    httpMock.expectOne('/api/v1/modes/input_boolean.manual_nachtmodus/toggle')
      .flush('kaputt', { status: 500, statusText: 'Server Error' });

    expect(failed).toBeTrue();
  });
});
```

- [ ] **Step 2: `mode.model.ts` + `mode.service.ts` implementieren**

`frontend/src/app/models/mode.model.ts`:

```typescript
/** Haus-Modus der Dashboard-Modus-Leiste (INPUT_BOOLEAN mit Modus-Marker). */
export interface ModeEntity {
  entityId: string;
  displayName: string;
  /** Material-Symbols-Name. */
  icon: string;
  /** "on" oder "off". */
  state: string;
}
```

`frontend/src/app/services/mode.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ModeEntity } from '../models/mode.model';

/** REST-Service für die Haus-Modi (Modus-Leiste im Dashboard). */
@Injectable({ providedIn: 'root' })
export class ModeService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/modes';

  /** Haus-Modi in Anzeige-Reihenfolge. */
  getModes(): Observable<ModeEntity[]> {
    return this.http.get<ModeEntity[]>(this.baseUrl).pipe(catchError(this.handleError));
  }

  /** Schaltet einen Modus um und liefert seinen aktualisierten Zustand. */
  toggle(entityId: string): Observable<ModeEntity> {
    return this.http.post<ModeEntity>(`${this.baseUrl}/${entityId}/toggle`, {}).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Modus-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Modus-Anfrage.'));
  }
}
```

- [ ] **Step 3: Spec laufen lassen — muss grün sein**

```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/mode.service.spec.ts'
```
Erwartet: 3 Specs PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/mode.model.ts \
        frontend/src/app/services/mode.service.ts \
        frontend/src/app/services/mode.service.spec.ts
git commit -m "feat(modes): ModeService fuer die Haus-Modi-API"
```

---

### Task 5: Dashboard-Modus-Leiste anbinden

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (Footer, `.lumina__modes`)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss` (Aktiv-/Pending-Zustand, Fehlerhinweis)
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (neuer describe-Block)

- [ ] **Step 1: Failing Specs schreiben** — neuer describe-Block am Dateiende; Import ergänzen: `import { ModeService } from '../../services/mode.service';` und `import { ModeEntity } from '../../models/mode.model';`

```typescript
describe('DashboardComponent (Modus-Leiste)', () => {
  let modeServiceSpy: jasmine.SpyObj<ModeService>;

  const mode = (overrides: Partial<ModeEntity> = {}): ModeEntity => ({
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off',
    ...overrides
  });

  beforeEach(async () => {
    modeServiceSpy = jasmine.createSpyObj('ModeService', ['getModes', 'toggle']);
    modeServiceSpy.getModes.and.returnValue(of([mode()]));
    modeServiceSpy.toggle.and.returnValue(of(mode({ state: 'on' })));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ModeService, useValue: modeServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('laedt die Modi und rendert sie als Knoepfe', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(modeServiceSpy.getModes).toHaveBeenCalled();
    const button = (fixture.nativeElement as HTMLElement).querySelector('.lumina__mode');
    expect(button?.textContent).toContain('Nachtmodus');

    discardPeriodicTasks();
  }));

  it('markiert einen aktiven Modus', fakeAsync(() => {
    modeServiceSpy.getModes.and.returnValue(of([mode({ state: 'on' })]));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement).querySelector('.lumina__mode');
    expect(button?.classList).toContain('lumina__mode--active');

    discardPeriodicTasks();
  }));

  it('schaltet optimistisch und uebernimmt den Zustand aus der Antwort', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_nachtmodus');
    expect(fixture.componentInstance.modes[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('setzt den Zustand bei einem Schaltfehler zurueck und meldet ihn', fakeAsync(() => {
    modeServiceSpy.toggle.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modes[0].state).toBe('off');
    expect(fixture.componentInstance.modeError).toContain('Nachtmodus');

    discardPeriodicTasks();
  }));

  it('behaelt beim Ladefehler die zuletzt bekannten Modi', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.modes.length).toBe(1);
    modeServiceSpy.getModes.and.returnValue(throwError(() => new Error('kaputt')));

    tick(30000);

    expect(fixture.componentInstance.modes.length).toBe(1);

    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Specs laufen lassen — müssen fehlschlagen** (`toggleMode`/`modes`/`modeError` existieren noch nicht)

```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```
Erwartet: Compile-Fehler bzw. FAIL.

- [ ] **Step 3: `dashboard.component.ts` anpassen**

Imports ergänzen:

```typescript
import { ModeService } from '../../services/mode.service';
import { ModeEntity } from '../../models/mode.model';
```

Im Klassenkopf: `private readonly modeService = inject(ModeService);`, Subscription `private modeSubscription?: Subscription;`, Konstante `private static readonly MODE_REFRESH_MS = 30000;` und Farbton-Reihe:

```typescript
  /** Farbton je Modus-Position (bestehende lumina__mode-Varianten). */
  private static readonly MODE_TONES = ['primary', 'tertiary', 'neutral', 'error'] as const;
```

Das statische `modes`-Array und das `ModeButton`-Interface (Datei-Ende) ersetzen durch:

```typescript
  /** Haus-Modi der Fussleiste, vom Backend geladen. */
  modes: ModeEntity[] = [];
  /** Entity-IDs mit laufendem Modus-Schaltbefehl (verhindert Doppelklicks). */
  readonly pendingModeIds = new Set<string>();
  modeError: string | null = null;
```

In `ngOnInit()` zusätzlich `this.startModeRefresh();`, in `ngOnDestroy()` zusätzlich `this.modeSubscription?.unsubscribe();`.

Neue Methoden (nach `applySwitchState`):

```typescript
  /** Farbton eines Modus-Knopfs anhand seiner Position; überzählige werden neutral. */
  modeTone(index: number): string {
    return DashboardComponent.MODE_TONES[index] ?? 'neutral';
  }

  /**
   * Schaltet einen Haus-Modus. Der Zustand wird optimistisch umgeschaltet und
   * bei einem Fehler zurueckgesetzt (gleiches Muster wie {@link toggleSwitch}).
   */
  toggleMode(mode: ModeEntity): void {
    if (this.pendingModeIds.has(mode.entityId)) {
      return;
    }
    const previousState = mode.state;
    this.pendingModeIds.add(mode.entityId);
    this.modeError = null;
    this.applyModeState(mode.entityId, previousState === 'on' ? 'off' : 'on');

    this.modeService.toggle(mode.entityId).subscribe({
      next: updated => {
        this.pendingModeIds.delete(mode.entityId);
        this.applyModeState(updated.entityId, updated.state);
      },
      error: () => {
        this.pendingModeIds.delete(mode.entityId);
        this.applyModeState(mode.entityId, previousState);
        this.modeError = `${mode.displayName} konnte nicht geschaltet werden.`;
      }
    });
  }

  private applyModeState(entityId: string, state: string): void {
    const match = this.modes.find(item => item.entityId === entityId);
    if (match) {
      match.state = state;
    }
  }
```

Neue private Methode (bei den anderen `start*`-Methoden; Flows können Modi auch von außen umschalten, daher Polling):

```typescript
  private startModeRefresh(): void {
    this.modeSubscription = interval(DashboardComponent.MODE_REFRESH_MS)
      .pipe(
        startWith(0),
        // Ladefehler behalten die zuletzt bekannten Modi (null = kein Update).
        switchMap(() => this.modeService.getModes().pipe(catchError(() => of<ModeEntity[] | null>(null))))
      )
      .subscribe(modes => {
        if (modes) {
          this.modes = modes;
          this.modeError = null;
        }
      });
  }
```

- [ ] **Step 4: `dashboard.component.html` anpassen** — den `lumina__modes`-Block im Footer ersetzen durch:

```html
    <div class="lumina__modes-area">
      <div class="lumina__modes">
        <button
          *ngFor="let mode of modes; let i = index"
          type="button"
          class="lumina-card lumina__mode"
          [ngClass]="'lumina__mode--' + modeTone(i)"
          [class.lumina__mode--active]="mode.state === 'on'"
          [class.lumina__mode--pending]="pendingModeIds.has(mode.entityId)"
          (click)="toggleMode(mode)"
        >
          <span class="lumina__mode-icon">
            <span class="material-symbols-outlined">{{ mode.icon }}</span>
          </span>
          <span class="lumina__mode-label">{{ mode.displayName }}</span>
        </button>
      </div>
      <p *ngIf="modeError" class="lumina__mode-error">{{ modeError }}</p>
    </div>
```

- [ ] **Step 5: `dashboard.component.scss` anpassen**

Nach `.lumina__modes { ... }` (Z. 846-850) den Wrapper und Fehlerhinweis ergänzen:

```scss
.lumina__modes-area {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.lumina__mode-error {
  margin: 0;
  font-size: 12px;
  color: #fca5a5;
}
```

Pending-Zustand (nach `.lumina__mode-label`):

```scss
.lumina__mode--pending {
  opacity: 0.6;
  pointer-events: none;
}
```

Aktiv-Zustand je Farbvariante — in jedem der vier Blöcke die Hover-Optik zusätzlich als `--active` festschreiben. Die vier Blöcke (Z. 892-929) werden zu:

```scss
.lumina__mode--primary {
  border-color: rgba(170, 199, 255, 0.2);
  .lumina__mode-icon { background: rgba(170, 199, 255, 0.1); color: var(--primary); }
  &:hover,
  &.lumina__mode--active {
    border-color: rgba(170, 199, 255, 0.5);
    .lumina__mode-icon { background: var(--primary); color: var(--on-primary); }
    .lumina__mode-label { color: var(--primary); }
  }
}

.lumina__mode--tertiary {
  border-color: rgba(233, 196, 0, 0.2);
  .lumina__mode-icon { background: rgba(233, 196, 0, 0.1); color: var(--tertiary); }
  &:hover,
  &.lumina__mode--active {
    border-color: rgba(233, 196, 0, 0.5);
    .lumina__mode-icon { background: var(--tertiary); color: var(--on-tertiary); }
    .lumina__mode-label { color: var(--tertiary); }
  }
}

.lumina__mode--neutral {
  .lumina__mode-icon { background: rgba(255, 255, 255, 0.05); color: var(--on-surface); }
  &:hover,
  &.lumina__mode--active {
    border-color: rgba(255, 255, 255, 0.4);
    .lumina__mode-icon { background: #fff; color: #131315; }
    .lumina__mode-label { color: #fff; }
  }
}

.lumina__mode--error {
  border-color: rgba(255, 180, 171, 0.2);
  .lumina__mode-icon { background: rgba(255, 180, 171, 0.1); color: var(--error); }
  &:hover,
  &.lumina__mode--active {
    border-color: rgba(255, 180, 171, 0.5);
    .lumina__mode-icon { background: var(--error); color: var(--on-error); }
    .lumina__mode-label { color: var(--error); }
  }
}
```

Im Responsive-Block (Z. 1003-1011) zusätzlich zentrieren:

```scss
  .lumina__modes-area {
    align-items: center;
  }
```

- [ ] **Step 6: Specs laufen lassen — alle grün**

```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```
Erwartet: alle Dashboard-Specs PASS (alte Blöcke stellen keinen ModeService bereit — die echten HTTP-Aufrufe laufen dort ins Test-Backend und bleiben folgenlos offen).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts \
        frontend/src/app/pages/dashboard/dashboard.component.html \
        frontend/src/app/pages/dashboard/dashboard.component.scss \
        frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(dashboard): Modus-Leiste an Haus-Modi anbinden"
```

---

### Task 6: Gesamtverifikation

- [ ] **Step 1: Backend-Gesamtbuild + relevante Tests**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && \
mvn test -Dtest='HouseModeInitializerTest,HouseModeQueryServiceTest,SwitchQueryServiceTest,ManualEntityServiceTest,SwitchCommandServiceTest,SwitchableEntitiesTest'
```
Erwartet: alle PASS. (Der volle `mvn test` schlägt lokal nur bei den bekannten DB-Integrationstests fehl.)

- [ ] **Step 2: Frontend-Gesamttests**

```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless
```
Erwartet: alle PASS.

- [ ] **Step 3: End-to-End-Sichtprüfung** — Backend starten (`mvn spring-boot:run`), Frontend starten (`npm start`), Dashboard öffnen: vier Knöpfe „Abwesend / Toni allein / Nachtmodus / Ausschalten", Klick schaltet um (Knopf leuchtet), Modi fehlen in der Schalter-Kachel, `GET /api/v1/modes` liefert die vier Einträge.

- [ ] **Step 4: Plan-Checkboxen abhaken und committen**

```bash
git add docs/superpowers/plans/2026-07-17-dashboard-modes.md
git commit -m "docs: Plan fuer Dashboard-Modi abgeschlossen"
```
