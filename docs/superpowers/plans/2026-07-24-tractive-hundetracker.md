# Tractive-Hundetracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Den Tractive-GPS-Tracker des Hundes ins System integrieren: Position, Akku und Safe-Zone als Entitäten (Flow-Trigger für Geofence-Alarme) plus eine Frontend-Seite mit Leaflet-Karte.

**Architecture:** Cloud-Polling gegen die inoffizielle Tractive-REST-API direkt aus dem Spring-Backend (kein Sidecar), analog zur Nuki-Integration. Login als In-App-Flow; persistiert wird **ausschließlich** das Access-Token (Tractive hat kein Refresh-Token), nie die Zugangsdaten. Die gepollten Werte werden in die bestehende Entity-State-Ebene gespiegelt und sind damit automatisch Flow-Trigger.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Lombok, Liquibase, MariaDB, JUnit 5 + Mockito + MockRestServiceServer; Angular 19 (standalone), SCSS, Leaflet + OpenStreetMap.

---

## Verifizierte API-Fakten (Basis für alle Tasks)

Recherchiert aus `aiotractive` (Referenzimplementierung der Home-Assistant-Integration). Diese Werte sind **verifiziert** und dürfen nicht geraten werden:

- Base-URL: `https://graph.tractive.com/4`
- Pflicht-Header auf **allen** Requests: `x-tractive-client: 625e533dc3c3b41c28a669f0` (öffentliche App-Client-ID)
- Auth-Header auf authentifizierten Requests: `Authorization: Bearer <access_token>` **und** `x-tractive-user: <user_id>`
- Login: `POST /auth/token` mit Body `{"platform_email": "...", "platform_token": "<passwort>", "grant_type": "tractive"}` → Antwort `{"user_id": "...", "access_token": "...", "expires_at": <unix-sekunden>}`. **Kein Refresh-Token.**
- `GET /user/{userId}/trackable_objects` → Liste `[{"_id": "..."}]`
- `GET /trackable_object/{id}` → Details des Haustiers, u. a. `{"_id": ..., "device_id": "<trackerId>", "details": {"name": "..."}}`
- `GET /tracker/{trackerId}` → Tracker-Details
- `GET /device_pos_report/{trackerId}` → `{"latlong": [lat, lon], "accuracy": 12, "sensor_used": "GPS", "time": <unix>}`
- `GET /device_hw_report/{trackerId}/` (Slash am Ende!) → `{"battery_level": 87, "charging_state": "CHARGING"|..., "time": <unix>}`

**Nicht verifiziert:** der Geofence-Endpunkt. `aiotractive` implementiert ihn nicht. Task 8 klärt ihn empirisch, bevor Code dagegen geschrieben wird; die Home-Zone aus Task 7 funktioniert unabhängig davon.

---

## File Structure

**Backend – neues Paket `backend/src/main/java/com/household/manager/tractive/`:**

| Datei | Verantwortung |
|---|---|
| `TractiveProperties.java` | Konfiguration (URLs, Intervalle, Home-Zone) |
| `TractiveException.java` | Fachliche Ausnahme der Integration |
| `TractiveApiClient.java` | HTTP gegen die Tractive-API, sonst nichts |
| `TractiveAuth.java` | JPA-Entity, Ein-Zeilen-Tabelle (nur Token) |
| `TractiveAuthService.java` | Login, Token-Gültigkeit, Logout |
| `TractiveAuthController.java` | `/v1/tractive/login|status|logout` |
| `GeoZone.java` | Kreiszone + Haversine-Enthaltensein (rein fachlich, testbar) |
| `TractiveZoneResolver.java` | Position → Zonenname / `away` |
| `TractivePollingService.java` | Poll-Zyklus, `unavailable`-Markierung |
| `TractivePetService.java` | Baut die gebündelte Sicht für die Frontend-Seite |
| `TractiveController.java` | `GET /v1/tractive/pets` |
| `dto/` | Antwort-Records der API + Request/Response-DTOs |

`repository/TractiveAuthRepository.java` kommt nach `com.household.manager.repository` (JpaConfig scannt nur dieses Paket – siehe Memory „JPA repository package").
Mapper kommt nach `entitystate/mapper/TractiveEntityMapper.java` (dort liegen alle Mapper).

**Frontend:**

| Datei | Verantwortung |
|---|---|
| `frontend/src/app/models/tractive.model.ts` | Interfaces |
| `frontend/src/app/services/tractive.service.ts` | REST-Zugriff |
| `frontend/src/app/pages/pets/pets.component.ts/.html/.scss` | Seite: Login, Karte, Kacheln |

---

## Task 1: Grundgerüst – EntitySource, Properties, Exception

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveProperties.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveException.java`
- Modify: `backend/src/main/resources/application.properties`

- [ ] **Step 1: `EntitySource` um TRACTIVE erweitern**

In `EntitySource.java` nach dem `VISION`-Eintrag einfügen:

```java
    /** Tractive GPS-Haustiertracker (inoffizielle Cloud-API). */
    TRACTIVE,
```

- [ ] **Step 2: `TractiveException` anlegen**

```java
package com.household.manager.tractive;

/** Fehler beim Zugriff auf die Tractive-Cloud-API. */
public class TractiveException extends RuntimeException {

    public TractiveException(String message, Throwable cause) {
        super(message, cause);
    }

    public TractiveException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: `TractiveProperties` anlegen**

```java
package com.household.manager.tractive;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "tractive")
@Data
public class TractiveProperties {

    private boolean enabled = true;
    private String baseUrl = "https://graph.tractive.com/4";
    /** Oeffentliche Client-ID der Tractive-App; kein Geheimnis. */
    private String clientId = "625e533dc3c3b41c28a669f0";
    private long pollIntervalMs = 60000;
    private long initialDelayMs = 20000;
    private int httpTimeoutMs = 10000;

    /** Fallback-Zone, falls die Tractive-Geofences nicht lesbar sind. Radius in Metern. */
    private Double homeLatitude;
    private Double homeLongitude;
    private double homeRadiusMeters = 100;
    private String homeZoneName = "Zuhause";
}
```

- [ ] **Step 4: Konfiguration ergänzen**

An `application.properties` anhängen (nach dem Vision-Block):

```properties

# Tractive-Haustiertracker (inoffizielle Cloud-API; Login erfolgt in der App)
tractive.enabled=${TRACTIVE_ENABLED:true}
tractive.base-url=https://graph.tractive.com/4
tractive.poll-interval-ms=60000
tractive.initial-delay-ms=20000
tractive.http-timeout-ms=10000
# Fallback-Zone, wenn die Tractive-Geofences nicht gelesen werden koennen
tractive.home-latitude=${TRACTIVE_HOME_LAT:}
tractive.home-longitude=${TRACTIVE_HOME_LON:}
tractive.home-radius-meters=100
```

- [ ] **Step 5: Kompilieren**

```bash
cd backend && mvn -q compile
```
Erwartung: BUILD SUCCESS. (Vorher `JAVA_HOME` auf jdk-21.0.10 setzen – siehe Memory „Backend JDK 21 build".)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntitySource.java backend/src/main/java/com/household/manager/tractive backend/src/main/resources/application.properties
git commit -m "feat(tractive): Grundgeruest, EntitySource und Konfiguration"
```

---

## Task 2: Token-Persistenz (Liquibase, Entity, Repository)

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260725-0041-create-tractive-auth-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveAuth.java`
- Create: `backend/src/main/java/com/household/manager/repository/TractiveAuthRepository.java`

- [ ] **Step 1: Changeset anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260725-0041-create-tractive-auth-table" author="claude">
        <comment>Tractive-Zugangstoken (nur Token, nie Zugangsdaten)</comment>
        <createTable tableName="tractive_auth">
            <column name="id" type="BIGINT">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="access_token" type="VARCHAR(1024)">
                <constraints nullable="false"/>
            </column>
            <column name="user_id" type="VARCHAR(128)">
                <constraints nullable="false"/>
            </column>
            <column name="email" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="expires_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Master-Changelog erweitern**

Vor `</databaseChangeLog>` einfügen:

```xml
    <!-- Tractive-Haustiertracker -->
    <include file="db/changelog/changes/20260725-0041-create-tractive-auth-table.xml"/>
```

- [ ] **Step 3: Entity anlegen**

```java
package com.household.manager.tractive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * Persistiertes Tractive-Zugangstoken. Es gibt hoechstens eine Zeile ({@link #SINGLETON_ID}).
 * Zugangsdaten werden bewusst nicht gespeichert – Tractive kennt kein Refresh-Token,
 * nach Ablauf ist ein erneuter Login noetig.
 */
@Entity
@Table(name = "tractive_auth")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TractiveAuth {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @ToString.Exclude
    @Column(name = "access_token", nullable = false, length = 1024)
    private String accessToken;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(nullable = false)
    private String email;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

- [ ] **Step 4: Repository anlegen**

```java
package com.household.manager.repository;

import com.household.manager.tractive.TractiveAuth;
import org.springframework.data.jpa.repository.JpaRepository;

/** Zugriff auf das einzige Tractive-Token (id = {@link TractiveAuth#SINGLETON_ID}). */
public interface TractiveAuthRepository extends JpaRepository<TractiveAuth, Long> {
}
```

- [ ] **Step 5: Kompilieren**

```bash
cd backend && mvn -q compile
```
Erwartung: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/tractive/TractiveAuth.java backend/src/main/java/com/household/manager/repository/TractiveAuthRepository.java
git commit -m "feat(tractive): Tabelle und Entity fuer das Zugangstoken"
```

---

## Task 3: API-Client – Login

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractiveTokenDto.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveApiClient.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveApiClientTest.java`

- [ ] **Step 1: Test schreiben**

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveTokenDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TractiveApiClientTest {

    private TractiveApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        TractiveProperties properties = new TractiveProperties();
        properties.setBaseUrl("https://graph.tractive.com/4");
        client = new TractiveApiClient(properties, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void loginSendsCredentialsAndClientHeader() {
        server.expect(requestTo("https://graph.tractive.com/4/auth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-tractive-client", "625e533dc3c3b41c28a669f0"))
                .andExpect(jsonPath("$.platform_email").value("halter@example.com"))
                .andExpect(jsonPath("$.platform_token").value("geheim"))
                .andExpect(jsonPath("$.grant_type").value("tractive"))
                .andRespond(withSuccess("""
                        {"user_id": "u-1", "access_token": "tok-1",
                         "expires_at": 1800000000, "unknownField": true}
                        """, MediaType.APPLICATION_JSON));

        TractiveTokenDto token = client.login("halter@example.com", "geheim");

        assertEquals("u-1", token.userId());
        assertEquals("tok-1", token.accessToken());
        assertEquals(1800000000L, token.expiresAt());
        server.verify();
    }

    @Test
    void wrapsUnauthorizedInTractiveException() {
        server.expect(requestTo("https://graph.tractive.com/4/auth/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(TractiveException.class, () -> client.login("a@b.de", "falsch"));
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractiveApiClientTest
```
Erwartung: Kompilierfehler – `TractiveApiClient` existiert nicht.

- [ ] **Step 3: DTO anlegen**

```java
package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Antwort von {@code POST /auth/token}. {@code expiresAt} ist eine Unix-Sekunde. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveTokenDto(
        @JsonProperty("user_id") String userId,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_at") long expiresAt
) {
}
```

- [ ] **Step 4: Client anlegen**

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveTokenDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * Duenner HTTP-Client fuer die inoffizielle Tractive-API.
 * Enthaelt ausschliesslich Transport-Logik; Zugangsdaten werden nie geloggt.
 */
@Component
@Slf4j
public class TractiveApiClient {

    private final TractiveProperties properties;
    private final RestTemplate restTemplate;

    public TractiveApiClient(TractiveProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .build();
    }

    public TractiveTokenDto login(String email, String password) {
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
                "platform_email", email,
                "platform_token", password,
                "grant_type", "tractive");
        try {
            var response = restTemplate.exchange(properties.getBaseUrl() + "/auth/token",
                    HttpMethod.POST, new HttpEntity<>(body, headers), TractiveTokenDto.class);
            TractiveTokenDto token = response.getBody();
            if (token == null || token.accessToken() == null) {
                throw new TractiveException("Tractive-Login lieferte kein Token");
            }
            return token;
        } catch (RestClientException ex) {
            // Bewusst ohne Zugangsdaten im Log.
            throw new TractiveException("Tractive-Login fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-tractive-client", properties.getClientId());
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    /** Nur fuer Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
```

- [ ] **Step 5: Test laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TractiveApiClientTest
```
Erwartung: Tests grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive backend/src/test/java/com/household/manager/tractive
git commit -m "feat(tractive): API-Client mit Login"
```

---

## Task 4: `TractiveAuthService` – Token-Gültigkeit

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveAuthService.java`
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractiveAuthStatusDto.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveAuthServiceTest.java`

- [ ] **Step 1: Test schreiben**

```java
package com.household.manager.tractive;

import com.household.manager.repository.TractiveAuthRepository;
import com.household.manager.tractive.dto.TractiveTokenDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TractiveAuthServiceTest {

    @Mock
    private TractiveApiClient apiClient;
    @Mock
    private TractiveAuthRepository repository;
    @InjectMocks
    private TractiveAuthService service;

    private TractiveAuth storedToken(LocalDateTime expiresAt) {
        return TractiveAuth.builder()
                .id(TractiveAuth.SINGLETON_ID)
                .accessToken("tok")
                .userId("u-1")
                .email("halter@example.com")
                .expiresAt(expiresAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void loginPersistsOnlyTheToken() {
        when(apiClient.login("halter@example.com", "geheim"))
                .thenReturn(new TractiveTokenDto("u-1", "tok",
                        java.time.Instant.now().plusSeconds(86400).getEpochSecond()));

        service.login("halter@example.com", "geheim");

        verify(repository).save(argThat(auth ->
                auth.getId().equals(TractiveAuth.SINGLETON_ID)
                        && auth.getAccessToken().equals("tok")
                        && auth.getUserId().equals("u-1")
                        && auth.getEmail().equals("halter@example.com")));
    }

    @Test
    void validTokenIsReturned() {
        when(repository.findById(TractiveAuth.SINGLETON_ID))
                .thenReturn(Optional.of(storedToken(LocalDateTime.now().plusDays(1))));

        assertTrue(service.getValidToken().isPresent());
        assertEquals("tok", service.getValidToken().get().getAccessToken());
    }

    @Test
    void tokenExpiringWithinAnHourCountsAsInvalid() {
        when(repository.findById(TractiveAuth.SINGLETON_ID))
                .thenReturn(Optional.of(storedToken(LocalDateTime.now().plusMinutes(10))));

        assertTrue(service.getValidToken().isEmpty());
    }

    @Test
    void missingTokenCountsAsInvalid() {
        when(repository.findById(TractiveAuth.SINGLETON_ID)).thenReturn(Optional.empty());

        assertTrue(service.getValidToken().isEmpty());
        assertFalse(service.status().authenticated());
    }

    @Test
    void logoutDeletesTheToken() {
        service.logout();
        verify(repository).deleteById(TractiveAuth.SINGLETON_ID);
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractiveAuthServiceTest
```
Erwartung: Kompilierfehler – `TractiveAuthService` existiert nicht.

- [ ] **Step 3: Status-DTO anlegen**

```java
package com.household.manager.tractive.dto;

import java.time.LocalDateTime;

/** Anmeldezustand fuer das Frontend. */
public record TractiveAuthStatusDto(boolean authenticated, String email, LocalDateTime expiresAt) {
}
```

- [ ] **Step 4: Service anlegen**

```java
package com.household.manager.tractive;

import com.household.manager.repository.TractiveAuthRepository;
import com.household.manager.tractive.dto.TractiveAuthStatusDto;
import com.household.manager.tractive.dto.TractiveTokenDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Verwaltet das Tractive-Zugangstoken. Tractive kennt kein Refresh-Token: laeuft das
 * Token ab, ist ein erneuter Login noetig. Zugangsdaten werden nie persistiert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractiveAuthService {

    /** Sicherheitsabstand: Token unter dieser Restlaufzeit gelten als abgelaufen. */
    private static final Duration EXPIRY_MARGIN = Duration.ofHours(1);

    private final TractiveApiClient apiClient;
    private final TractiveAuthRepository repository;

    @Transactional
    public TractiveAuthStatusDto login(String email, String password) {
        TractiveTokenDto token = apiClient.login(email, password);
        // Tractive liefert eine Unix-Sekunde; hier auf lokale Zeit gebracht, damit die
        // Speicherung zu allen anderen Zeitstempeln dieses Schemas passt.
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(token.expiresAt()), ZoneId.systemDefault());
        repository.save(TractiveAuth.builder()
                .id(TractiveAuth.SINGLETON_ID)
                .accessToken(token.accessToken())
                .userId(token.userId())
                .email(email)
                .expiresAt(expiresAt)
                .updatedAt(LocalDateTime.now())
                .build());
        log.info("Tractive-Login erfolgreich, Token gueltig bis {}", expiresAt);
        return new TractiveAuthStatusDto(true, email, expiresAt);
    }

    /** Das gespeicherte Token, sofern vorhanden und ausreichend lange gueltig. */
    @Transactional(readOnly = true)
    public Optional<TractiveAuth> getValidToken() {
        return repository.findById(TractiveAuth.SINGLETON_ID)
                .filter(this::isUsable);
    }

    @Transactional(readOnly = true)
    public TractiveAuthStatusDto status() {
        return repository.findById(TractiveAuth.SINGLETON_ID)
                .map(auth -> new TractiveAuthStatusDto(
                        isUsable(auth), auth.getEmail(), auth.getExpiresAt()))
                .orElse(new TractiveAuthStatusDto(false, null, null));
    }

    /** Token gilt nur als brauchbar, solange es den Sicherheitsabstand ueberdauert. */
    private boolean isUsable(TractiveAuth auth) {
        return auth.getExpiresAt().isAfter(LocalDateTime.now().plus(EXPIRY_MARGIN));
    }

    @Transactional
    public void logout() {
        repository.deleteById(TractiveAuth.SINGLETON_ID);
    }
}
```

- [ ] **Step 5: Test laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TractiveAuthServiceTest
```
Erwartung: Tests grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive backend/src/test/java/com/household/manager/tractive
git commit -m "feat(tractive): Token-Verwaltung mit Ablaufpruefung"
```

---

## Task 5: Login-Controller

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractiveLoginRequest.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveAuthController.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveAuthControllerTest.java`

- [ ] **Step 1: Test schreiben**

```java
package com.household.manager.tractive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.tractive.dto.TractiveAuthStatusDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TractiveAuthControllerTest {

    @Mock
    private TractiveAuthService authService;

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new TractiveAuthController(authService))
                .setControllerAdvice(new TractiveAuthController.TractiveAuthExceptionHandler())
                .build();
    }

    @Test
    void loginReturnsStatus() throws Exception {
        LocalDateTime expiry = LocalDateTime.parse("2026-09-01T00:00:00");
        when(authService.login("halter@example.com", "geheim"))
                .thenReturn(new TractiveAuthStatusDto(true, "halter@example.com", expiry));

        mockMvc().perform(post("/v1/tractive/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                Map.of("email", "halter@example.com", "password", "geheim"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.email").value("halter@example.com"));
    }

    @Test
    void failedLoginReturns401() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new TractiveException("falsche Zugangsdaten"));

        mockMvc().perform(post("/v1/tractive/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                Map.of("email", "a@b.de", "password", "falsch"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutDelegatesToService() throws Exception {
        mockMvc().perform(post("/v1/tractive/logout"))
                .andExpect(status().isNoContent());
        verify(authService).logout();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractiveAuthControllerTest
```
Erwartung: Kompilierfehler – `TractiveAuthController` existiert nicht.

- [ ] **Step 3: Request-DTO anlegen**

```java
package com.household.manager.tractive.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.ToString;

/** Login-Anfrage. Das Passwort wird nur weitergereicht, nie gespeichert oder geloggt. */
public record TractiveLoginRequest(
        @NotBlank String email,
        @ToString.Exclude @NotBlank String password
) {
    @Override
    public String toString() {
        return "TractiveLoginRequest[email=" + email + ", password=***]";
    }
}
```

- [ ] **Step 4: Controller anlegen**

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveAuthStatusDto;
import com.household.manager.tractive.dto.TractiveLoginRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Anmeldung an der Tractive-Cloud (In-App-Login). */
@RestController
@RequestMapping("/v1/tractive")
@RequiredArgsConstructor
public class TractiveAuthController {

    private final TractiveAuthService authService;

    @PostMapping("/login")
    public TractiveAuthStatusDto login(@Valid @RequestBody TractiveLoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @GetMapping("/status")
    public TractiveAuthStatusDto status() {
        return authService.status();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout();
        return ResponseEntity.noContent().build();
    }

    /** Fehlgeschlagene Anmeldung bzw. Cloud-Fehler als 401 melden. */
    @RestControllerAdvice(assignableTypes = TractiveAuthController.class)
    public static class TractiveAuthExceptionHandler {

        @ExceptionHandler(TractiveException.class)
        public ResponseEntity<Map<String, String>> handle(TractiveException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Anmeldung bei Tractive fehlgeschlagen."));
        }
    }
}
```

- [ ] **Step 5: Test laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TractiveAuthControllerTest
```
Erwartung: Tests grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive backend/src/test/java/com/household/manager/tractive
git commit -m "feat(tractive): Login-Endpunkte fuer den In-App-Flow"
```

---

## Task 6: API-Client – Haustiere, Position, Hardware

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractiveTrackableRefDto.java`
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractiveTrackableDto.java`
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractivePositionDto.java`
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractiveHardwareDto.java`
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveApiClient.java`
- Modify: `backend/src/test/java/com/household/manager/tractive/TractiveApiClientTest.java`

- [ ] **Step 1: Tests ergänzen**

In `TractiveApiClientTest` diese Methoden ergänzen:

```java
    @Test
    void listTrackableObjectsSendsAuthHeaders() {
        server.expect(requestTo("https://graph.tractive.com/4/user/u-1/trackable_objects"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(header("x-tractive-user", "u-1"))
                .andExpect(header("x-tractive-client", "625e533dc3c3b41c28a669f0"))
                .andRespond(withSuccess("""
                        [{"_id": "trk-1"}, {"_id": "trk-2"}]
                        """, MediaType.APPLICATION_JSON));

        var refs = client.listTrackableObjects("tok-1", "u-1");

        assertEquals(2, refs.size());
        assertEquals("trk-1", refs.get(0).id());
        server.verify();
    }

    @Test
    void trackableDetailsParseNameAndDeviceId() {
        server.expect(requestTo("https://graph.tractive.com/4/trackable_object/trk-1"))
                .andRespond(withSuccess("""
                        {"_id": "trk-1", "device_id": "dev-9",
                         "details": {"name": "Bello", "pet_type": "DOG"}, "extra": 1}
                        """, MediaType.APPLICATION_JSON));

        var trackable = client.getTrackable("tok-1", "u-1", "trk-1");

        assertEquals("dev-9", trackable.deviceId());
        assertEquals("Bello", trackable.details().name());
        server.verify();
    }

    @Test
    void positionReportParsesLatLong() {
        server.expect(requestTo("https://graph.tractive.com/4/device_pos_report/dev-9"))
                .andRespond(withSuccess("""
                        {"latlong": [48.2082, 16.3738], "accuracy": 12,
                         "sensor_used": "GPS", "time": 1800000000, "extra": true}
                        """, MediaType.APPLICATION_JSON));

        var position = client.getPosition("tok-1", "u-1", "dev-9");

        assertEquals(48.2082, position.latitude());
        assertEquals(16.3738, position.longitude());
        assertEquals("GPS", position.sensorUsed());
        server.verify();
    }

    @Test
    void hardwareReportParsesBatteryAndCharging() {
        server.expect(requestTo("https://graph.tractive.com/4/device_hw_report/dev-9/"))
                .andRespond(withSuccess("""
                        {"battery_level": 87, "charging_state": "CHARGING", "time": 1800000000}
                        """, MediaType.APPLICATION_JSON));

        var hardware = client.getHardware("tok-1", "u-1", "dev-9");

        assertEquals(87, hardware.batteryLevel());
        assertTrue(hardware.isCharging());
        server.verify();
    }
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractiveApiClientTest
```
Erwartung: Kompilierfehler – Methoden existieren nicht.

- [ ] **Step 3: DTOs anlegen**

`TractiveTrackableRefDto.java`:

```java
package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Eintrag aus {@code GET /user/{userId}/trackable_objects}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveTrackableRefDto(@JsonProperty("_id") String id) {
}
```

`TractiveTrackableDto.java`:

```java
package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Haustier-Details aus {@code GET /trackable_object/{id}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveTrackableDto(
        @JsonProperty("_id") String id,
        @JsonProperty("device_id") String deviceId,
        Details details
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(String name, @JsonProperty("pet_type") String petType) {
    }

    /** Anzeigename mit Rueckfall auf die Geraete-ID. */
    public String displayName() {
        return details != null && details.name() != null && !details.name().isBlank()
                ? details.name()
                : "Tracker " + deviceId;
    }
}
```

`TractivePositionDto.java`:

```java
package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Positionsbericht aus {@code GET /device_pos_report/{trackerId}}.
 * Tractive liefert die Koordinaten als Array {@code [lat, lon]}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractivePositionDto(
        List<Double> latlong,
        Double accuracy,
        @JsonProperty("sensor_used") String sensorUsed,
        Long time
) {

    public boolean hasCoordinates() {
        return latlong != null && latlong.size() >= 2
                && latlong.get(0) != null && latlong.get(1) != null;
    }

    public double latitude() {
        return latlong.get(0);
    }

    public double longitude() {
        return latlong.get(1);
    }

    public Instant reportedAt() {
        return time != null ? Instant.ofEpochSecond(time) : null;
    }
}
```

`TractiveHardwareDto.java`:

```java
package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Hardware-Bericht aus {@code GET /device_hw_report/{trackerId}/}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveHardwareDto(
        @JsonProperty("battery_level") Integer batteryLevel,
        @JsonProperty("charging_state") String chargingState
) {

    public boolean isCharging() {
        return "CHARGING".equalsIgnoreCase(chargingState);
    }
}
```

- [ ] **Step 4: Client erweitern**

In `TractiveApiClient` ergänzen (Imports `java.util.List`, `org.springframework.core.ParameterizedTypeReference` nicht vergessen):

```java
    public List<TractiveTrackableRefDto> listTrackableObjects(String token, String userId) {
        return getList("/user/" + userId + "/trackable_objects", token, userId,
                new ParameterizedTypeReference<List<TractiveTrackableRefDto>>() {
                });
    }

    public TractiveTrackableDto getTrackable(String token, String userId, String trackableId) {
        return get("/trackable_object/" + trackableId, token, userId, TractiveTrackableDto.class);
    }

    public TractivePositionDto getPosition(String token, String userId, String trackerId) {
        return get("/device_pos_report/" + trackerId, token, userId, TractivePositionDto.class);
    }

    public TractiveHardwareDto getHardware(String token, String userId, String trackerId) {
        // Der abschliessende Slash ist von der API vorgegeben.
        return get("/device_hw_report/" + trackerId + "/", token, userId, TractiveHardwareDto.class);
    }

    private <T> T get(String path, String token, String userId, Class<T> type) {
        try {
            var response = restTemplate.exchange(properties.getBaseUrl() + path, HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token, userId)), type);
            T body = response.getBody();
            if (body == null) {
                throw new TractiveException("Leere Antwort von " + path);
            }
            return body;
        } catch (RestClientException ex) {
            throw new TractiveException("Tractive-Abruf " + path + " fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private <T> List<T> getList(String path, String token, String userId,
                                ParameterizedTypeReference<List<T>> type) {
        try {
            var response = restTemplate.exchange(properties.getBaseUrl() + path, HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token, userId)), type);
            return response.getBody() != null ? response.getBody() : List.of();
        } catch (RestClientException ex) {
            throw new TractiveException("Tractive-Abruf " + path + " fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders authHeaders(String token, String userId) {
        HttpHeaders headers = baseHeaders();
        headers.setBearerAuth(token);
        headers.set("x-tractive-user", userId);
        return headers;
    }
```

- [ ] **Step 5: Tests laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TractiveApiClientTest
```
Erwartung: alle Tests grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive backend/src/test/java/com/household/manager/tractive
git commit -m "feat(tractive): Abruf von Haustieren, Position und Hardware"
```

---

## Task 7: Zonenlogik (Haversine, Home-Fallback)

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/GeoZone.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveZoneResolver.java`
- Test: `backend/src/test/java/com/household/manager/tractive/GeoZoneTest.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveZoneResolverTest.java`

- [ ] **Step 1: Test für `GeoZone` schreiben**

```java
package com.household.manager.tractive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeoZoneTest {

    /** Wiener Stephansdom als Zonenmittelpunkt, Radius 100 m. */
    private final GeoZone zone = new GeoZone("Zuhause", 48.2082, 16.3738, 100);

    @Test
    void pointAtCenterIsInside() {
        assertTrue(zone.contains(48.2082, 16.3738));
    }

    @Test
    void pointJustInsideRadiusIsInside() {
        // rund 50 m noerdlich (1 Breitengrad entspricht ca. 111.320 m)
        assertTrue(zone.contains(48.2082 + 0.00045, 16.3738));
    }

    @Test
    void pointOutsideRadiusIsOutside() {
        // rund 550 m noerdlich
        assertFalse(zone.contains(48.2082 + 0.005, 16.3738));
    }

    @Test
    void distanceIsSymmetric() {
        double a = GeoZone.distanceMeters(48.2082, 16.3738, 48.2100, 16.3800);
        double b = GeoZone.distanceMeters(48.2100, 16.3800, 48.2082, 16.3738);
        assertEquals(a, b, 0.001);
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=GeoZoneTest
```
Erwartung: Kompilierfehler – `GeoZone` existiert nicht.

- [ ] **Step 3: `GeoZone` implementieren**

```java
package com.household.manager.tractive;

/**
 * Kreisfoermige Zone mit Mittelpunkt und Radius in Metern.
 * Die Distanzberechnung nutzt die Haversine-Formel; fuer Zonengroessen
 * im Meter- bis Kilometerbereich ist die Kugelnaeherung ausreichend genau.
 */
public record GeoZone(String name, double latitude, double longitude, double radiusMeters) {

    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    public boolean contains(double pointLatitude, double pointLongitude) {
        return distanceMeters(latitude, longitude, pointLatitude, pointLongitude) <= radiusMeters;
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
```

- [ ] **Step 4: `GeoZoneTest` laufen lassen**

```bash
cd backend && mvn -q test -Dtest=GeoZoneTest
```
Erwartung: Tests grün.

- [ ] **Step 5: Test für `TractiveZoneResolver` schreiben**

```java
package com.household.manager.tractive;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TractiveZoneResolverTest {

    private TractiveProperties propertiesWithHome() {
        TractiveProperties properties = new TractiveProperties();
        properties.setHomeLatitude(48.2082);
        properties.setHomeLongitude(16.3738);
        properties.setHomeRadiusMeters(100);
        properties.setHomeZoneName("Zuhause");
        return properties;
    }

    @Test
    void positionInsideAZoneYieldsTheZoneName() {
        TractiveZoneResolver resolver = new TractiveZoneResolver(new TractiveProperties());
        List<GeoZone> zones = List.of(new GeoZone("Garten", 48.2082, 16.3738, 100));

        assertEquals("Garten", resolver.resolve(48.2082, 16.3738, zones));
    }

    @Test
    void positionOutsideAllZonesYieldsAway() {
        TractiveZoneResolver resolver = new TractiveZoneResolver(new TractiveProperties());
        List<GeoZone> zones = List.of(new GeoZone("Garten", 48.2082, 16.3738, 100));

        assertEquals("away", resolver.resolve(48.3000, 16.3738, zones));
    }

    @Test
    void homeZoneIsUsedWhenNoZonesAreKnown() {
        TractiveZoneResolver resolver = new TractiveZoneResolver(propertiesWithHome());

        assertEquals("Zuhause", resolver.resolve(48.2082, 16.3738, List.of()));
        assertEquals("away", resolver.resolve(48.3000, 16.3738, List.of()));
    }

    @Test
    void withoutZonesAndWithoutHomeTheStateIsUnknown() {
        TractiveZoneResolver resolver = new TractiveZoneResolver(new TractiveProperties());

        assertEquals("unknown", resolver.resolve(48.2082, 16.3738, List.of()));
    }
}
```

- [ ] **Step 6: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractiveZoneResolverTest
```
Erwartung: Kompilierfehler – `TractiveZoneResolver` existiert nicht.

- [ ] **Step 7: `TractiveZoneResolver` implementieren**

```java
package com.household.manager.tractive;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bestimmt aus einer Position den Zonennamen. Bekannte Zonen gewinnen; ist keine
 * bekannt, greift die konfigurierte Home-Zone. Ohne beides bleibt der Zustand
 * {@code unknown} – ein Zonenzustand wird nie geraten, damit Geofence-Flows
 * nicht auf erfundenen Werten feuern.
 */
@Component
@RequiredArgsConstructor
public class TractiveZoneResolver {

    /** Zustand ausserhalb aller bekannten Zonen. */
    public static final String AWAY = "away";
    /** Zustand, wenn keine Zonenaussage moeglich ist. */
    public static final String UNKNOWN = "unknown";

    private final TractiveProperties properties;

    public String resolve(double latitude, double longitude, List<GeoZone> zones) {
        List<GeoZone> effectiveZones = zones.isEmpty() ? homeZone() : zones;
        if (effectiveZones.isEmpty()) {
            return UNKNOWN;
        }
        return effectiveZones.stream()
                .filter(zone -> zone.contains(latitude, longitude))
                .map(GeoZone::name)
                .findFirst()
                .orElse(AWAY);
    }

    private List<GeoZone> homeZone() {
        if (properties.getHomeLatitude() == null || properties.getHomeLongitude() == null) {
            return List.of();
        }
        return List.of(new GeoZone(properties.getHomeZoneName(),
                properties.getHomeLatitude(), properties.getHomeLongitude(),
                properties.getHomeRadiusMeters()));
    }
}
```

- [ ] **Step 8: Tests laufen lassen**

```bash
cd backend && mvn -q test -Dtest=GeoZoneTest+TractiveZoneResolverTest
```
Erwartung: alle Tests grün.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive backend/src/test/java/com/household/manager/tractive
git commit -m "feat(tractive): Zonenlogik mit Haversine und Home-Rueckfall"
```

---

## Task 8: Geofences aus Tractive laden (empirisch verifizieren)

Der Geofence-Endpunkt ist als einziger **nicht** aus `aiotractive` verifizierbar. Deshalb wird hier zuerst die reale Antwort geprüft und erst dann geparst. Die Integration funktioniert auch ohne diesen Task (Home-Zone aus Task 7).

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractiveGeofenceDto.java`
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveApiClient.java`
- Modify: `backend/src/test/java/com/household/manager/tractive/TractiveApiClientTest.java`

- [ ] **Step 1: Reale Antwort abfragen**

Erst Token holen, dann die Geofences des Trackers ausgeben. `<EMAIL>`, `<PASSWORT>` und `<TRACKER_ID>` ersetzen:

```bash
curl -s -X POST 'https://graph.tractive.com/4/auth/token' -H 'x-tractive-client: 625e533dc3c3b41c28a669f0' -H 'content-type: application/json' -d '{"platform_email":"<EMAIL>","platform_token":"<PASSWORT>","grant_type":"tractive"}'
```

Aus der Antwort `access_token` und `user_id` übernehmen und damit abfragen:

```bash
curl -s 'https://graph.tractive.com/4/tracker/<TRACKER_ID>/geofences' -H 'x-tractive-client: 625e533dc3c3b41c28a669f0' -H 'x-tractive-user: <USER_ID>' -H 'authorization: Bearer <ACCESS_TOKEN>'
```

Erwartung: eine JSON-Liste der Virtual Fences. **Antwort festhalten** – die tatsächlichen Feldnamen bestimmen Step 2.

Liefert der Endpunkt 404 oder etwas Unbrauchbares: Die restlichen Steps trotzdem **vollständig ausführen** – Task 10 ruft `listGeofences` auf, die Methode muss also existieren. Sie fängt Fehler ohnehin ab und liefert dann eine leere Liste, womit automatisch die Home-Zone greift. Zusätzlich `tractive.home-latitude`/`-longitude` in `application.properties` setzen.

- [ ] **Step 2: DTO an die reale Antwort anpassen und Test ergänzen**

Ausgangspunkt (die üblicherweise gelieferte Form – Feldnamen anhand von Step 1 korrigieren):

```java
package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.household.manager.tractive.GeoZone;

import java.util.List;
import java.util.Optional;

/**
 * Virtual Fence aus {@code GET /tracker/{trackerId}/geofences}.
 * Alle Felder sind optional: die API ist inoffiziell, und nur kreisfoermige
 * Zonen werden ausgewertet – alles andere wird bewusst ignoriert statt geraten.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveGeofenceDto(
        String name,
        Boolean active,
        Shape shape
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Shape(String type, List<Double> center, Double radius) {
    }

    /** Wandelt in eine {@link GeoZone}, sofern es eine nutzbare aktive Kreiszone ist. */
    public Optional<GeoZone> toZone() {
        if (Boolean.FALSE.equals(active) || shape == null || shape.radius() == null) {
            return Optional.empty();
        }
        List<Double> center = shape.center();
        if (center == null || center.size() < 2 || center.get(0) == null || center.get(1) == null) {
            return Optional.empty();
        }
        String zoneName = name != null && !name.isBlank() ? name : "Zone";
        return Optional.of(new GeoZone(zoneName, center.get(0), center.get(1), shape.radius()));
    }
}
```

Test in `TractiveApiClientTest` ergänzen:

```java
    @Test
    void geofencesAreParsedIntoZones() {
        server.expect(requestTo("https://graph.tractive.com/4/tracker/dev-9/geofences"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"name": "Garten", "active": true,
                          "shape": {"type": "circle", "center": [48.2082, 16.3738], "radius": 120}}]
                        """, MediaType.APPLICATION_JSON));

        var fences = client.listGeofences("tok-1", "u-1", "dev-9");

        assertEquals(1, fences.size());
        var zone = fences.get(0).toZone().orElseThrow();
        assertEquals("Garten", zone.name());
        assertEquals(120, zone.radiusMeters());
        server.verify();
    }

    @Test
    void inactiveOrNonCircularGeofencesAreIgnored() {
        var inactive = new com.household.manager.tractive.dto.TractiveGeofenceDto(
                "Aus", false, new com.household.manager.tractive.dto.TractiveGeofenceDto.Shape(
                        "circle", java.util.List.of(48.0, 16.0), 100.0));
        var withoutRadius = new com.household.manager.tractive.dto.TractiveGeofenceDto(
                "Polygon", true, new com.household.manager.tractive.dto.TractiveGeofenceDto.Shape(
                        "polygon", java.util.List.of(48.0, 16.0), null));

        assertTrue(inactive.toZone().isEmpty());
        assertTrue(withoutRadius.toZone().isEmpty());
    }
```

- [ ] **Step 3: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractiveApiClientTest
```
Erwartung: Kompilierfehler – `listGeofences` existiert nicht.

- [ ] **Step 4: Client-Methode ergänzen**

In `TractiveApiClient`:

```java
    /**
     * Virtual Fences des Trackers. Fehler werden geschluckt: die Zonen sind
     * eine Verbesserung, ihr Fehlen darf den Poll-Zyklus nicht kippen.
     */
    public List<TractiveGeofenceDto> listGeofences(String token, String userId, String trackerId) {
        try {
            return getList("/tracker/" + trackerId + "/geofences", token, userId,
                    new ParameterizedTypeReference<List<TractiveGeofenceDto>>() {
                    });
        } catch (TractiveException ex) {
            log.debug("Tractive-Geofences nicht lesbar ({}), es gilt die Home-Zone", ex.getMessage());
            return List.of();
        }
    }
```

- [ ] **Step 5: Tests laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TractiveApiClientTest
```
Erwartung: alle Tests grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive backend/src/test/java/com/household/manager/tractive
git commit -m "feat(tractive): Virtual Fences als Zonen auswerten"
```

---

## Task 9: Entity-Mapper

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/TractivePetSnapshot.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/mapper/TractiveEntityMapper.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/TractiveEntityMapperTest.java`

- [ ] **Step 1: Snapshot-Record anlegen**

Der Mapper soll nicht selbst pollen. Deshalb erst das Bündel, das der Poller füllt:

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;

import java.util.List;

/**
 * Alles, was in einem Poll-Zyklus zu einem Haustier eingesammelt wurde.
 * {@code position} und {@code hardware} duerfen null sein.
 */
public record TractivePetSnapshot(
        TractiveTrackableDto trackable,
        TractivePositionDto position,
        TractiveHardwareDto hardware,
        List<GeoZone> zones
) {

    public String trackerId() {
        return trackable.deviceId();
    }

    public String name() {
        return trackable.displayName();
    }
}
```

- [ ] **Step 2: Test schreiben**

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.tractive.GeoZone;
import com.household.manager.tractive.TractivePetSnapshot;
import com.household.manager.tractive.TractiveProperties;
import com.household.manager.tractive.TractiveZoneResolver;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TractiveEntityMapperTest {

    private TractiveEntityMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TractiveEntityMapper(new TractiveZoneResolver(new TractiveProperties()));
    }

    private TractiveTrackableDto bello() {
        return new TractiveTrackableDto("trk-1", "dev-9",
                new TractiveTrackableDto.Details("Bello", "DOG"));
    }

    private EntityStateUpdate byId(List<EntityStateUpdate> updates, String entityId) {
        return updates.stream()
                .filter(update -> update.entityId().equals(entityId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entitaet fehlt: " + entityId));
    }

    @Test
    void positionInsideZoneBecomesZoneName() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L),
                new TractiveHardwareDto(87, "NOT_CHARGING"),
                List.of(new GeoZone("Garten", 48.2082, 16.3738, 100)));

        var location = byId(mapper.map(snapshot), "sensor.tractive_dev_9_location");

        assertEquals("Garten", location.state());
        assertEquals(EntitySource.TRACTIVE, location.source());
        assertEquals(48.2082, location.attributes().get("latitude"));
        assertEquals(16.3738, location.attributes().get("longitude"));
        assertEquals("GPS", location.attributes().get("sensorUsed"));
        assertEquals("Bello", location.friendlyName());
    }

    @Test
    void positionOutsideAllZonesBecomesAway() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.3000, 16.3738), 12.0, "GPS", 1800000000L),
                null,
                List.of(new GeoZone("Garten", 48.2082, 16.3738, 100)));

        assertEquals("away", byId(mapper.map(snapshot), "sensor.tractive_dev_9_location").state());
    }

    @Test
    void missingPositionBecomesUnknown() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(null, null, null, null), null, List.of());

        var location = byId(mapper.map(snapshot), "sensor.tractive_dev_9_location");

        assertEquals("unknown", location.state());
        assertFalse(location.attributes().containsKey("latitude"));
    }

    @Test
    void batteryAndChargingAreMapped() {
        var snapshot = new TractivePetSnapshot(bello(), null,
                new TractiveHardwareDto(87, "CHARGING"), List.of());

        List<EntityStateUpdate> updates = mapper.map(snapshot);

        assertEquals("87", byId(updates, "sensor.tractive_dev_9_battery").state());
        assertEquals("battery",
                byId(updates, "sensor.tractive_dev_9_battery").attributes().get("deviceClass"));
        assertEquals("on", byId(updates, "binary_sensor.tractive_dev_9_charging").state());
    }

    @Test
    void withoutHardwareNoBatteryEntityIsReported() {
        var snapshot = new TractivePetSnapshot(bello(), null, null, List.of());

        List<EntityStateUpdate> updates = mapper.map(snapshot);

        assertTrue(updates.stream().noneMatch(u -> u.entityId().contains("battery")));
        assertTrue(updates.stream().noneMatch(u -> u.entityId().contains("charging")));
    }
}
```

- [ ] **Step 3: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractiveEntityMapperTest
```
Erwartung: Kompilierfehler – `TractiveEntityMapper` existiert nicht.

- [ ] **Step 4: Mapper implementieren**

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.tractive.TractivePetSnapshot;
import com.household.manager.tractive.TractiveZoneResolver;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mappt einen Tractive-Haustier-Snapshot auf Entity-Zustaende:
 * {@code sensor.tractive_<trackerId>_location} (State = Zonenname oder {@code away}),
 * {@code sensor.tractive_<trackerId>_battery} und
 * {@code binary_sensor.tractive_<trackerId>_charging}.
 */
@Component
@RequiredArgsConstructor
public class TractiveEntityMapper {

    private final TractiveZoneResolver zoneResolver;

    public List<EntityStateUpdate> map(TractivePetSnapshot snapshot) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        String ref = snapshot.trackerId();
        String name = snapshot.name();

        updates.add(locationUpdate(snapshot, ref, name));

        TractiveHardwareDto hardware = snapshot.hardware();
        if (hardware != null && hardware.batteryLevel() != null) {
            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TRACTIVE, ref, "battery"))
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.TRACTIVE)
                    .sourceRef(ref)
                    .friendlyName(name + " Akku")
                    .state(String.valueOf(hardware.batteryLevel()))
                    .attributes(Map.of("deviceClass", "battery", "unit", "%"))
                    .build());
        }
        if (hardware != null && hardware.chargingState() != null) {
            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.TRACTIVE, ref, "charging"))
                    .domain(EntityDomain.BINARY_SENSOR)
                    .source(EntitySource.TRACTIVE)
                    .sourceRef(ref)
                    .friendlyName(name + " laedt")
                    .state(hardware.isCharging() ? "on" : "off")
                    .attributes(Map.of("deviceClass", "battery_charging"))
                    .build());
        }
        return updates;
    }

    private EntityStateUpdate locationUpdate(TractivePetSnapshot snapshot, String ref, String name) {
        TractivePositionDto position = snapshot.position();
        Map<String, Object> attributes = new HashMap<>();
        String state = TractiveZoneResolver.UNKNOWN;

        if (position != null && position.hasCoordinates()) {
            state = zoneResolver.resolve(position.latitude(), position.longitude(), snapshot.zones());
            attributes.put("latitude", position.latitude());
            attributes.put("longitude", position.longitude());
            if (position.accuracy() != null) {
                attributes.put("accuracy", position.accuracy());
            }
            if (position.sensorUsed() != null) {
                attributes.put("sensorUsed", position.sensorUsed());
            }
            if (position.reportedAt() != null) {
                attributes.put("positionTime", position.reportedAt().toString());
            }
        }
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TRACTIVE, ref, "location"))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.TRACTIVE)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .build();
    }
}
```

- [ ] **Step 5: Test laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TractiveEntityMapperTest
```
Erwartung: alle Tests grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager backend/src/test/java/com/household/manager
git commit -m "feat(tractive): Entity-Mapper fuer Position, Akku und Ladezustand"
```

---

## Task 10: Poll-Service

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/TractivePollingService.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractivePollingServiceTest.java`

- [ ] **Step 1: Test schreiben**

```java
package com.household.manager.tractive;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.TractiveEntityMapper;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import com.household.manager.tractive.dto.TractiveTrackableRefDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TractivePollingServiceTest {

    @Mock
    private TractiveApiClient apiClient;
    @Mock
    private TractiveAuthService authService;
    @Mock
    private TractiveEntityMapper mapper;
    @Mock
    private EntityStateService entityStateService;

    private TractiveProperties properties;
    private TractivePollingService service;

    private static final EntityStateUpdate LOCATION_UPDATE = EntityStateUpdate.builder()
            .entityId("sensor.tractive_dev_9_location")
            .domain(EntityDomain.SENSOR)
            .source(EntitySource.TRACTIVE)
            .sourceRef("dev-9")
            .friendlyName("Bello")
            .state("Garten")
            .attributes(Map.of())
            .build();

    @BeforeEach
    void setUp() {
        properties = new TractiveProperties();
        service = new TractivePollingService(properties, apiClient, authService, mapper, entityStateService);
    }

    private void givenAuthenticated() {
        when(authService.getValidToken()).thenReturn(Optional.of(TractiveAuth.builder()
                .id(TractiveAuth.SINGLETON_ID)
                .accessToken("tok")
                .userId("u-1")
                .email("halter@example.com")
                .expiresAt(Instant.now().plusSeconds(86400))
                .updatedAt(Instant.now())
                .build()));
    }

    private void givenOnePet() {
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenReturn(List.of(new TractiveTrackableRefDto("trk-1")));
        when(apiClient.getTrackable("tok", "u-1", "trk-1"))
                .thenReturn(new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")));
        when(apiClient.getPosition("tok", "u-1", "dev-9"))
                .thenReturn(new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L));
        when(apiClient.getHardware("tok", "u-1", "dev-9"))
                .thenReturn(new TractiveHardwareDto(87, "NOT_CHARGING"));
        when(apiClient.listGeofences("tok", "u-1", "dev-9")).thenReturn(List.of());
    }

    @Test
    void pollReportsMappedStates() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any())).thenReturn(List.of(LOCATION_UPDATE));

        service.poll();

        verify(entityStateService).reportState(LOCATION_UPDATE);
    }

    @Test
    void doesNothingWithoutValidToken() {
        when(authService.getValidToken()).thenReturn(Optional.empty());

        service.poll();

        verifyNoInteractions(apiClient, entityStateService);
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);

        service.poll();

        verifyNoInteractions(apiClient, authService, entityStateService);
    }

    @Test
    void cloudFailureMarksLastKnownEntitiesUnavailable() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any())).thenReturn(List.of(LOCATION_UPDATE));

        service.poll();
        reset(apiClient);
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenThrow(new TractiveException("cloud down"));

        assertDoesNotThrow(() -> service.poll());

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        EntityStateUpdate unavailable = captor.getAllValues().get(1);
        assertEquals("sensor.tractive_dev_9_location", unavailable.entityId());
        assertEquals("unavailable", unavailable.state());
    }

    @Test
    void oneBrokenPetDoesNotAbortTheCycle() {
        givenAuthenticated();
        when(apiClient.listTrackableObjects("tok", "u-1")).thenReturn(List.of(
                new TractiveTrackableRefDto("broken"), new TractiveTrackableRefDto("trk-1")));
        when(apiClient.getTrackable("tok", "u-1", "broken"))
                .thenThrow(new TractiveException("boom"));
        when(apiClient.getTrackable("tok", "u-1", "trk-1"))
                .thenReturn(new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")));
        when(apiClient.getPosition("tok", "u-1", "dev-9"))
                .thenReturn(new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L));
        when(apiClient.getHardware("tok", "u-1", "dev-9"))
                .thenReturn(new TractiveHardwareDto(87, "NOT_CHARGING"));
        when(apiClient.listGeofences("tok", "u-1", "dev-9")).thenReturn(List.of());
        when(mapper.map(any())).thenReturn(List.of(LOCATION_UPDATE));

        assertDoesNotThrow(() -> service.poll());

        verify(entityStateService).reportState(LOCATION_UPDATE);
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractivePollingServiceTest
```
Erwartung: Kompilierfehler – `TractivePollingService` existiert nicht.

- [ ] **Step 3: Poll-Service implementieren**

```java
package com.household.manager.tractive;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.TractiveEntityMapper;
import com.household.manager.tractive.dto.TractiveGeofenceDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pollt die Tractive-Cloud und spiegelt die Haustiere in den Entity-State-Layer.
 * Bei Cloud-Fehlern oder abgelaufenem Token werden die zuletzt gemeldeten
 * Entitaeten auf {@code unavailable} gesetzt; das Polling bricht nie ab.
 *
 * <p>Live-Tracking wird bewusst nicht aktiviert – gelesen wird nur der zuletzt
 * regulaer gemeldete Positionsbericht, um den Tracker-Akku zu schonen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractivePollingService {

    private final TractiveProperties properties;
    private final TractiveApiClient apiClient;
    private final TractiveAuthService authService;
    private final TractiveEntityMapper mapper;
    private final EntityStateService entityStateService;

    /** Zuletzt erfolgreich gemeldete Updates; Basis fuer die unavailable-Markierung. */
    private volatile List<EntityStateUpdate> lastUpdates = List.of();
    /** Letzter erfolgreicher Poll-Stand fuer die Frontend-Seite. */
    private volatile List<TractivePetSnapshot> lastSnapshots = List.of();

    @Scheduled(fixedDelayString = "${tractive.poll-interval-ms:60000}",
            initialDelayString = "${tractive.initial-delay-ms:20000}")
    public synchronized void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        Optional<TractiveAuth> auth = authService.getValidToken();
        if (auth.isEmpty()) {
            markUnavailable();
            return;
        }
        String token = auth.get().getAccessToken();
        String userId = auth.get().getUserId();
        try {
            List<TractivePetSnapshot> snapshots = new ArrayList<>();
            for (var ref : apiClient.listTrackableObjects(token, userId)) {
                collectPet(token, userId, ref.id()).ifPresent(snapshots::add);
            }
            List<EntityStateUpdate> updates = new ArrayList<>();
            for (TractivePetSnapshot snapshot : snapshots) {
                try {
                    updates.addAll(mapper.map(snapshot));
                } catch (Exception ex) {
                    log.warn("Tractive-Mapping fuer {} fehlgeschlagen: {}",
                            snapshot.trackerId(), ex.getMessage());
                }
            }
            updates.forEach(entityStateService::reportState);
            lastUpdates = List.copyOf(updates);
            lastSnapshots = List.copyOf(snapshots);
        } catch (Exception ex) {
            log.warn("Tractive-Polling fehlgeschlagen: {}", ex.getMessage());
            markUnavailable();
        }
    }

    /** Ein einzelnes Haustier einsammeln; Fehler betreffen nur dieses Tier. */
    private Optional<TractivePetSnapshot> collectPet(String token, String userId, String trackableId) {
        try {
            TractiveTrackableDto trackable = apiClient.getTrackable(token, userId, trackableId);
            if (trackable.deviceId() == null || trackable.deviceId().isBlank()) {
                log.debug("Tractive-Objekt {} hat keinen Tracker, wird uebersprungen", trackableId);
                return Optional.empty();
            }
            String trackerId = trackable.deviceId();
            List<GeoZone> zones = apiClient.listGeofences(token, userId, trackerId).stream()
                    .map(TractiveGeofenceDto::toZone)
                    .flatMap(Optional::stream)
                    .toList();
            return Optional.of(new TractivePetSnapshot(trackable,
                    apiClient.getPosition(token, userId, trackerId),
                    apiClient.getHardware(token, userId, trackerId),
                    zones));
        } catch (Exception ex) {
            log.warn("Tractive-Abruf fuer Objekt {} fehlgeschlagen: {}", trackableId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Letzter bekannter Stand fuer die Haustier-Seite. */
    public List<TractivePetSnapshot> latestSnapshots() {
        return lastSnapshots;
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

- [ ] **Step 4: Test laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TractivePollingServiceTest
```
Erwartung: alle Tests grün.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive backend/src/test/java/com/household/manager/tractive
git commit -m "feat(tractive): Poll-Service mit unavailable-Markierung"
```

---

## Task 11: `GET /v1/tractive/pets`

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractivePetDto.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveController.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveControllerTest.java`

- [ ] **Step 1: Test schreiben**

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TractiveControllerTest {

    @Mock
    private TractivePollingService pollingService;
    @Mock
    private TractiveZoneResolver zoneResolver;

    @Test
    void petsAreReturnedForTheMap() throws Exception {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L),
                new TractiveHardwareDto(87, "NOT_CHARGING"),
                List.of(new GeoZone("Garten", 48.2082, 16.3738, 100)));
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));
        when(zoneResolver.resolve(48.2082, 16.3738, snapshot.zones())).thenReturn("Garten");

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TractiveController(pollingService, zoneResolver)).build();

        mockMvc.perform(get("/v1/tractive/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bello"))
                .andExpect(jsonPath("$[0].trackerId").value("dev-9"))
                .andExpect(jsonPath("$[0].latitude").value(48.2082))
                .andExpect(jsonPath("$[0].batteryPercent").value(87))
                .andExpect(jsonPath("$[0].charging").value(false))
                .andExpect(jsonPath("$[0].zone").value("Garten"));
    }

    @Test
    void petWithoutPositionOmitsCoordinates() throws Exception {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                null, null, List.of());
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TractiveController(pollingService, zoneResolver)).build();

        mockMvc.perform(get("/v1/tractive/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latitude").doesNotExist())
                .andExpect(jsonPath("$[0].zone").value("unknown"));
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TractiveControllerTest
```
Erwartung: Kompilierfehler – `TractiveController` existiert nicht.

- [ ] **Step 3: DTO anlegen**

```java
package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Gebuendelte Sicht eines Haustiers fuer die Kartenseite. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TractivePetDto(
        String trackerId,
        String name,
        Double latitude,
        Double longitude,
        Double accuracy,
        String sensorUsed,
        Instant lastSeen,
        Integer batteryPercent,
        Boolean charging,
        String zone
) {
}
```

- [ ] **Step 4: Controller implementieren**

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Liefert den letzten bekannten Stand der Haustiere fuer die Kartenseite. */
@RestController
@RequestMapping("/v1/tractive")
@RequiredArgsConstructor
public class TractiveController {

    private final TractivePollingService pollingService;
    private final TractiveZoneResolver zoneResolver;

    @GetMapping("/pets")
    public List<TractivePetDto> pets() {
        return pollingService.latestSnapshots().stream().map(this::toDto).toList();
    }

    private TractivePetDto toDto(TractivePetSnapshot snapshot) {
        TractivePositionDto position = snapshot.position();
        TractiveHardwareDto hardware = snapshot.hardware();
        boolean hasPosition = position != null && position.hasCoordinates();

        return new TractivePetDto(
                snapshot.trackerId(),
                snapshot.name(),
                hasPosition ? position.latitude() : null,
                hasPosition ? position.longitude() : null,
                hasPosition ? position.accuracy() : null,
                hasPosition ? position.sensorUsed() : null,
                position != null ? position.reportedAt() : null,
                hardware != null ? hardware.batteryLevel() : null,
                hardware != null ? hardware.isCharging() : null,
                hasPosition
                        ? zoneResolver.resolve(position.latitude(), position.longitude(), snapshot.zones())
                        : TractiveZoneResolver.UNKNOWN);
    }
}
```

- [ ] **Step 5: Test laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TractiveControllerTest
```
Erwartung: Tests grün.

- [ ] **Step 6: Gesamte Backend-Testsuite laufen lassen**

```bash
cd backend && mvn -q test
```
Erwartung: keine **neuen** Fehler. Lokale DB-Tests schlagen bauartbedingt fehl (siehe Memory „Backend JDK 21 build") – nur die `Tractive*`-Tests müssen grün sein.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive backend/src/test/java/com/household/manager/tractive
git commit -m "feat(tractive): Endpunkt fuer die Haustier-Kartenseite"
```

---

## Task 12: Frontend – Leaflet, Modelle, Service

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/angular.json`
- Create: `frontend/src/app/models/tractive.model.ts`
- Create: `frontend/src/app/services/tractive.service.ts`

- [ ] **Step 1: Leaflet installieren**

```bash
cd frontend && npm install leaflet@^1.9.4 && npm install --save-dev @types/leaflet@^1.9.12
```
Erwartung: beide Pakete landen in `package.json`.

- [ ] **Step 2: Leaflet-Stylesheet einbinden**

In `frontend/angular.json` im Abschnitt `projects.<name>.architect.build.options.styles` den Eintrag ergänzen (vor `src/styles.scss`):

```json
"node_modules/leaflet/dist/leaflet.css",
```

- [ ] **Step 3: Modelle anlegen**

```typescript
/** Anmeldezustand der Tractive-Integration. */
export interface TractiveAuthStatus {
  authenticated: boolean;
  email?: string;
  expiresAt?: string;
}

/** Ein Haustier mit letztem bekanntem Stand. */
export interface TractivePet {
  trackerId: string;
  name: string;
  latitude?: number;
  longitude?: number;
  accuracy?: number;
  sensorUsed?: string;
  lastSeen?: string;
  batteryPercent?: number;
  charging?: boolean;
  /** Zonenname, 'away' ausserhalb aller Zonen oder 'unknown' ohne Position. */
  zone: string;
}
```

- [ ] **Step 4: Service anlegen**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { TractiveAuthStatus, TractivePet } from '../models/tractive.model';

/** REST-Service fuer die Tractive-Haustiertracker. */
@Injectable({ providedIn: 'root' })
export class TractiveService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/tractive';

  getStatus(): Observable<TractiveAuthStatus> {
    return this.http.get<TractiveAuthStatus>(`${this.baseUrl}/status`).pipe(
      catchError(this.handleError)
    );
  }

  login(email: string, password: string): Observable<TractiveAuthStatus> {
    return this.http.post<TractiveAuthStatus>(`${this.baseUrl}/login`, { email, password });
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout`, {}).pipe(
      catchError(this.handleError)
    );
  }

  getPets(): Observable<TractivePet[]> {
    return this.http.get<TractivePet[]>(`${this.baseUrl}/pets`).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Tractive-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Tractive-Anfrage.'));
  }
}
```

Hinweis: `login` reicht Fehler bewusst durch, damit die Seite 401 als „Zugangsdaten falsch" anzeigen kann.

- [ ] **Step 5: Build prüfen**

```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```
Erwartung: keine Fehler.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/angular.json frontend/src/app/models/tractive.model.ts frontend/src/app/services/tractive.service.ts
git commit -m "feat(tractive): Frontend-Modelle, Service und Leaflet-Abhaengigkeit"
```

---

## Task 13: Frontend – Seite „Hundetracker"

**Files:**
- Create: `frontend/src/app/pages/pets/pets.component.ts`
- Create: `frontend/src/app/pages/pets/pets.component.html`
- Create: `frontend/src/app/pages/pets/pets.component.scss`

- [ ] **Step 1: Komponente anlegen**

```typescript
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import * as L from 'leaflet';
import { TractiveService } from '../../services/tractive.service';
import { TractivePet } from '../../models/tractive.model';

/** Seite „Hundetracker": Login, Karte und Kacheln je Haustier. */
@Component({
  selector: 'app-pets',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './pets.component.html',
  styleUrl: './pets.component.scss'
})
export class PetsComponent implements OnInit, OnDestroy {
  private readonly tractiveService = inject(TractiveService);

  readonly authenticated = signal(false);
  readonly loading = signal(true);
  readonly errorMessage = signal<string | null>(null);
  readonly pets = signal<TractivePet[]>([]);

  email = '';
  password = '';

  private map?: L.Map;
  private markers = new Map<string, L.Marker>();
  private refreshTimer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.tractiveService.getStatus().subscribe({
      next: status => {
        this.authenticated.set(status.authenticated);
        this.loading.set(false);
        if (status.authenticated) {
          this.startPolling();
        }
      },
      error: () => {
        this.loading.set(false);
        this.errorMessage.set('Status konnte nicht geladen werden.');
      }
    });
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.map?.remove();
  }

  login(): void {
    this.errorMessage.set(null);
    this.tractiveService.login(this.email, this.password).subscribe({
      next: () => {
        this.password = '';
        this.authenticated.set(true);
        this.startPolling();
      },
      error: () => this.errorMessage.set('Anmeldung fehlgeschlagen. Bitte Zugangsdaten pruefen.')
    });
  }

  logout(): void {
    this.tractiveService.logout().subscribe({
      next: () => {
        this.stopPolling();
        this.authenticated.set(false);
        this.pets.set([]);
      }
    });
  }

  /** Anzeigetext der Zone. */
  zoneLabel(pet: TractivePet): string {
    if (pet.zone === 'away') {
      return 'Ausserhalb der Zone';
    }
    if (pet.zone === 'unknown') {
      return 'Keine Position';
    }
    return pet.zone;
  }

  private startPolling(): void {
    this.loadPets();
    this.refreshTimer = setInterval(() => this.loadPets(), 60000);
  }

  private stopPolling(): void {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = undefined;
    }
  }

  private loadPets(): void {
    this.tractiveService.getPets().subscribe({
      next: pets => {
        this.pets.set(pets);
        this.renderMap(pets);
      },
      error: () => this.errorMessage.set('Positionen konnten nicht geladen werden.')
    });
  }

  /** Karte beim ersten Datensatz aufbauen und Marker aktualisieren. */
  private renderMap(pets: TractivePet[]): void {
    const located = pets.filter(pet => pet.latitude != null && pet.longitude != null);
    if (located.length === 0) {
      return;
    }
    if (!this.map) {
      const container = document.getElementById('pet-map');
      if (!container) {
        return;
      }
      this.map = L.map(container).setView([located[0].latitude!, located[0].longitude!], 16);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
      }).addTo(this.map);
    }
    for (const pet of located) {
      const position: L.LatLngExpression = [pet.latitude!, pet.longitude!];
      const existing = this.markers.get(pet.trackerId);
      if (existing) {
        existing.setLatLng(position);
      } else {
        this.markers.set(pet.trackerId,
          L.marker(position).addTo(this.map).bindPopup(pet.name));
      }
    }
  }
}
```

- [ ] **Step 2: Template anlegen**

```html
<div class="pets-page">
  <h1>Hundetracker</h1>

  <p *ngIf="loading()">Wird geladen …</p>

  <section class="login" *ngIf="!loading() && !authenticated()">
    <h2>Bei Tractive anmelden</h2>
    <p class="hint">
      Die Zugangsdaten werden nur zur Anmeldung verwendet und nie gespeichert.
      Da Tractive kein Erneuerungs-Token ausgibt, ist gelegentlich eine erneute
      Anmeldung noetig.
    </p>
    <form (ngSubmit)="login()">
      <label>
        E-Mail
        <input type="email" name="email" [(ngModel)]="email" required autocomplete="username">
      </label>
      <label>
        Passwort
        <input type="password" name="password" [(ngModel)]="password" required
               autocomplete="current-password">
      </label>
      <button type="submit" [disabled]="!email || !password">Anmelden</button>
    </form>
  </section>

  <section *ngIf="!loading() && authenticated()">
    <div id="pet-map" class="map"></div>

    <div class="pet-cards">
      <article class="pet-card" *ngFor="let pet of pets()">
        <h2>{{ pet.name }}</h2>
        <p class="zone" [class.away]="pet.zone === 'away'">{{ zoneLabel(pet) }}</p>
        <p *ngIf="pet.batteryPercent != null">
          Akku: {{ pet.batteryPercent }} %
          <span *ngIf="pet.charging"> (laedt)</span>
        </p>
        <p *ngIf="pet.lastSeen" class="muted">
          Zuletzt gesehen: {{ pet.lastSeen | date:'short' }}
        </p>
      </article>
    </div>

    <p *ngIf="pets().length === 0" class="muted">
      Noch keine Daten – der erste Abruf erfolgt kurz nach dem Start.
    </p>

    <button type="button" class="logout" (click)="logout()">Abmelden</button>
  </section>

  <p class="error" *ngIf="errorMessage()">{{ errorMessage() }}</p>
</div>
```

- [ ] **Step 3: Styles anlegen**

```scss
.pets-page {
  padding: 1.5rem;
  max-width: 1000px;
  margin: 0 auto;
}

.map {
  height: 420px;
  width: 100%;
  border-radius: 12px;
  margin-bottom: 1.5rem;
}

.login {
  max-width: 400px;

  form {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }

  label {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
  }

  input {
    padding: 0.5rem;
    border-radius: 6px;
    border: 1px solid #ccc;
  }
}

.hint {
  font-size: 0.9rem;
  opacity: 0.75;
}

.pet-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 1rem;
}

.pet-card {
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 12px;
  padding: 1rem;

  h2 {
    margin: 0 0 0.5rem;
    font-size: 1.1rem;
  }
}

.zone {
  font-weight: 600;

  &.away {
    color: #c0392b;
  }
}

.muted {
  opacity: 0.7;
  font-size: 0.9rem;
}

.error {
  color: #c0392b;
}

.logout {
  margin-top: 1.5rem;
}
```

- [ ] **Step 4: Build prüfen**

```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```
Erwartung: keine Fehler.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/pets
git commit -m "feat(tractive): Seite Hundetracker mit Karte und Login"
```

---

## Task 14: Route und Navigation

**Files:**
- Modify: `frontend/src/app/app.routes.ts`
- Modify: Navigations-Komponente (Header/Sidebar – vorher ermitteln, siehe Step 1)

- [ ] **Step 1: Navigationsdatei ermitteln**

```bash
cd frontend && grep -rl "waste-collection" src/app --include=*.html
```
Erwartung: die Datei mit den Navigationslinks (z. B. `src/app/components/header/header.component.html`). Diese ist in Step 3 gemeint.

- [ ] **Step 2: Route ergänzen**

In `app.routes.ts` vor der Fallback-/Wildcard-Route einfügen:

```typescript
  {
    path: 'pets',
    loadComponent: () => import('./pages/pets/pets.component').then(m => m.PetsComponent),
    title: 'Hundetracker - Household Manager'
  },
```

- [ ] **Step 3: Navigationslink ergänzen**

In der aus Step 1 ermittelten Datei einen Link analog zu den bestehenden ergänzen (die konkrete Markup-Struktur der Datei übernehmen, nicht dieses Beispiel kopieren):

```html
<a routerLink="/pets" routerLinkActive="active">Hundetracker</a>
```

- [ ] **Step 4: Frontend-Tests laufen lassen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
Erwartung: keine **neuen** Fehler. 4 Tests (Header/App/Hero) schlagen vorbestehend fehl, `SmartDeviceList` ist flaky – siehe Memory „Frontend-Test-Baseline". Schlägt ein Header-Test wegen des neuen Links zusätzlich fehl, den Test anpassen.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app
git commit -m "feat(tractive): Route und Navigationseintrag fuer den Hundetracker"
```

---

## Task 15: Dokumentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `C:\Users\bened\.claude\projects\C--Users-bened-IdeaProjects-Household-Manager\memory\MEMORY.md`
- Create: `C:\Users\bened\.claude\projects\C--Users-bened-IdeaProjects-Household-Manager\memory\tractive-hundetracker.md`

- [ ] **Step 1: `CLAUDE.md` erweitern**

Nach dem Abschnitt „### Wandtablet (Präsenzerkennung)" einfügen:

```markdown
### Tractive-Hundetracker
- GPS-Tracker des Hundes über die **inoffizielle** Tractive-Cloud-API (`https://graph.tractive.com/4`), reines Java im Backend (`tractive/`) – kein Sidecar nötig, da der Login ein simpler REST-Aufruf ist
- **Kein Refresh-Token:** `POST /auth/token` liefert nur `access_token` + `expires_at`. Persistiert wird ausschließlich das Token (Tabelle `tractive_auth`, eine Zeile), **nie** die Zugangsdaten. Läuft es ab, gehen die Entitäten auf `unavailable` und es ist ein erneuter In-App-Login nötig — bewusste Entscheidung gegen at-rest-Zugangsdaten
- Pflicht-Header auf allen Requests: `x-tractive-client` (öffentliche App-Client-ID), zusätzlich `Authorization: Bearer` **und** `x-tractive-user` auf authentifizierten Requests
- Endpunkte: `user/{userId}/trackable_objects`, `trackable_object/{id}` (liefert `device_id` = Tracker-ID), `device_pos_report/{trackerId}`, `device_hw_report/{trackerId}/` (der abschließende Slash ist Pflicht)
- Entitäten je Tracker: `sensor.tractive_<trackerId>_location` (State = Zonenname, `away` außerhalb, `unknown` ohne Position — der Geofence-Trigger), `sensor.tractive_<trackerId>_battery`, `binary_sensor.tractive_<trackerId>_charging`
- **Zonen:** primär die Virtual Fences aus dem Tractive-Konto; nur aktive Kreiszonen werden ausgewertet. Ist nichts lesbar, greift die konfigurierte Home-Zone (`tractive.home-latitude`/`-longitude`). Ohne beides bleibt der State `unknown` — ein Zonenzustand wird **nie** geraten, damit Geofence-Flows nicht auf erfundenen Werten feuern
- Live-Tracking wird bewusst **nicht** aktiviert (Tracker-Akku); gelesen wird nur der letzte reguläre Positionsbericht. Poll-Intervall 60 s
- Frontend-Seite „Hundetracker" (`pages/pets/`, Route `pets`): Login, Leaflet/OSM-Karte, Kacheln für Zone und Akku
```

- [ ] **Step 2: Memory-Datei anlegen**

```markdown
---
name: tractive-hundetracker
description: Tractive-GPS-Tracker via inoffizieller Cloud-API; kein Refresh-Token, daher gelegentlicher Re-Login; Zonen-State ist der Geofence-Trigger
metadata:
  type: project
---

Der Hunde-Tracker von Tractive hängt über die inoffizielle Cloud-API im System (`backend/src/main/java/com/household/manager/tractive/`, Frontend `pages/pets/`).

Nicht offensichtlich und leicht falsch zu machen:
- **Tractive gibt kein Refresh-Token aus.** Ein abgelaufenes Token kann nur durch erneutes Senden von E-Mail+Passwort ersetzt werden. Der Nutzer hat sich bewusst gegen gespeicherte Zugangsdaten entschieden — also ist ein gelegentlicher manueller Re-Login der Normalfall, kein Bug.
- `device_hw_report/{trackerId}/` braucht den **abschließenden Slash**, `device_pos_report/{trackerId}` nicht.
- Die Tracker-ID ist `device_id` aus `trackable_object/{id}` — nicht die Objekt-ID selbst.
- Der Geofence-Endpunkt ist als einziger nicht aus `aiotractive` verifiziert; Fehler dort werden geschluckt und die Home-Zone greift.
- Ohne Zonen **und** ohne Home-Koordinate bleibt der State `unknown` statt `away` — sonst würde ein Geofence-Flow bei jedem Start fälschlich „Hund ist weg" feuern.

Realer Test mit echtem Konto/Tracker steht noch aus (wie bei [[nuki-web-api-integration]] und [[blink-gesichtserkennung]]).
```

- [ ] **Step 3: Memory-Index erweitern**

An `MEMORY.md` anhängen:

```markdown
- [Tractive-Hundetracker](tractive-hundetracker.md) — kein Refresh-Token (Re-Login normal); hw_report braucht Slash; ohne Zone+Home bleibt State unknown
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Tractive-Hundetracker dokumentiert"
```

---

## Task 16: Abschluss und Verifikation

- [ ] **Step 1: Backend-Tests vollständig laufen lassen**

```bash
cd backend && mvn -q test -Dtest='Tractive*'
```
Erwartung: alle Tractive-Tests grün.

- [ ] **Step 2: Frontend bauen**

```bash
cd frontend && npm run build
```
Erwartung: Build erfolgreich.

- [ ] **Step 3: Manuelle Verifikation gegen das echte Konto**

Anwendung starten, `/pets` öffnen, mit echten Tractive-Zugangsdaten anmelden. Prüfen:
1. Nach spätestens 80 Sekunden erscheint der Hund auf der Karte.
2. Die Akku-Anzeige entspricht der Tractive-App.
3. Der Zonenname entspricht der Virtual Fence in der Tractive-App. Steht dort `away`, obwohl der Hund zu Hause ist → Task 8 Step 1 erneut ausführen und die Feldnamen korrigieren, oder `tractive.home-latitude`/`-longitude` setzen.
4. Unter „Entitäten" im Frontend erscheinen `sensor.tractive_*`.

- [ ] **Step 4: Branch abschließen**

Mit der `superpowers:finishing-a-development-branch`-Skill den Merge nach `main` durchführen.

---

## Nicht enthalten (YAGNI, aus dem Design übernommen)

- Kein Live-Tracking-Schalter, kein Buzzer/LED (Tractive kann es, wird nicht gebraucht)
- Kein historischer Positionsverlauf auf der Karte (nur aktuelle Position)
- Kein automatischer Re-Login, keine at-rest-Zugangsdaten
- Kein Aktivitäts-Sensor: der `health_overview`-Endpunkt liegt auf einer anderen Base-URL (`https://aps-api.tractive.com/api/1/`) und ist modell-/abo-abhängig. Er wird nachgezogen, sobald Task 16 Step 3 zeigt, dass der Tracker überhaupt Aktivitätsdaten liefert.
