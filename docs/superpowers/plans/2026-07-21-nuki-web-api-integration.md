# Nuki-Web-API-Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nuki Smart Lock Pro über die Nuki Web API integrieren: Status als `lock.*`-Entität, Sperren/Entsperren aus UI und Flows, Dashboard-Kachel mit Bestätigungsdialog.

**Architecture:** Neues Backend-Package `nuki/` (Properties → ApiClient → PollingService/LockService → Controller) meldet Zustände an den Entity-State-Layer (neue Domain `LOCK`, neue Source `NUKI`). Flow-Aktion als `NodeHandler`-Bean. Frontend: `NukiService` + Umbau der statischen „System gesichert“-Footer-Karte im Dashboard zur echten Türschloss-Kachel.

**Tech Stack:** Spring Boot 3.4.1 / Java 21, RestTemplate (RestTemplateBuilder), JUnit 5 + Mockito + MockRestServiceServer, Angular 19 standalone, RxJS.

**Spec:** `docs/superpowers/specs/2026-07-21-nuki-web-api-integration-design.md`

---

## Wichtige Umgebungs-Hinweise (vor jedem Backend-Build!)

- Maven braucht JDK 21. In **Bash** vor jedem `mvn`-Aufruf:
  ```bash
  export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
  ```
  Maven liegt unter `C:\Users\bened\apache-maven-3.9.11\bin\mvn` (auf PATH), kein `mvnw`. Aus `backend/` heraus ausführen.
- Die lokalen Tests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen auf dieser Maschine **immer** fehl („Access denied for user 'root'@'localhost'“ – Test-DB nicht erreichbar). Das ist vorbestehend und zu ignorieren; deshalb laufen Testbefehle hier immer klassenweise (`mvn test -Dtest=...`).
- Alle JPA-Repositories müssen in `com.household.manager.repository` liegen (hier nicht relevant – diese Integration hat keine Persistenz).
- Frontend-Tests: aus `frontend/` mit `npx ng test --watch=false --browsers=ChromeHeadless`.

## Nuki-Web-API-Referenz (für alle Tasks)

- Basis-URL `https://api.nuki.io`, Auth-Header `Authorization: Bearer <token>`.
- `GET /smartlock` → JSON-Array, relevante Felder pro Schloss:
  ```json
  {
    "smartlockId": 17958143231,
    "name": "Haustür",
    "state": { "state": 1, "doorState": 2, "batteryCritical": false, "batteryCharge": 85 }
  }
  ```
- Lock-State-Codes: 0 uncalibrated, 1 locked, 2 unlocking, 3 unlocked, 4 locking, 5 unlatched, 6 unlocked (lock’n’go), 7 unlatching, 254 motor blocked (jammed), 255 undefined.
- Door-State-Codes: 0 unavailable, 1 deactivated, 2 closed, 3 open, 4 unknown, 5 calibrating.
- `POST /smartlock/{smartlockId}/action` mit Body `{"action": <code>}` → HTTP 204. Codes: **1 = unlock, 2 = lock, 3 = unlatch**.
- `smartlockId` ist **long** (Werte > 2^31 kommen vor) — nie `int` verwenden.

---

### Task 1: Domain `LOCK` und Source `NUKI` im Entity-State-Layer

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntityDomain.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`

- [ ] **Step 1: `LOCK` in EntityDomain ergänzen**

In `EntityDomain.java` nach `BINARY_SENSOR,` einfügen:

```java
    /** Türschloss (z. B. Nuki); State = locked/unlocked/unlatched/…. */
    LOCK,
```

(Die Enum-Reihenfolge ist unkritisch — `idPrefix()` leitet das ID-Präfix `lock` automatisch ab, `isManualHelper()` bleibt unverändert false für `LOCK`.)

- [ ] **Step 2: `NUKI` in EntitySource ergänzen**

In `EntitySource.java` nach `TABLET,` einfügen:

```java
    /** Nuki Smart Lock (Web API). */
    NUKI,
```

- [ ] **Step 3: Kompilieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test-compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntityDomain.java backend/src/main/java/com/household/manager/entitystate/EntitySource.java
git commit -m "feat(entitystate): Domain LOCK und Source NUKI"
```

---

### Task 2: Nuki-DTOs und NukiEntityMapper

**Files:**
- Create: `backend/src/main/java/com/household/manager/nuki/dto/NukiSmartlockStateDto.java`
- Create: `backend/src/main/java/com/household/manager/nuki/dto/NukiSmartlockDto.java`
- Create: `backend/src/main/java/com/household/manager/nuki/NukiLockStates.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/mapper/NukiEntityMapper.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/NukiEntityMapperTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/mapper/NukiEntityMapperTest.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import com.household.manager.nuki.dto.NukiSmartlockStateDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NukiEntityMapperTest {

    private final NukiEntityMapper mapper = new NukiEntityMapper();

    private NukiSmartlockDto smartlock(int state, Integer doorState) {
        return new NukiSmartlockDto(17958143231L, "Haustür",
                new NukiSmartlockStateDto(state, doorState, false, 85));
    }

    @Test
    void mapsLockedStateWithBatteryAttributes() {
        List<EntityStateUpdate> updates = mapper.map(smartlock(1, null));

        assertEquals(1, updates.size());
        EntityStateUpdate lock = updates.get(0);
        assertEquals("lock.nuki_17958143231", lock.entityId());
        assertEquals(EntityDomain.LOCK, lock.domain());
        assertEquals(EntitySource.NUKI, lock.source());
        assertEquals("17958143231", lock.sourceRef());
        assertEquals("Haustür", lock.friendlyName());
        assertEquals("locked", lock.state());
        assertEquals(85, lock.attributes().get("batteryCharge"));
        assertEquals(false, lock.attributes().get("batteryCritical"));
    }

    @Test
    void mapsAllLockStates() {
        assertEquals("uncalibrated", mapper.map(smartlock(0, null)).get(0).state());
        assertEquals("locked", mapper.map(smartlock(1, null)).get(0).state());
        assertEquals("unlocking", mapper.map(smartlock(2, null)).get(0).state());
        assertEquals("unlocked", mapper.map(smartlock(3, null)).get(0).state());
        assertEquals("locking", mapper.map(smartlock(4, null)).get(0).state());
        assertEquals("unlatched", mapper.map(smartlock(5, null)).get(0).state());
        assertEquals("unlocked", mapper.map(smartlock(6, null)).get(0).state());
        assertEquals("unlatching", mapper.map(smartlock(7, null)).get(0).state());
        assertEquals("jammed", mapper.map(smartlock(254, null)).get(0).state());
        assertEquals("unknown", mapper.map(smartlock(255, null)).get(0).state());
    }

    @Test
    void mapsDoorSensorWithOnEqualsOpenSemantics() {
        List<EntityStateUpdate> open = mapper.map(smartlock(1, 3));
        assertEquals(2, open.size());
        EntityStateUpdate door = open.get(1);
        assertEquals("binary_sensor.nuki_17958143231_door", door.entityId());
        assertEquals(EntityDomain.BINARY_SENSOR, door.domain());
        assertEquals("on", door.state());
        assertEquals("Haustür Tür", door.friendlyName());

        assertEquals("off", mapper.map(smartlock(1, 2)).get(1).state());
        assertEquals("unknown", mapper.map(smartlock(1, 4)).get(1).state());
    }

    @Test
    void skipsDoorSensorWhenDeactivatedOrUnavailable() {
        assertEquals(1, mapper.map(smartlock(1, null)).size());
        assertEquals(1, mapper.map(smartlock(1, 0)).size());
        assertEquals(1, mapper.map(smartlock(1, 1)).size());
    }

    @Test
    void survivesMissingStateObject() {
        NukiSmartlockDto broken = new NukiSmartlockDto(1L, "Kaputt", null);
        List<EntityStateUpdate> updates = mapper.map(broken);
        assertEquals(1, updates.size());
        assertEquals("unknown", updates.get(0).state());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=NukiEntityMapperTest -q
```
Expected: COMPILATION ERROR (Klassen existieren noch nicht)

- [ ] **Step 3: DTOs implementieren**

`backend/src/main/java/com/household/manager/nuki/dto/NukiSmartlockStateDto.java`:

```java
package com.household.manager.nuki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Zustandsobjekt eines Smartlocks aus der Nuki Web API.
 * Codes: state 0=uncalibrated 1=locked 2=unlocking 3=unlocked 4=locking
 * 5=unlatched 6=unlocked(lock'n'go) 7=unlatching 254=motor blocked;
 * doorState 0=unavailable 1=deactivated 2=closed 3=open 4=unknown 5=calibrating.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NukiSmartlockStateDto(
        Integer state,
        Integer doorState,
        Boolean batteryCritical,
        Integer batteryCharge
) {
}
```

`backend/src/main/java/com/household/manager/nuki/dto/NukiSmartlockDto.java`:

```java
package com.household.manager.nuki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Ein Smartlock aus {@code GET /smartlock} der Nuki Web API. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NukiSmartlockDto(
        long smartlockId,
        String name,
        NukiSmartlockStateDto state
) {
}
```

- [ ] **Step 4: NukiLockStates implementieren**

`backend/src/main/java/com/household/manager/nuki/NukiLockStates.java`:

```java
package com.household.manager.nuki;

/** Übersetzt die numerischen Nuki-Zustandscodes in Entity-States. */
public final class NukiLockStates {

    public static final String UNKNOWN = "unknown";

    private NukiLockStates() {
    }

    /** Lock-State-Code → Entity-State (z. B. 1 → "locked"). */
    public static String lockState(Integer code) {
        if (code == null) {
            return UNKNOWN;
        }
        return switch (code) {
            case 0 -> "uncalibrated";
            case 1 -> "locked";
            case 2 -> "unlocking";
            case 3, 6 -> "unlocked";
            case 4 -> "locking";
            case 5 -> "unlatched";
            case 7 -> "unlatching";
            case 254 -> "jammed";
            default -> UNKNOWN;
        };
    }

    /**
     * Door-State-Code → binary_sensor-State mit on=offen-Semantik,
     * oder null wenn kein Türsensor vorhanden/aktiv ist (Code 0/1/fehlend).
     */
    public static String doorState(Integer code) {
        if (code == null || code == 0 || code == 1) {
            return null;
        }
        return switch (code) {
            case 2 -> "off";
            case 3 -> "on";
            default -> UNKNOWN;
        };
    }
}
```

- [ ] **Step 5: NukiEntityMapper implementieren**

`backend/src/main/java/com/household/manager/entitystate/mapper/NukiEntityMapper.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.nuki.NukiLockStates;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import com.household.manager.nuki.dto.NukiSmartlockStateDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mappt ein Nuki-Smartlock auf Entity-Zustände: das Schloss als
 * {@code lock.nuki_<smartlockId>}, der optionale Türsensor als
 * {@code binary_sensor.nuki_<smartlockId>_door} (on = offen).
 */
@Component
public class NukiEntityMapper {

    public List<EntityStateUpdate> map(NukiSmartlockDto smartlock) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        String ref = String.valueOf(smartlock.smartlockId());
        NukiSmartlockStateDto state = smartlock.state();

        Map<String, Object> attributes = new HashMap<>();
        if (state != null && state.batteryCharge() != null) {
            attributes.put("batteryCharge", state.batteryCharge());
        }
        if (state != null && state.batteryCritical() != null) {
            attributes.put("batteryCritical", state.batteryCritical());
        }

        updates.add(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.LOCK, EntitySource.NUKI, ref, null))
                .domain(EntityDomain.LOCK)
                .source(EntitySource.NUKI)
                .sourceRef(ref)
                .friendlyName(smartlock.name())
                .state(NukiLockStates.lockState(state != null ? state.state() : null))
                .attributes(attributes)
                .build());

        String doorState = NukiLockStates.doorState(state != null ? state.doorState() : null);
        if (doorState != null) {
            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.NUKI, ref, "door"))
                    .domain(EntityDomain.BINARY_SENSOR)
                    .source(EntitySource.NUKI)
                    .sourceRef(ref)
                    .friendlyName(smartlock.name() + " Tür")
                    .state(doorState)
                    .attributes(Map.of("deviceClass", "door"))
                    .build());
        }
        return updates;
    }
}
```

- [ ] **Step 6: Test laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=NukiEntityMapperTest -q
```
Expected: Tests run: 5, Failures: 0, Errors: 0 → BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/nuki/ backend/src/main/java/com/household/manager/entitystate/mapper/NukiEntityMapper.java backend/src/test/java/com/household/manager/entitystate/mapper/NukiEntityMapperTest.java
git commit -m "feat(nuki): DTOs, Zustandscodes und NukiEntityMapper"
```

---

### Task 3: NukiProperties und NukiApiClient

**Files:**
- Create: `backend/src/main/java/com/household/manager/nuki/NukiProperties.java`
- Create: `backend/src/main/java/com/household/manager/nuki/NukiException.java`
- Create: `backend/src/main/java/com/household/manager/nuki/NukiApiClient.java`
- Test: `backend/src/test/java/com/household/manager/nuki/NukiApiClientTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/nuki/NukiApiClientTest.java`:

```java
package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiSmartlockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NukiApiClientTest {

    private NukiApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        NukiProperties properties = new NukiProperties();
        properties.setApiToken("test-token");
        properties.setBaseUrl("https://api.nuki.io");
        client = new NukiApiClient(properties, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void listSmartlocksParsesResponseAndSendsBearerToken() {
        server.expect(requestTo("https://api.nuki.io/smartlock"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        [{"smartlockId": 17958143231, "name": "Haustür", "unknownField": true,
                          "state": {"state": 1, "doorState": 2, "batteryCritical": false,
                                    "batteryCharge": 85, "alsoUnknown": 1}}]
                        """, MediaType.APPLICATION_JSON));

        List<NukiSmartlockDto> locks = client.listSmartlocks();

        assertEquals(1, locks.size());
        assertEquals(17958143231L, locks.get(0).smartlockId());
        assertEquals("Haustür", locks.get(0).name());
        assertEquals(1, locks.get(0).state().state());
        server.verify();
    }

    @Test
    void sendActionPostsActionCode() {
        server.expect(requestTo("https://api.nuki.io/smartlock/17958143231/action"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(jsonPath("$.action").value(2))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertDoesNotThrow(() -> client.sendAction(17958143231L, 2));
        server.verify();
    }

    @Test
    void wrapsHttpErrorsInNukiException() {
        server.expect(requestTo("https://api.nuki.io/smartlock"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(NukiException.class, () -> client.listSmartlocks());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=NukiApiClientTest -q
```
Expected: COMPILATION ERROR

- [ ] **Step 3: NukiProperties implementieren**

`backend/src/main/java/com/household/manager/nuki/NukiProperties.java`:

```java
package com.household.manager.nuki;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "nuki")
@Data
public class NukiProperties {

    private boolean enabled = true;
    /** Persönlicher API-Token von https://web.nuki.io (Smartlock lesen + bedienen). */
    private String apiToken = "";
    private String baseUrl = "https://api.nuki.io";
    private long pollIntervalMs = 30000;
    private long initialDelayMs = 15000;
    private int httpTimeoutMs = 5000;

    /** True, wenn die Integration aktiv und ein Token hinterlegt ist. */
    public boolean isConfigured() {
        return enabled && apiToken != null && !apiToken.isBlank();
    }
}
```

- [ ] **Step 4: NukiException implementieren**

`backend/src/main/java/com/household/manager/nuki/NukiException.java`:

```java
package com.household.manager.nuki;

/** Fehler bei der Kommunikation mit der Nuki Web API. */
public class NukiException extends RuntimeException {

    public NukiException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: NukiApiClient implementieren**

`backend/src/main/java/com/household/manager/nuki/NukiApiClient.java`:

```java
package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiSmartlockDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Dünner HTTP-Client für die Nuki Web API (Bearer-Auth).
 * Action-Codes: 1 = entsperren, 2 = verriegeln, 3 = Tür öffnen.
 */
@Component
@Slf4j
public class NukiApiClient {

    private final NukiProperties properties;
    private final RestTemplate restTemplate;

    public NukiApiClient(NukiProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .build();
    }

    public List<NukiSmartlockDto> listSmartlocks() {
        String url = properties.getBaseUrl() + "/smartlock";
        try {
            var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<List<NukiSmartlockDto>>() {
                    });
            return response.getBody() != null ? response.getBody() : List.of();
        } catch (RestClientException ex) {
            throw new NukiException("Nuki Web API nicht erreichbar: " + ex.getMessage(), ex);
        }
    }

    public void sendAction(long smartlockId, int actionCode) {
        String url = properties.getBaseUrl() + "/smartlock/" + smartlockId + "/action";
        try {
            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(Map.of("action", actionCode), headers), Void.class);
        } catch (RestClientException ex) {
            throw new NukiException("Nuki-Aktion fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiToken());
        return headers;
    }

    /** Nur für Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
```

- [ ] **Step 6: Test laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=NukiApiClientTest -q
```
Expected: Tests run: 3, Failures: 0, Errors: 0 → BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/nuki/ backend/src/test/java/com/household/manager/nuki/
git commit -m "feat(nuki): Properties und Web-API-Client"
```

---

### Task 4: NukiPollingService

**Files:**
- Create: `backend/src/main/java/com/household/manager/nuki/NukiPollingService.java`
- Test: `backend/src/test/java/com/household/manager/nuki/NukiPollingServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/nuki/NukiPollingServiceTest.java`:

```java
package com.household.manager.nuki;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.NukiEntityMapper;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NukiPollingServiceTest {

    @Mock
    private NukiApiClient apiClient;
    @Mock
    private NukiEntityMapper mapper;
    @Mock
    private EntityStateService entityStateService;

    private NukiProperties properties;
    private NukiPollingService service;

    private static final EntityStateUpdate LOCK_UPDATE = EntityStateUpdate.builder()
            .entityId("lock.nuki_1")
            .domain(EntityDomain.LOCK)
            .source(EntitySource.NUKI)
            .sourceRef("1")
            .friendlyName("Haustür")
            .state("locked")
            .attributes(Map.of())
            .build();

    @BeforeEach
    void setUp() {
        properties = new NukiProperties();
        properties.setApiToken("token");
        service = new NukiPollingService(properties, apiClient, mapper, entityStateService);
    }

    @Test
    void pollReportsMappedStates() {
        NukiSmartlockDto dto = new NukiSmartlockDto(1L, "Haustür", null);
        when(apiClient.listSmartlocks()).thenReturn(List.of(dto));
        when(mapper.map(dto)).thenReturn(List.of(LOCK_UPDATE));

        service.poll();

        verify(entityStateService).reportState(LOCK_UPDATE);
    }

    @Test
    void pollFailureMarksLastKnownEntitiesUnavailable() {
        NukiSmartlockDto dto = new NukiSmartlockDto(1L, "Haustür", null);
        when(apiClient.listSmartlocks())
                .thenReturn(List.of(dto))
                .thenThrow(new NukiException("down", null));
        when(mapper.map(dto)).thenReturn(List.of(LOCK_UPDATE));

        service.poll();
        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        EntityStateUpdate unavailable = captor.getAllValues().get(1);
        assertEquals("lock.nuki_1", unavailable.entityId());
        assertEquals("unavailable", unavailable.state());
    }

    @Test
    void doesNothingWithoutToken() {
        properties.setApiToken("");
        service.poll();
        verifyNoInteractions(apiClient, entityStateService);
    }

    @Test
    void mapperErrorsDoNotAbortPolling() {
        NukiSmartlockDto broken = new NukiSmartlockDto(1L, "Kaputt", null);
        NukiSmartlockDto ok = new NukiSmartlockDto(2L, "Haustür", null);
        when(apiClient.listSmartlocks()).thenReturn(List.of(broken, ok));
        when(mapper.map(broken)).thenThrow(new IllegalStateException("boom"));
        when(mapper.map(ok)).thenReturn(List.of(LOCK_UPDATE));

        assertDoesNotThrow(() -> service.poll());
        verify(entityStateService).reportState(LOCK_UPDATE);
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=NukiPollingServiceTest -q
```
Expected: COMPILATION ERROR

- [ ] **Step 3: NukiPollingService implementieren**

`backend/src/main/java/com/household/manager/nuki/NukiPollingService.java`:

```java
package com.household.manager.nuki;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.NukiEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pollt die Nuki Web API und spiegelt die Schlösser in den Entity-State-Layer.
 * Bei Cloud-Fehlern werden die zuletzt gemeldeten Entitäten auf
 * {@code unavailable} gesetzt; das Polling bricht dadurch nie.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NukiPollingService {

    private final NukiProperties properties;
    private final NukiApiClient apiClient;
    private final NukiEntityMapper mapper;
    private final EntityStateService entityStateService;

    /** Zuletzt erfolgreich gemeldete Updates; Basis für die unavailable-Markierung. */
    private volatile List<EntityStateUpdate> lastUpdates = List.of();

    @Scheduled(fixedDelayString = "${nuki.poll-interval-ms:30000}",
            initialDelayString = "${nuki.initial-delay-ms:15000}")
    public void poll() {
        if (!properties.isConfigured()) {
            return;
        }
        try {
            List<EntityStateUpdate> updates = new ArrayList<>();
            for (var smartlock : apiClient.listSmartlocks()) {
                try {
                    updates.addAll(mapper.map(smartlock));
                } catch (Exception ex) {
                    log.warn("Failed to map Nuki smartlock {}: {}", smartlock.smartlockId(), ex.getMessage());
                }
            }
            updates.forEach(entityStateService::reportState);
            lastUpdates = List.copyOf(updates);
        } catch (NukiException ex) {
            log.warn("Nuki polling failed: {}", ex.getMessage());
            markUnavailable();
        }
    }

    private void markUnavailable() {
        for (EntityStateUpdate update : lastUpdates) {
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(update.entityId())
                    .domain(update.domain())
                    .source(update.source())
                    .sourceRef(update.sourceRef())
                    .friendlyName(update.friendlyName())
                    .state("unavailable")
                    .attributes(update.attributes())
                    .build());
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=NukiPollingServiceTest -q
```
Expected: Tests run: 4, Failures: 0, Errors: 0 → BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/nuki/NukiPollingService.java backend/src/test/java/com/household/manager/nuki/NukiPollingServiceTest.java
git commit -m "feat(nuki): Polling-Service mit unavailable-Markierung"
```

---

### Task 5: NukiLockService, Controller und Exception-Handling

**Files:**
- Create: `backend/src/main/java/com/household/manager/nuki/NukiLockAction.java`
- Create: `backend/src/main/java/com/household/manager/nuki/dto/NukiLockResponse.java`
- Create: `backend/src/main/java/com/household/manager/nuki/dto/NukiActionRequest.java`
- Create: `backend/src/main/java/com/household/manager/nuki/NukiLockService.java`
- Create: `backend/src/main/java/com/household/manager/nuki/NukiController.java`
- Modify: `backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/household/manager/nuki/NukiLockServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/nuki/NukiLockServiceTest.java`:

```java
package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import com.household.manager.nuki.dto.NukiSmartlockStateDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NukiLockServiceTest {

    @Mock
    private NukiApiClient apiClient;
    @Mock
    private NukiPollingService pollingService;
    @InjectMocks
    private NukiLockService service;

    @Test
    void listLocksMapsToResponse() {
        when(apiClient.listSmartlocks()).thenReturn(List.of(
                new NukiSmartlockDto(17958143231L, "Haustür",
                        new NukiSmartlockStateDto(1, 2, false, 85))));

        List<NukiLockResponse> locks = service.listLocks();

        assertEquals(1, locks.size());
        NukiLockResponse lock = locks.get(0);
        assertEquals(17958143231L, lock.smartlockId());
        assertEquals("Haustür", lock.name());
        assertEquals("locked", lock.state());
        assertEquals("off", lock.doorState());
        assertEquals(85, lock.batteryCharge());
        assertFalse(lock.batteryCritical());
    }

    @Test
    void listLocksHandlesMissingState() {
        when(apiClient.listSmartlocks()).thenReturn(List.of(
                new NukiSmartlockDto(1L, "Kaputt", null)));

        NukiLockResponse lock = service.listLocks().get(0);
        assertEquals("unknown", lock.state());
        assertNull(lock.doorState());
        assertNull(lock.batteryCharge());
        assertFalse(lock.batteryCritical());
    }

    @Test
    void executeActionSendsCodeAndRefreshesState() {
        service.executeAction(42L, NukiLockAction.LOCK);

        verify(apiClient).sendAction(42L, 2);
        verify(pollingService).poll();
    }

    @Test
    void actionCodesMatchNukiApi() {
        assertEquals(1, NukiLockAction.UNLOCK.getApiCode());
        assertEquals(2, NukiLockAction.LOCK.getApiCode());
        assertEquals(3, NukiLockAction.UNLATCH.getApiCode());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=NukiLockServiceTest -q
```
Expected: COMPILATION ERROR

- [ ] **Step 3: NukiLockAction implementieren**

`backend/src/main/java/com/household/manager/nuki/NukiLockAction.java`:

```java
package com.household.manager.nuki;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Steuerbare Schloss-Aktionen mit ihren Nuki-Web-API-Action-Codes. */
@Getter
@RequiredArgsConstructor
public enum NukiLockAction {
    UNLOCK(1),
    LOCK(2),
    /** Tür öffnen (Falle ziehen). */
    UNLATCH(3);

    private final int apiCode;
}
```

- [ ] **Step 4: Response-/Request-DTOs implementieren**

`backend/src/main/java/com/household/manager/nuki/dto/NukiLockResponse.java`:

```java
package com.household.manager.nuki.dto;

/** Schloss-Zustand für das Frontend (Dashboard-Kachel). */
public record NukiLockResponse(
        long smartlockId,
        String name,
        String state,
        String doorState,
        Integer batteryCharge,
        boolean batteryCritical
) {
}
```

`backend/src/main/java/com/household/manager/nuki/dto/NukiActionRequest.java`:

```java
package com.household.manager.nuki.dto;

import com.household.manager.nuki.NukiLockAction;
import jakarta.validation.constraints.NotNull;

/** Aktionsanforderung an ein Schloss ({@code {"action": "LOCK"}}). */
public record NukiActionRequest(@NotNull NukiLockAction action) {
}
```

- [ ] **Step 5: NukiLockService implementieren**

`backend/src/main/java/com/household/manager/nuki/NukiLockService.java`:

```java
package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import com.household.manager.nuki.dto.NukiSmartlockStateDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachlogik für die Schloss-Endpoints: Liste für die Kachel und
 * Aktionen mit sofortigem Nachpollen (Entitäten hängen sonst bis zu
 * einem Poll-Intervall hinterher).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NukiLockService {

    private final NukiApiClient apiClient;
    private final NukiPollingService pollingService;

    public List<NukiLockResponse> listLocks() {
        return apiClient.listSmartlocks().stream()
                .map(this::toResponse)
                .toList();
    }

    public void executeAction(long smartlockId, NukiLockAction action) {
        log.info("Nuki action {} for smartlock {}", action, smartlockId);
        apiClient.sendAction(smartlockId, action.getApiCode());
        pollingService.poll();
    }

    private NukiLockResponse toResponse(NukiSmartlockDto smartlock) {
        NukiSmartlockStateDto state = smartlock.state();
        return new NukiLockResponse(
                smartlock.smartlockId(),
                smartlock.name(),
                NukiLockStates.lockState(state != null ? state.state() : null),
                NukiLockStates.doorState(state != null ? state.doorState() : null),
                state != null ? state.batteryCharge() : null,
                state != null && Boolean.TRUE.equals(state.batteryCritical()));
    }
}
```

- [ ] **Step 6: NukiController implementieren**

`backend/src/main/java/com/household/manager/nuki/NukiController.java`:

```java
package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiActionRequest;
import com.household.manager.nuki.dto.NukiLockResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST-Endpoints für die Nuki-Schlösser (Dashboard-Kachel). */
@RestController
@RequestMapping("/v1/nuki")
@RequiredArgsConstructor
public class NukiController {

    private final NukiLockService lockService;

    @GetMapping("/locks")
    public List<NukiLockResponse> getLocks() {
        return lockService.listLocks();
    }

    @PostMapping("/locks/{smartlockId}/actions")
    public ResponseEntity<Void> executeAction(@PathVariable long smartlockId,
                                              @Valid @RequestBody NukiActionRequest request) {
        lockService.executeAction(smartlockId, request.action());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 7: GlobalExceptionHandler erweitern**

In `GlobalExceptionHandler.java` Import ergänzen:

```java
import com.household.manager.nuki.NukiException;
```

und nach der `handleTapoException`-Methode einfügen:

```java
    @ExceptionHandler(NukiException.class)
    public ResponseEntity<ErrorResponse> handleNukiException(
            NukiException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Nuki communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }
```

- [ ] **Step 8: Tests laufen lassen — müssen grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest="NukiLockServiceTest,NukiApiClientTest,NukiPollingServiceTest,NukiEntityMapperTest" -q
```
Expected: alle Tests grün → BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/household/manager/nuki/ backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java backend/src/test/java/com/household/manager/nuki/
git commit -m "feat(nuki): LockService, REST-Controller und Fehler-Mapping"
```

---

### Task 6: Flow-Aktions-Node `nuki-lock-action`

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/NukiLockActionNodeHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/NukiLockActionNodeHandlerTest.java`

**Hinweis:** `smartlockId` wird in der Node-Config als **String** geführt und mit `Long.parseLong` geparst — `NodeConfig.integer()` ist int-basiert und Nuki-IDs überschreiten den int-Bereich.

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/flowengine/nodes/NukiLockActionNodeHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NukiLockActionNodeHandlerTest {

    @Mock
    private NukiLockService lockService;

    private NukiLockActionNodeHandler handler() {
        return new NukiLockActionNodeHandler(lockService);
    }

    private final FlowMessage msg = FlowMessage.of(Map.of());

    @Test
    void locksAndPassesMessageThrough() {
        NodeResult result = handler().handle(msg,
                new NodeConfig(Map.of("smartlockId", "17958143231", "action", "lock")), null);

        verify(lockService).executeAction(17958143231L, NukiLockAction.LOCK);
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void unlocksAndUnlatches() {
        handler().handle(msg, new NodeConfig(Map.of("smartlockId", "42", "action", "unlock")), null);
        verify(lockService).executeAction(42L, NukiLockAction.UNLOCK);

        handler().handle(msg, new NodeConfig(Map.of("smartlockId", "42", "action", "unlatch")), null);
        verify(lockService).executeAction(42L, NukiLockAction.UNLATCH);
    }

    @Test
    void validateRequiresParseableIdAndValidAction() {
        assertEquals(2, handler().validate(NodeConfig.empty()).size());
        assertFalse(handler().validate(
                new NodeConfig(Map.of("smartlockId", "abc", "action", "lock"))).isEmpty());
        assertFalse(handler().validate(
                new NodeConfig(Map.of("smartlockId", "42", "action", "toggle"))).isEmpty());
        assertTrue(handler().validate(
                new NodeConfig(Map.of("smartlockId", "42", "action", "lock"))).isEmpty());
    }

    @Test
    void typeAndFieldsAreDescribed() {
        assertEquals("nuki-lock-action", handler().type());
        assertEquals(2, handler().fields().size());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=NukiLockActionNodeHandlerTest -q
```
Expected: COMPILATION ERROR

- [ ] **Step 3: NodeHandler implementieren**

`backend/src/main/java/com/household/manager/flowengine/nodes/NukiLockActionNodeHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aktions-Node: steuert ein Nuki-Schloss (verriegeln/entsperren/Tür öffnen).
 * smartlockId als String, weil Nuki-IDs den int-Bereich von
 * {@link NodeConfig#integer} überschreiten.
 */
@Component
@RequiredArgsConstructor
public class NukiLockActionNodeHandler implements NodeHandler {

    private static final List<String> ACTIONS = List.of("lock", "unlock", "unlatch");

    private final NukiLockService lockService;

    @Override
    public String type() {
        return "nuki-lock-action";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        String smartlockId = config.string("smartlockId").orElse(null);
        if (smartlockId == null || !smartlockId.matches("\\d+")) {
            errors.add("smartlockId fehlt oder ist keine Zahl");
        }
        String action = config.string("action").orElse(null);
        if (action == null || !ACTIONS.contains(action)) {
            errors.add("action muss 'lock', 'unlock' oder 'unlatch' sein");
        }
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        long smartlockId = Long.parseLong(config.string("smartlockId").orElseThrow());
        NukiLockAction action = NukiLockAction.valueOf(
                config.string("action").orElseThrow().toUpperCase(Locale.ROOT));
        lockService.executeAction(smartlockId, action);
        return NodeResult.single(message);
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("smartlockId", "Smartlock-ID", NodeFieldType.STRING, true),
                NodeFieldDescriptor.enumField("action", "Aktion", true, ACTIONS));
    }
}
```

- [ ] **Step 4: Tests laufen lassen — muss grün sein (inkl. Katalog-Test)**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest="NukiLockActionNodeHandlerTest,NodeCatalogFieldsTest,FlowValidatorTest" -q
```
Expected: alle grün → BUILD SUCCESS. Falls `NodeCatalogFieldsTest` eine feste Node-Typ-Liste prüft und fehlschlägt: den neuen Typ `nuki-lock-action` dort ergänzen (Testdatei lesen, Muster der anderen Typen übernehmen).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes/NukiLockActionNodeHandler.java backend/src/test/java/com/household/manager/flowengine/nodes/NukiLockActionNodeHandlerTest.java
git commit -m "feat(flowengine): nuki-lock-action-Node"
```

---

### Task 7: Konfiguration (application.properties, docker-compose)

**Files:**
- Modify: `backend/src/main/resources/application.properties`
- Modify: `docker-compose.yml`

- [ ] **Step 1: application.properties erweitern**

Ans Ende von `backend/src/main/resources/application.properties` anfügen (nach dem Alexa-Air-Quality-Block):

```properties

# Nuki Smart Lock (Web API, Cloud-Polling)
nuki.enabled=${NUKI_ENABLED:true}
nuki.api-token=${NUKI_API_TOKEN:}
nuki.base-url=https://api.nuki.io
nuki.poll-interval-ms=30000
nuki.initial-delay-ms=15000
nuki.http-timeout-ms=5000
```

- [ ] **Step 2: docker-compose.yml erweitern**

`docker-compose.yml` lesen, im Backend-Service unter `environment:` analog zu den bestehenden Einträgen ergänzen:

```yaml
      NUKI_API_TOKEN: ${NUKI_API_TOKEN:-}
```

(Exakte Einrückung und Stil — `KEY: value` vs. `- KEY=value` — an den vorhandenen Einträgen der Datei ausrichten.)

- [ ] **Step 3: Anwendung baut**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test-compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/application.properties docker-compose.yml
git commit -m "feat(nuki): Konfiguration fuer Web-API-Token und Polling"
```

---

### Task 8: Frontend — NukiService und Dashboard-Kachel

**Files:**
- Create: `frontend/src/app/models/nuki.model.ts`
- Create: `frontend/src/app/services/nuki.service.ts`
- Test: `frontend/src/app/services/nuki.service.spec.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (Footer-Karte `lumina__secured` + neuer Bestätigungsdialog)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`

- [ ] **Step 1: Modell anlegen**

`frontend/src/app/models/nuki.model.ts`:

```typescript
/** Nuki-Schloss, wie es GET /api/v1/nuki/locks liefert. */
export interface NukiLock {
  smartlockId: number;
  name: string;
  state: string;
  /** 'on' = Tür offen, 'off' = zu, null = kein Türsensor. */
  doorState: string | null;
  batteryCharge: number | null;
  batteryCritical: boolean;
}

export type NukiLockActionType = 'LOCK' | 'UNLOCK' | 'UNLATCH';
```

- [ ] **Step 2: Failing Service-Test schreiben**

`frontend/src/app/services/nuki.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NukiService } from './nuki.service';
import { NukiLock } from '../models/nuki.model';

describe('NukiService', () => {
  let service: NukiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(NukiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt die Schloesser', () => {
    const locks: NukiLock[] = [
      { smartlockId: 1, name: 'Haustür', state: 'locked', doorState: 'off', batteryCharge: 85, batteryCritical: false }
    ];

    service.getLocks().subscribe(result => expect(result).toEqual(locks));

    const req = httpMock.expectOne('/api/v1/nuki/locks');
    expect(req.request.method).toBe('GET');
    req.flush(locks);
  });

  it('sendet eine Aktion', () => {
    service.sendAction(1, 'LOCK').subscribe();

    const req = httpMock.expectOne('/api/v1/nuki/locks/1/actions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ action: 'LOCK' });
    req.flush(null);
  });
});
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless
```
Expected: FAIL (NukiService existiert nicht / Kompilierfehler)

- [ ] **Step 4: NukiService implementieren**

`frontend/src/app/services/nuki.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { NukiLock, NukiLockActionType } from '../models/nuki.model';

/** REST-Service für die Nuki-Schlösser (Türschloss-Kachel). */
@Injectable({ providedIn: 'root' })
export class NukiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/nuki';

  getLocks(): Observable<NukiLock[]> {
    return this.http.get<NukiLock[]>(`${this.baseUrl}/locks`).pipe(
      catchError(this.handleError)
    );
  }

  sendAction(smartlockId: number, action: NukiLockActionType): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/locks/${smartlockId}/actions`, { action }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Nuki-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Nuki-Anfrage.'));
  }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless
```
Expected: alle Tests SUCCESS

- [ ] **Step 6: Commit (Service)**

```bash
git add frontend/src/app/models/nuki.model.ts frontend/src/app/services/nuki.service.ts frontend/src/app/services/nuki.service.spec.ts
git commit -m "feat(frontend): NukiService und Modelle"
```

- [ ] **Step 7: Dashboard-Komponente erweitern (TS)**

In `frontend/src/app/pages/dashboard/dashboard.component.ts`:

1. Imports ergänzen:
```typescript
import { NukiService } from '../../services/nuki.service';
import { NukiLock, NukiLockActionType } from '../../models/nuki.model';
```
2. Service injizieren (bei den anderen `inject`-Feldern):
```typescript
  private readonly nukiService = inject(NukiService);
```
3. Subscription-Feld ergänzen (bei den anderen Subscriptions):
```typescript
  private nukiSubscription?: Subscription;
```
4. Konstante ergänzen (bei den anderen Konstanten):
```typescript
  /** Aktualisierungsintervall der Türschloss-Kachel (30 s). */
  private static readonly NUKI_REFRESH_MS = 30000;
```
5. State-Felder ergänzen (nach den Modus-Feldern):
```typescript
  /** Nuki-Schlösser für die Türschloss-Kachel. */
  nukiLocks: NukiLock[] = [];
  /** True, solange noch keine Nuki-Antwort vorliegt (unterscheidet "lädt" von "keine Schlösser"). */
  nukiLoading = true;
  nukiError: string | null = null;
  /** Smartlock-IDs mit laufender Aktion (verhindert Doppelklicks). */
  readonly pendingNukiIds = new Set<number>();
  /** Zu bestätigende Aktion (Entsperren/Tür öffnen); null = kein Dialog offen. */
  nukiConfirm: { lock: NukiLock; action: NukiLockActionType } | null = null;
```
6. In `ngOnInit()` ergänzen: `this.startNukiRefresh();`
7. In `ngOnDestroy()` ergänzen: `this.nukiSubscription?.unsubscribe();`
8. In `onEscape()` ergänzen: `this.nukiConfirm = null;`
9. Methoden ergänzen (nach `closeSwitchDialog()`):

```typescript
  private startNukiRefresh(): void {
    this.nukiSubscription = interval(DashboardComponent.NUKI_REFRESH_MS)
      .pipe(
        startWith(0),
        // Ladefehler behalten die zuletzt bekannten Schlösser (null = kein Update).
        switchMap(() => this.nukiService.getLocks().pipe(catchError(() => of<NukiLock[] | null>(null))))
      )
      .subscribe(locks => {
        this.nukiLoading = false;
        if (locks) {
          this.nukiLocks = locks;
          this.nukiError = null;
        } else if (this.nukiLocks.length === 0) {
          this.nukiError = 'Schloss nicht erreichbar.';
        }
      });
  }

  /** Verriegeln läuft ohne Rückfrage; Entsperren/Tür öffnen erst nach Bestätigung. */
  onNukiAction(lock: NukiLock, action: NukiLockActionType): void {
    if (this.pendingNukiIds.has(lock.smartlockId)) {
      return;
    }
    if (action === 'LOCK') {
      this.executeNukiAction(lock, action);
    } else {
      this.nukiConfirm = { lock, action };
    }
  }

  confirmNukiAction(): void {
    if (!this.nukiConfirm) {
      return;
    }
    const { lock, action } = this.nukiConfirm;
    this.nukiConfirm = null;
    this.executeNukiAction(lock, action);
  }

  cancelNukiAction(): void {
    this.nukiConfirm = null;
  }

  private executeNukiAction(lock: NukiLock, action: NukiLockActionType): void {
    this.pendingNukiIds.add(lock.smartlockId);
    this.nukiError = null;
    this.nukiService.sendAction(lock.smartlockId, action).subscribe({
      next: () => {
        this.pendingNukiIds.delete(lock.smartlockId);
        this.refreshNukiLocks();
      },
      error: () => {
        this.pendingNukiIds.delete(lock.smartlockId);
        this.nukiError = `${lock.name}: Aktion fehlgeschlagen.`;
      }
    });
  }

  private refreshNukiLocks(): void {
    this.nukiService.getLocks().pipe(catchError(() => of<NukiLock[]>([]))).subscribe(locks => {
      if (locks.length > 0) {
        this.nukiLocks = locks;
      }
    });
  }

  /** Anzeigetext des Schlosszustands. */
  nukiStateLabel(lock: NukiLock): string {
    switch (lock.state) {
      case 'locked': return 'Verriegelt';
      case 'unlocked': return 'Aufgesperrt';
      case 'unlatched': return 'Tür geöffnet';
      case 'locking': return 'Verriegelt…';
      case 'unlocking': return 'Sperrt auf…';
      case 'unlatching': return 'Öffnet…';
      case 'jammed': return 'Blockiert!';
      case 'uncalibrated': return 'Nicht kalibriert';
      case 'unavailable': return 'Nicht erreichbar';
      default: return 'Unbekannt';
    }
  }

  /** Material-Symbol zum Schlosszustand. */
  nukiStateIcon(lock: NukiLock): string {
    switch (lock.state) {
      case 'locked': return 'lock';
      case 'jammed': return 'lock_reset';
      case 'unavailable': return 'lock_clock';
      default: return 'lock_open';
    }
  }

  /** True, wenn Aktionen für dieses Schloss möglich sind. */
  nukiActionable(lock: NukiLock): boolean {
    return lock.state !== 'unavailable' && !this.pendingNukiIds.has(lock.smartlockId);
  }

  /** Beschriftung der zu bestätigenden Aktion im Dialog. */
  get nukiConfirmLabel(): string {
    if (!this.nukiConfirm) {
      return '';
    }
    return this.nukiConfirm.action === 'UNLATCH' ? 'Tür öffnen' : 'Aufsperren';
  }
```

- [ ] **Step 8: Footer-Karte im Template ersetzen**

In `dashboard.component.html` den kompletten statischen Block

```html
    <div class="lumina-card lumina__secured">
      <div class="lumina__secured-icon">
        <span class="lumina__pulse-ring"></span>
        <span class="material-symbols-outlined">security</span>
      </div>
      <div>
        <h4 class="lumina__label lumina__label--secondary">System gesichert</h4>
        <p class="lumina__secured-detail">8 Zugänge verriegelt • 4 Zonen aktiv</p>
      </div>
    </div>
```

ersetzen durch:

```html
    <div class="lumina-card lumina__secured" [class.lumina__secured--open]="nukiLocks[0] && nukiLocks[0].state !== 'locked' && nukiLocks[0].state !== 'unavailable'">
      <div class="lumina__secured-icon">
        <span class="lumina__pulse-ring"></span>
        <span class="material-symbols-outlined">{{ nukiLocks[0] ? nukiStateIcon(nukiLocks[0]) : 'security' }}</span>
      </div>
      <div class="lumina__lock-info">
        <ng-container *ngIf="nukiLocks[0] as lock; else nukiEmpty">
          <h4 class="lumina__label lumina__label--secondary">{{ lock.name }}</h4>
          <p class="lumina__secured-detail">
            {{ nukiStateLabel(lock) }}<ng-container *ngIf="lock.doorState"> • Tür {{ lock.doorState === 'on' ? 'offen' : 'zu' }}</ng-container>
            <span *ngIf="lock.batteryCritical" class="lumina__lock-battery-warn">
              <span class="material-symbols-outlined">battery_alert</span>{{ lock.batteryCharge }}%
            </span>
          </p>
          <p *ngIf="nukiError" class="lumina__lock-error">{{ nukiError }}</p>
        </ng-container>
        <ng-template #nukiEmpty>
          <h4 class="lumina__label lumina__label--secondary">Türschloss</h4>
          <p class="lumina__secured-detail">{{ nukiLoading ? 'Lädt…' : (nukiError ?? 'Kein Schloss gefunden') }}</p>
        </ng-template>
      </div>
      <div *ngIf="nukiLocks[0] as lock" class="lumina__lock-actions">
        <button type="button" class="lumina__lock-btn" [disabled]="!nukiActionable(lock)"
                (click)="onNukiAction(lock, 'LOCK')" aria-label="Verriegeln">
          <span class="material-symbols-outlined">lock</span>
        </button>
        <button type="button" class="lumina__lock-btn" [disabled]="!nukiActionable(lock)"
                (click)="onNukiAction(lock, 'UNLOCK')" aria-label="Aufsperren">
          <span class="material-symbols-outlined">lock_open</span>
        </button>
        <button type="button" class="lumina__lock-btn" [disabled]="!nukiActionable(lock)"
                (click)="onNukiAction(lock, 'UNLATCH')" aria-label="Tür öffnen">
          <span class="material-symbols-outlined">door_open</span>
        </button>
      </div>
    </div>
```

- [ ] **Step 9: Bestätigungsdialog ans Template-Ende anfügen**

Vor dem schließenden `</div>` der Datei (nach dem Schalter-Dialog-Block) einfügen:

```html
  <!-- Nuki-Bestaetigungsdialog (Entsperren/Tuer oeffnen erst nach Rueckfrage) -->
  <div
    *ngIf="nukiConfirm"
    class="lumina__dialog-backdrop"
    (click)="cancelNukiAction()"
  >
    <div
      class="lumina__dialog lumina__dialog--confirm"
      role="dialog"
      aria-modal="true"
      aria-label="Schloss-Aktion bestätigen"
      (click)="$event.stopPropagation()"
    >
      <header class="lumina__dialog-head">
        <h2 class="lumina__dialog-title">{{ nukiConfirmLabel }}?</h2>
        <button
          type="button"
          class="lumina__dialog-close"
          (click)="cancelNukiAction()"
          aria-label="Schließen"
        >
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>
      <div class="lumina__dialog-body">
        <p class="lumina__confirm-text">
          {{ nukiConfirm.lock.name }} wirklich {{ nukiConfirmLabel === 'Tür öffnen' ? 'öffnen' : 'aufsperren' }}?
        </p>
        <div class="lumina__confirm-actions">
          <button type="button" class="lumina__confirm-btn lumina__confirm-btn--cancel" (click)="cancelNukiAction()">
            Abbrechen
          </button>
          <button type="button" class="lumina__confirm-btn lumina__confirm-btn--go" (click)="confirmNukiAction()">
            {{ nukiConfirmLabel }}
          </button>
        </div>
      </div>
    </div>
  </div>
```

- [ ] **Step 10: Styles ergänzen**

In `frontend/src/app/pages/dashboard/dashboard.component.scss` — zuerst die bestehenden `lumina__secured`-, Dialog- und Button-Styles der Datei ansehen und Farbvariablen/Radien von dort übernehmen. Dann im Bereich der `lumina__secured`-Styles ergänzen (Werte an die vorgefundenen Variablen anpassen):

```scss
.lumina__secured--open {
  .lumina__secured-icon {
    color: #f0b429; // Warnton analog zu bestehenden Warnfarben der Datei, ggf. vorhandene Variable nutzen
  }
}

.lumina__lock-info {
  min-width: 0;
  flex: 1;
}

.lumina__lock-battery-warn {
  display: inline-flex;
  align-items: center;
  gap: 0.15rem;
  margin-left: 0.5rem;
  color: #e5484d; // Fehlerfarbe der Datei übernehmen

  .material-symbols-outlined {
    font-size: 1rem;
  }
}

.lumina__lock-error {
  margin: 0.15rem 0 0;
  font-size: 0.75rem;
  color: #e5484d; // Fehlerfarbe der Datei übernehmen
}

.lumina__lock-actions {
  display: flex;
  gap: 0.5rem;
  margin-left: auto;
}

.lumina__lock-btn {
  // Optik an bestehende Icon-Buttons der Datei (z. B. lumina__dialog-close) anlehnen
  display: grid;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 0.9rem;
  background: rgba(255, 255, 255, 0.06);
  color: inherit;
  cursor: pointer;

  &:disabled {
    opacity: 0.4;
    cursor: default;
  }
}

.lumina__dialog--confirm {
  max-width: 26rem;
}

.lumina__confirm-text {
  margin: 0 0 1.25rem;
}

.lumina__confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}

.lumina__confirm-btn {
  padding: 0.65rem 1.25rem;
  border-radius: 0.9rem;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
  color: inherit;
  cursor: pointer;

  &--go {
    background: #e5484d; // Fehler-/Warnfarbe der Datei übernehmen
    border-color: transparent;
  }
}
```

**Wichtig (Projekt-Regel):** Alle lumina-Klassen bleiben in `dashboard.component.scss` — keine neuen Styles in Kind-Komponenten.

- [ ] **Step 11: Build und Tests**

```bash
cd frontend && npx ng build --configuration production && npx ng test --watch=false --browsers=ChromeHeadless
```
Expected: Build erfolgreich, alle Tests SUCCESS (inkl. bestehender `dashboard.component.spec.ts` — falls dieser wegen des neuen Service-Aufrufs fehlschlägt, dort `provideHttpClient(), provideHttpClientTesting()` in die Provider aufnehmen, Muster siehe andere Specs).

- [ ] **Step 12: Commit**

```bash
git add frontend/src/app/pages/dashboard/
git commit -m "feat(dashboard): Tuerschloss-Kachel mit Bestaetigungsdialog"
```

---

### Task 9: Doku und Abschluss-Verifikation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: CLAUDE.md ergänzen**

In `CLAUDE.md` unter „Smart Device Integrations“ (nach dem Abschnitt „Amazon Smart Air Quality Monitor“) einfügen:

```markdown
### Nuki Smart Lock (Web API)
- Nuki Smart Lock Pro über die Cloud-API `https://api.nuki.io` (Bearer-Token von web.nuki.io, env `NUKI_API_TOKEN`); bewusste Entscheidung gegen lokales MQTT
- Polling alle 30 s (`NukiPollingService`); Zustände als `lock.nuki_<smartlockId>` (locked/unlocked/unlatched/jammed/…) und optional `binary_sensor.nuki_<smartlockId>_door` (on = offen); Cloud-Ausfall → `unavailable`
- Aktionen: verriegeln/entsperren/Tür öffnen via `POST /v1/nuki/locks/{smartlockId}/actions`; nach jeder Aktion sofortiges Nachpollen
- Flow-Engine: Zustands-Trigger über den Entity-State-Layer, Aktions-Node `nuki-lock-action` (smartlockId als String!)
- Dashboard: Türschloss-Kachel im Footer (ersetzt die statische „System gesichert“-Karte); Verriegeln direkt, Entsperren/Tür öffnen mit Bestätigungsdialog
- Implementierung in `backend/src/main/java/com/household/manager/nuki/`
```

- [ ] **Step 2: Alle neuen Backend-Tests laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest="Nuki*Test,NodeCatalogFieldsTest,FlowValidatorTest" -q
```
Expected: alle grün. (Voller `mvn test` schlägt lokal nur bei `HouseholdManagerApplicationTests`/`HealthControllerTest` fehl — DB-bedingt, vorbestehend.)

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Nuki-Integration in CLAUDE.md dokumentieren"
```

- [ ] **Step 4: Manuelle Verifikation (erfordert NUKI_API_TOKEN vom Benutzer)**

Sobald der Benutzer den Token hinterlegt hat (`NUKI_API_TOKEN` als Umgebungsvariable):

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && NUKI_API_TOKEN=<token> mvn spring-boot:run
```
Dann prüfen:
- `curl http://localhost:8080/api/v1/nuki/locks` → Schloss mit Zustand
- Nach ~45 s: `curl http://localhost:8080/api/v1/entities` (bzw. der Entities-Endpoint) enthält `lock.nuki_…`
- Dashboard im Browser: Kachel zeigt Zustand; Verriegeln direkt, Entsperren mit Dialog

Ohne Token bleibt die Integration still (kein Fehler-Spam) — das ist Absicht.
