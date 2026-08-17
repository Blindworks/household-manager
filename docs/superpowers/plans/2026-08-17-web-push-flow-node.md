# Web Push + Flow-Node `push-send` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Web-Push-Benachrichtigungen aus der Flow-Engine in die bestehende PWA (iPhone & Co.), inklusive Anmelde-Seite und neuem Aktions-Node `push-send`.

**Architecture:** Backend-Package `push/` mit VAPID-Schlüsseln in `application_settings` (auto-generiert), Subscriptions-Tabelle mit Upsert per Endpoint, Fire-and-forget-Versand über die Library `nl.martijndwars:web-push` (hinter dem Interface `WebPushClient` gekapselt), Flow-Node analog `telegram-send`. Frontend: `SwPush`-basierter Service + Seite „Benachrichtigungen"; der vorhandene `ngsw-worker.js` zeigt Nachrichten im ngsw-Notification-Schema selbst an.

**Tech Stack:** Spring Boot 3.4.1 / Java 21, Liquibase, `nl.martijndwars:web-push` 5.1.1 (BouncyCastle + jose4j transitiv), Angular 19 `@angular/service-worker` (`SwPush`).

**Spec:** `docs/superpowers/specs/2026-08-17-web-push-flow-node-design.md`

---

## Wichtige Umgebungs-Hinweise (für jeden Task)

- **Maven braucht JDK 21** (Default der Maschine ist JDK 17). Vor jedem `mvn` in der Bash-Shell:
  ```bash
  export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
  ```
  Maven liegt unter `C:\Users\bened\apache-maven-3.9.11\bin\mvn` (auf dem PATH), es gibt **kein** `mvnw`. Immer aus `backend/` heraus ausführen.
- Die lokalen Integrationstests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen auf dieser Maschine **immer** fehl („Access denied for user 'root'@'localhost'" — keine lokale Test-DB). Das ist vorbestehend; bei `mvn test` ohne `-Dtest=` diese beiden Fails ignorieren.
- **JPA-Repositories müssen in `com.household.manager.repository` liegen** — `JpaConfig` scannt nur dieses Paket. Ein Repository im `push/`-Paket würde still nicht gefunden.
- Frontend-Umlaute: Bestandscode nutzt teils `ae/oe/ue` in Strings (z. B. „Geraete"). In neuen UI-Texten genauso verfahren wie die direkt umgebenden Beispiele im jeweiligen File.

## File Structure

**Backend (neu):**
| Datei | Verantwortung |
|---|---|
| `backend/src/main/resources/db/changelog/changes/20260817-0047-create-push-subscription-table.xml` | Tabelle `push_subscription` |
| `backend/src/main/java/com/household/manager/model/entity/PushSubscription.java` | JPA-Entity |
| `backend/src/main/java/com/household/manager/repository/PushSubscriptionRepository.java` | Repository (Pflicht-Paket!) |
| `backend/src/main/java/com/household/manager/push/VapidKeyService.java` | VAPID-Schlüsselpaar erzeugen/laden |
| `backend/src/main/java/com/household/manager/push/PushDtos.java` | API-Verträge |
| `backend/src/main/java/com/household/manager/push/PushSubscriptionService.java` | Subscriptions verwalten (Upsert/Liste/Löschen) |
| `backend/src/main/java/com/household/manager/push/WebPushClient.java` | Interface — einzige Stelle mit Library-Spezifika dahinter |
| `backend/src/main/java/com/household/manager/push/MartijnDwarsWebPushClient.java` | Library-Adapter |
| `backend/src/main/java/com/household/manager/push/PushNotificationService.java` | Fire-and-forget-Versand, 404/410-Selbstbereinigung |
| `backend/src/main/java/com/household/manager/push/PushController.java` | REST-API `/v1/push` |
| `backend/src/main/java/com/household/manager/security/CurrentUserService.java` | User-Id der Session auflösen |
| `backend/src/main/java/com/household/manager/flowengine/nodes/PushSendNodeHandler.java` | Flow-Node `push-send` |

**Frontend (neu):** `models/push.model.ts`, `services/push.service.ts`, `pages/notifications/notifications.component.{ts,html,scss}`

**Modifiziert:** `backend/pom.xml`, `db.changelog-master.xml`, `SecurityRulesTest.java`, `frontend/src/app/app.routes.ts`, `frontend/src/app/components/header/header.component.ts`, `CLAUDE.md`

---

### Task 1: Dependency + Liquibase-Migration

**Files:**
- Modify: `backend/pom.xml` (im `<dependencies>`-Block, z. B. nach der `org.dmfs`-Dependency)
- Create: `backend/src/main/resources/db/changelog/changes/20260817-0047-create-push-subscription-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (vor `</databaseChangeLog>`)

- [ ] **Step 1: Dependency in `backend/pom.xml` ergänzen**

```xml
        <!-- Web Push (VAPID + aes128gcm) fuer PWA-Benachrichtigungen; BouncyCastle + jose4j kommen transitiv -->
        <dependency>
            <groupId>nl.martijndwars</groupId>
            <artifactId>web-push</artifactId>
            <version>5.1.1</version>
        </dependency>
```

- [ ] **Step 2: Changelog-Datei anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260817-0047-create-push-subscription" author="claude">
        <comment>Web-Push-Subscriptions der PWA (ein Geraet = eine Zeile); Loeschen des Nutzers raeumt seine Geraete mit ab</comment>
        <createTable tableName="push_subscription">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="user_id" type="BIGINT">
                <constraints nullable="false"/>
            </column>
            <column name="endpoint" type="VARCHAR(500)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="p256dh_key" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="auth_secret" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="device_label" type="VARCHAR(255)"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <!-- fachlicher Zeitstempel (letzter erfolgreicher Versand), bewusst DATETIME -->
            <column name="last_used_at" type="DATETIME"/>
        </createTable>
        <addForeignKeyConstraint baseTableName="push_subscription" baseColumnNames="user_id"
                                 referencedTableName="app_user" referencedColumnNames="id"
                                 constraintName="fk_push_subscription_user" onDelete="CASCADE"/>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Include im Master-Changelog ergänzen** (nach dem Toni-Futtervorrat-Include)

```xml
    <!-- Web-Push-Subscriptions der PWA -->
    <include file="db/changelog/changes/20260817-0047-create-push-subscription-table.xml"/>
```

- [ ] **Step 4: Kompilieren, Dependency-Auflösung prüfen**

Run:
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn -q compile
```
Expected: BUILD SUCCESS (Dependency lädt; noch kein neuer Code).

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/db/changelog
git commit -m "feat(push): web-push-Dependency und push_subscription-Tabelle"
```

---

### Task 2: Entity + Repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/PushSubscription.java`
- Create: `backend/src/main/java/com/household/manager/repository/PushSubscriptionRepository.java`

- [ ] **Step 1: Entity anlegen**

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Web-Push-Subscription eines Geraets (PWA). Ein Geraet = eine Zeile, Schluessel: endpoint. */
@Entity
@Table(name = "push_subscription")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 500, unique = true)
    private String endpoint;

    @Column(name = "p256dh_key", nullable = false)
    private String p256dhKey;

    @Column(name = "auth_secret", nullable = false)
    private String authSecret;

    @Column(name = "device_label")
    private String deviceLabel;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
```

- [ ] **Step 2: Repository anlegen** (zwingend in `com.household.manager.repository` — `JpaConfig` scannt nur dieses Paket)

```java
package com.household.manager.repository;

import com.household.manager.model.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserId(Long userId);

    Optional<PushSubscription> findByIdAndUserId(Long id, Long userId);
}
```

- [ ] **Step 3: Kompilieren**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/model/entity/PushSubscription.java backend/src/main/java/com/household/manager/repository/PushSubscriptionRepository.java
git commit -m "feat(push): PushSubscription-Entity und -Repository"
```

---

### Task 3: VapidKeyService (TDD)

**Files:**
- Test: `backend/src/test/java/com/household/manager/push/VapidKeyServiceTest.java`
- Create: `backend/src/main/java/com/household/manager/push/VapidKeyService.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.push;

import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VapidKeyServiceTest {

    @Mock
    private ApplicationSettingsService settings;

    @Test
    void generatesAndPersistsKeyPairOnFirstAccess() {
        when(settings.getString(eq("PUSH_VAPID"), anyString(), isNull())).thenReturn(null);

        VapidKeyService.VapidKeys keys = new VapidKeyService(settings).keyPair();

        byte[] publicKey = Base64.getUrlDecoder().decode(keys.publicKey());
        assertEquals(65, publicKey.length);
        assertEquals(0x04, publicKey[0]);
        assertFalse(keys.privateKey().isBlank());
        verify(settings).saveSettings(eq("PUSH_VAPID"), argThat(map ->
                map.get("publicKey").equals(keys.publicKey())
                        && map.get("privateKey").equals(keys.privateKey())));
    }

    @Test
    void returnsStoredKeysWithoutRegenerating() {
        when(settings.getString("PUSH_VAPID", "publicKey", null)).thenReturn("pub");
        when(settings.getString("PUSH_VAPID", "privateKey", null)).thenReturn("priv");

        VapidKeyService.VapidKeys keys = new VapidKeyService(settings).keyPair();

        assertEquals("pub", keys.publicKey());
        assertEquals("priv", keys.privateKey());
        verify(settings, never()).saveSettings(anyString(), anyMap());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest=VapidKeyServiceTest`
Expected: Compile-Fehler „cannot find symbol: class VapidKeyService"

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.push;

import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;
import java.util.Map;

/**
 * VAPID-Schluesselpaar in application_settings (Kategorie PUSH_VAPID), beim
 * ersten Zugriff automatisch erzeugt — bewusst keine Env-Variable, damit der
 * Rollout keinen manuellen Schritt braucht.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VapidKeyService {

    static final String CATEGORY = "PUSH_VAPID";
    static final String KEY_PUBLIC = "publicKey";
    static final String KEY_PRIVATE = "privateKey";

    private final ApplicationSettingsService settings;
    private final Object lock = new Object();

    public String publicKey() {
        return keyPair().publicKey();
    }

    public VapidKeys keyPair() {
        synchronized (lock) {
            String publicKey = settings.getString(CATEGORY, KEY_PUBLIC, null);
            String privateKey = settings.getString(CATEGORY, KEY_PRIVATE, null);
            if (publicKey != null && privateKey != null) {
                return new VapidKeys(publicKey, privateKey);
            }
            VapidKeys generated = generate();
            settings.saveSettings(CATEGORY, Map.of(
                    KEY_PUBLIC, generated.publicKey(),
                    KEY_PRIVATE, generated.privateKey()));
            log.info("VAPID-Schluesselpaar erzeugt und gespeichert");
            return generated;
        }
    }

    private VapidKeys generate() {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
            generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"), new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new VapidKeys(
                    encoder.encodeToString(Utils.encode((ECPublicKey) pair.getPublic())),
                    encoder.encodeToString(Utils.encode((ECPrivateKey) pair.getPrivate())));
        } catch (Exception ex) {
            throw new IllegalStateException("VAPID-Schluesselerzeugung fehlgeschlagen", ex);
        }
    }

    public record VapidKeys(String publicKey, String privateKey) {}
}
```

Hinweis: `Utils` kommt aus `nl.martijndwars.webpush`, die `ECPublicKey`/`ECPrivateKey`-Interfaces aus `org.bouncycastle.jce.interfaces` (nicht `java.security.interfaces`!). Sollte sich die `Utils.encode`-Signatur in der Library-Version unterscheiden, `mvn dependency:tree -Dincludes=org.bouncycastle` prüfen und die tatsächlich vorhandenen Methoden verwenden — die Kapselung bleibt in dieser Klasse.

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest=VapidKeyServiceTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/push backend/src/test/java/com/household/manager/push
git commit -m "feat(push): VapidKeyService mit auto-generiertem Schluesselpaar"
```

---

### Task 4: DTOs + PushSubscriptionService (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/push/PushDtos.java`
- Test: `backend/src/test/java/com/household/manager/push/PushSubscriptionServiceTest.java`
- Create: `backend/src/main/java/com/household/manager/push/PushSubscriptionService.java`

- [ ] **Step 1: DTOs anlegen**

```java
package com.household.manager.push;

import java.time.LocalDateTime;

/** API-Vertraege der Push-Endpunkte. */
public final class PushDtos {

    private PushDtos() {
    }

    public record PublicKeyResponse(String publicKey) {}

    public record SubscribeRequest(String endpoint, String p256dh, String auth, String userAgent) {}

    /** endpoint ist enthalten, damit das Frontend "dieses Geraet" per Vergleich erkennen kann. */
    public record SubscriptionResponse(Long id, String deviceLabel, LocalDateTime createdAt,
                                       LocalDateTime lastUsedAt, String endpoint) {}
}
```

- [ ] **Step 2: Failing Test schreiben**

```java
package com.household.manager.push;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    @Mock
    private PushSubscriptionRepository repository;
    @Mock
    private AuditService auditService;

    private PushSubscriptionService service() {
        return new PushSubscriptionService(repository, auditService);
    }

    private PushDtos.SubscribeRequest request() {
        return new PushDtos.SubscribeRequest("https://web.push.apple.com/abc", "p256dh-key", "auth-secret",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)");
    }

    @Test
    void subscribeCreatesNewSubscriptionWithDeviceLabel() {
        when(repository.findByEndpoint("https://web.push.apple.com/abc")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PushDtos.SubscriptionResponse response = service().subscribe(7L, request());

        assertEquals("iPhone", response.deviceLabel());
        verify(auditService).record(eq("push.subscribe"), anyString());
    }

    @Test
    void subscribeUpsertsExistingEndpointInsteadOfDuplicating() {
        PushSubscription existing = PushSubscription.builder().id(3L)
                .endpoint("https://web.push.apple.com/abc").userId(1L)
                .p256dhKey("old").authSecret("old").build();
        when(repository.findByEndpoint("https://web.push.apple.com/abc")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().subscribe(7L, request());

        ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
        verify(repository).save(captor.capture());
        assertEquals(3L, captor.getValue().getId());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals("p256dh-key", captor.getValue().getP256dhKey());
    }

    @Test
    void subscribeRejectsMissingFieldsAndNonHttpsEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> service().subscribe(7L,
                new PushDtos.SubscribeRequest("", "k", "a", null)));
        assertThrows(IllegalArgumentException.class, () -> service().subscribe(7L,
                new PushDtos.SubscribeRequest("http://insecure", "k", "a", null)));
    }

    @Test
    void unsubscribeOnlyDeletesOwnSubscription() {
        when(repository.findByIdAndUserId(5L, 7L)).thenReturn(Optional.empty());

        assertFalse(service().unsubscribe(7L, 5L));
        verify(repository, never()).delete(any());
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest=PushSubscriptionServiceTest`
Expected: Compile-Fehler „cannot find symbol: class PushSubscriptionService"

- [ ] **Step 4: Implementierung**

```java
package com.household.manager.push;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Verwaltung der Web-Push-Subscriptions. Anmelden ist ein Upsert per Endpoint —
 * erneutes Abonnieren desselben Geraets erzeugt keine Dublette, sondern
 * aktualisiert Schluessel und Besitzer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushSubscriptionService {

    private static final int MAX_ENDPOINT_LENGTH = 500;

    private final PushSubscriptionRepository repository;
    private final AuditService auditService;

    @Transactional
    public PushDtos.SubscriptionResponse subscribe(Long userId, PushDtos.SubscribeRequest request) {
        String endpoint = validated(request);
        PushSubscription subscription = repository.findByEndpoint(endpoint)
                .orElseGet(() -> PushSubscription.builder().endpoint(endpoint).build());
        subscription.setUserId(userId);
        subscription.setP256dhKey(request.p256dh().trim());
        subscription.setAuthSecret(request.auth().trim());
        subscription.setDeviceLabel(deviceLabel(request.userAgent()));
        PushSubscription saved = repository.save(subscription);
        auditService.record("push.subscribe", "Geraet: " + saved.getDeviceLabel());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PushDtos.SubscriptionResponse> listForUser(Long userId) {
        return repository.findByUserId(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public boolean unsubscribe(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .map(subscription -> {
                    repository.delete(subscription);
                    auditService.record("push.unsubscribe", "Geraet: " + subscription.getDeviceLabel());
                    return true;
                })
                .orElse(false);
    }

    private String validated(PushDtos.SubscribeRequest request) {
        if (request == null || isBlank(request.endpoint()) || isBlank(request.p256dh()) || isBlank(request.auth())) {
            throw new IllegalArgumentException("endpoint, p256dh und auth sind Pflichtfelder");
        }
        String endpoint = request.endpoint().trim();
        if (!endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("endpoint muss eine https-URL sein");
        }
        if (endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw new IllegalArgumentException("endpoint ist zu lang (max. " + MAX_ENDPOINT_LENGTH + " Zeichen)");
        }
        return endpoint;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Grobe, rein kosmetische Geraetebezeichnung aus dem User-Agent. */
    private String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unbekanntes Geraet";
        }
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Android")) {
            return "Android-Geraet";
        }
        if (userAgent.contains("Macintosh")) {
            return "Mac";
        }
        if (userAgent.contains("Windows")) {
            return "Windows-PC";
        }
        return "Unbekanntes Geraet";
    }

    private PushDtos.SubscriptionResponse toResponse(PushSubscription subscription) {
        return new PushDtos.SubscriptionResponse(
                subscription.getId(),
                subscription.getDeviceLabel(),
                subscription.getCreatedAt(),
                subscription.getLastUsedAt(),
                subscription.getEndpoint());
    }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest=PushSubscriptionServiceTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/push backend/src/test/java/com/household/manager/push
git commit -m "feat(push): Subscription-Verwaltung mit Endpoint-Upsert"
```

---

### Task 5: WebPushClient + PushNotificationService (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/push/WebPushClient.java`
- Create: `backend/src/main/java/com/household/manager/push/MartijnDwarsWebPushClient.java`
- Test: `backend/src/test/java/com/household/manager/push/PushNotificationServiceTest.java`
- Create: `backend/src/main/java/com/household/manager/push/PushNotificationService.java`

- [ ] **Step 1: Interface anlegen**

```java
package com.household.manager.push;

import com.household.manager.model.entity.PushSubscription;

/**
 * Duenne Abstraktion ueber die Web-Push-Library — die einzige Stelle mit
 * Library-Spezifika ist die Implementierung, und der PushNotificationService
 * bleibt ohne echte Krypto testbar.
 */
public interface WebPushClient {

    /** Sendet die Payload an die Subscription und liefert den HTTP-Status des Push-Dienstes. */
    int send(PushSubscription subscription, String payload) throws Exception;
}
```

- [ ] **Step 2: Failing Test schreiben**

```java
package com.household.manager.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock
    private PushSubscriptionRepository repository;
    @Mock
    private WebPushClient webPushClient;

    private PushNotificationService service() {
        return new PushNotificationService(repository, webPushClient, new ObjectMapper());
    }

    private PushSubscription subscription(long id) {
        return PushSubscription.builder().id(id).userId(1L)
                .endpoint("https://push.example/" + id).p256dhKey("k").authSecret("a")
                .deviceLabel("iPhone").build();
    }

    @Test
    void deletesExpiredSubscriptionOn410() throws Exception {
        when(repository.findAll()).thenReturn(List.of(subscription(1)));
        when(webPushClient.send(any(), anyString())).thenReturn(410);

        service().sendToAll("Titel", "Text");

        verify(repository).deleteById(1L);
    }

    @Test
    void oneFailingDeviceDoesNotStopTheOthers() throws Exception {
        when(repository.findAll()).thenReturn(List.of(subscription(1), subscription(2)));
        when(webPushClient.send(any(), anyString()))
                .thenThrow(new RuntimeException("kaputt"))
                .thenReturn(201);

        assertDoesNotThrow(() -> service().sendToAll("Titel", "Text"));

        verify(webPushClient, times(2)).send(any(), anyString());
    }

    @Test
    void payloadFollowsNgswNotificationSchema() throws Exception {
        when(repository.findAll()).thenReturn(List.of(subscription(1)));
        when(webPushClient.send(any(), anyString())).thenReturn(201);

        service().sendToAll("Titel", "Text");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(webPushClient).send(any(), payload.capture());
        assertTrue(payload.getValue().contains("\"notification\""));
        assertTrue(payload.getValue().contains("\"title\":\"Titel\""));
        assertTrue(payload.getValue().contains("\"openWindow\""));
    }

    @Test
    void noSubscriptionsMeansNoSendAndNoError() {
        when(repository.findByUserId(9L)).thenReturn(List.of());

        assertDoesNotThrow(() -> service().sendToUser(9L, "Titel", "Text"));

        verifyNoInteractions(webPushClient);
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest=PushNotificationServiceTest`
Expected: Compile-Fehler „cannot find symbol: class PushNotificationService"

- [ ] **Step 4: PushNotificationService implementieren**

```java
package com.household.manager.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Fire-and-forget-Versand von Web-Push-Nachrichten (Muster Telegram): wirft
 * nie, Fehler einzelner Geraete stoppen die anderen nicht. 404/410 vom
 * Push-Dienst loescht die verfallene Subscription (Selbstbereinigung — iOS
 * laesst Subscriptions bei laengerer Nichtnutzung verfallen).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final PushSubscriptionRepository repository;
    private final WebPushClient webPushClient;
    private final ObjectMapper objectMapper;

    public void sendToAll(String title, String body) {
        send(repository.findAll(), title, body);
    }

    public void sendToUser(Long userId, String title, String body) {
        List<PushSubscription> subscriptions = repository.findByUserId(userId);
        if (subscriptions.isEmpty()) {
            log.warn("Keine Push-Subscriptions fuer Nutzer {} — Nachricht verworfen", userId);
            return;
        }
        send(subscriptions, title, body);
    }

    private void send(List<PushSubscription> subscriptions, String title, String body) {
        if (subscriptions.isEmpty()) {
            log.debug("Keine Push-Subscriptions vorhanden — Nachricht verworfen");
            return;
        }
        String payload;
        try {
            payload = buildPayload(title, body);
        } catch (JsonProcessingException ex) {
            log.warn("Push-Payload nicht serialisierbar: {}", ex.getMessage());
            return;
        }
        subscriptions.forEach(subscription -> sendTo(subscription, payload));
    }

    private void sendTo(PushSubscription subscription, String payload) {
        try {
            int status = webPushClient.send(subscription, payload);
            if (status == 404 || status == 410) {
                repository.deleteById(subscription.getId());
                log.info("Push-Subscription '{}' verfallen (HTTP {}) — geloescht",
                        subscription.getDeviceLabel(), status);
            } else if (status >= 400) {
                log.warn("Push an '{}' fehlgeschlagen: HTTP {}", subscription.getDeviceLabel(), status);
            } else {
                subscription.setLastUsedAt(LocalDateTime.now());
                repository.save(subscription);
            }
        } catch (Exception ex) {
            log.warn("Push an '{}' fehlgeschlagen: {}", subscription.getDeviceLabel(), ex.getMessage());
        }
    }

    /** Payload im ngsw-Notification-Schema — der Angular Service Worker zeigt sie selbst an. */
    private String buildPayload(String title, String body) throws JsonProcessingException {
        Map<String, Object> notification = Map.of(
                "title", title,
                "body", body,
                "data", Map.of("onActionClick",
                        Map.of("default", Map.of("operation", "openWindow", "url", "/"))));
        return objectMapper.writeValueAsString(Map.of("notification", notification));
    }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest=PushNotificationServiceTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: Library-Adapter implementieren**

```java
package com.household.manager.push;

import com.household.manager.model.entity.PushSubscription;
import lombok.RequiredArgsConstructor;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;

/**
 * Einzige Stelle mit nl.martijndwars:web-push-Spezifika. Der PushService wird
 * lazy gebaut, damit die VAPID-Schluessel erst beim ersten Versand erzeugt
 * werden muessen (nicht beim Boot).
 */
@Component
@RequiredArgsConstructor
public class MartijnDwarsWebPushClient implements WebPushClient {

    /** VAPID-Subject: Kontakt fuer den Push-Dienst-Betreiber (Apple verlangt einen validen Wert). */
    private static final String VAPID_SUBJECT = "mailto:benedikt.lind@gmail.com";

    private final VapidKeyService vapidKeyService;
    private volatile PushService pushService;

    @Override
    public int send(PushSubscription subscription, String payload) throws Exception {
        Notification notification = new Notification(
                subscription.getEndpoint(),
                subscription.getP256dhKey(),
                subscription.getAuthSecret(),
                payload.getBytes(StandardCharsets.UTF_8));
        HttpResponse response = pushService().send(notification);
        return response.getStatusLine().getStatusCode();
    }

    private PushService pushService() throws GeneralSecurityException {
        if (pushService == null) {
            synchronized (this) {
                if (pushService == null) {
                    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                        Security.addProvider(new BouncyCastleProvider());
                    }
                    VapidKeyService.VapidKeys keys = vapidKeyService.keyPair();
                    pushService = new PushService(keys.publicKey(), keys.privateKey(), VAPID_SUBJECT);
                }
            }
        }
        return pushService;
    }
}
```

Hinweis: Falls `HttpResponse`/`getStatusLine` nicht kompiliert (Library-Version nutzt evtl. eine andere HTTP-Abstraktion), die tatsächliche Rückgabe von `PushService.send(...)` in der installierten 5.1.1 nachsehen (IDE/`javap`) und nur diese Klasse anpassen — Interface und Service bleiben unverändert.

- [ ] **Step 7: Alles kompilieren + Commit**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn -q compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/com/household/manager/push backend/src/test/java/com/household/manager/push
git commit -m "feat(push): Fire-and-forget-Versand mit 410-Selbstbereinigung"
```

---

### Task 6: CurrentUserService + PushController + SecurityRulesTest

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/CurrentUserService.java`
- Create: `backend/src/main/java/com/household/manager/push/PushController.java`
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: CurrentUserService anlegen**

```java
package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Liefert die App-User-Id der aktuellen Session. Leer bei Service-Tokens und
 * ausserhalb eines Request-Kontexts.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final AppUserRepository repository;

    public Optional<Long> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            return Optional.ofNullable(principal.getId());
        }
        if (authentication.getPrincipal() instanceof UserDetails details) {
            return repository.findByUsername(details.getUsername()).map(AppUser::getId);
        }
        return Optional.empty();
    }

    /** IllegalStateException wird vom GlobalExceptionHandler als 400 abgebildet. */
    public Long requireUserId() {
        return currentUserId().orElseThrow(() ->
                new IllegalStateException("Diese Aktion braucht eine Nutzer-Session (kein Service-Token)"));
    }
}
```

- [ ] **Step 2: PushController anlegen**

```java
package com.household.manager.push;

import com.household.manager.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Push-API. Lesen faellt unter die generische GET-KIOSK-Regel, Schreiben unter
 * anyRequest -> MEMBER; eine eigene Security-Regel gibt es bewusst nicht
 * (SecurityRulesTest haelt beide Richtungen fest).
 */
@RestController
@RequestMapping("/v1/push")
@RequiredArgsConstructor
public class PushController {

    private final VapidKeyService vapidKeyService;
    private final PushSubscriptionService subscriptionService;
    private final PushNotificationService notificationService;
    private final CurrentUserService currentUserService;

    @GetMapping("/vapid-public-key")
    public PushDtos.PublicKeyResponse publicKey() {
        return new PushDtos.PublicKeyResponse(vapidKeyService.publicKey());
    }

    @GetMapping("/subscriptions")
    public List<PushDtos.SubscriptionResponse> mySubscriptions() {
        return subscriptionService.listForUser(currentUserService.requireUserId());
    }

    @PostMapping("/subscriptions")
    public PushDtos.SubscriptionResponse subscribe(@RequestBody PushDtos.SubscribeRequest request) {
        return subscriptionService.subscribe(currentUserService.requireUserId(), request);
    }

    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long id) {
        return subscriptionService.unsubscribe(currentUserService.requireUserId(), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Void> sendTest() {
        notificationService.sendToUser(currentUserService.requireUserId(),
                "Household Manager", "Testnachricht — Push funktioniert auf diesem Geraet.");
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 3: SecurityRulesTest erweitern**

In der `@WebMvcTest(controllers = {...})`-Liste `PushController.class` ergänzen (Import `com.household.manager.push.PushController`). Bei den `@MockitoBean`-Feldern ergänzen:

```java
    @MockitoBean
    private com.household.manager.push.VapidKeyService vapidKeyService;
    @MockitoBean
    private com.household.manager.push.PushSubscriptionService pushSubscriptionService;
    @MockitoBean
    private com.household.manager.push.PushNotificationService pushNotificationService;
    @MockitoBean
    private CurrentUserService currentUserService;
```

Neue Tests am Ende der Klasse (vor der schließenden Klammer):

```java
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfVapidPublicKeyLesen() throws Exception {
        when(vapidKeyService.publicKey()).thenReturn("key");
        mockMvc.perform(get("/v1/push/vapid-public-key")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeinePushSubscriptionAnlegen() throws Exception {
        mockMvc.perform(post("/v1/push/subscriptions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\": \"https://x\", \"p256dh\": \"k\", \"auth\": \"a\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfPushSubscriptionAnlegen() throws Exception {
        when(currentUserService.requireUserId()).thenReturn(1L);
        mockMvc.perform(post("/v1/push/subscriptions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\": \"https://x\", \"p256dh\": \"k\", \"auth\": \"a\"}"))
                .andExpect(status().isOk());
    }
```

(`when` ist in der Testklasse bereits statisch importiert.)

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest=SecurityRulesTest`
Expected: alle Tests grün (auch die bestehenden — falls einer der Bestandstests bricht, wurde etwas an der Config statt am Test geändert: stoppen und analysieren).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/push backend/src/main/java/com/household/manager/security/CurrentUserService.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(push): REST-API /v1/push mit Rollenmatrix-Tests"
```

---

### Task 7: Flow-Node `push-send` (TDD)

**Files:**
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/PushSendNodeHandlerTest.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/PushSendNodeHandler.java`

- [ ] **Step 1: Failing Test schreiben** (Muster: `TelegramSendNodeHandlerTest`)

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.push.PushNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PushSendNodeHandlerTest {

    @Mock
    private PushNotificationService notificationService;

    private PushSendNodeHandler handler() {
        return new PushSendNodeHandler(notificationService);
    }

    @Test
    void broadcastsWithDefaultTitleAndResolvedPlaceholders() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "{entityId} ist jetzt {newState}"));
        FlowMessage msg = FlowMessage.of(Map.of("entityId", "switch.x", "newState", "on", "oldState", "off"));

        NodeResult result = handler().handle(msg, cfg, null);

        verify(notificationService).sendToAll("Household Manager", "switch.x ist jetzt on");
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void explicitUserIdSendsOnlyToThatUser() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi", "title", "Alarm", "userId", "7"));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        verify(notificationService).sendToUser(7L, "Alarm", "hi");
    }

    @Test
    void titleSupportsPlaceholdersToo() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "x", "title", "{entityId}"));

        handler().handle(FlowMessage.of(Map.of("entityId", "sensor.tuer")), cfg, null);

        verify(notificationService).sendToAll("sensor.tuer", "x");
    }

    @Test
    void validateRequiresMessageAndNumericUserId() {
        assertFalse(handler().validate(NodeConfig.empty()).isEmpty());
        assertFalse(handler().validate(new NodeConfig(Map.of("message", "x", "userId", "abc"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("message", "x"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("message", "x", "userId", "7"))).isEmpty());
    }

    @Test
    void typeAndPortsMatchCatalogExpectations() {
        assertEquals("push-send", handler().type());
        assertEquals(1, handler().outputPorts());
        assertFalse(handler().fields().isEmpty());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest=PushSendNodeHandlerTest`
Expected: Compile-Fehler „cannot find symbol: class PushSendNodeHandler"

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.push.PushNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktions-Node: Web-Push-Benachrichtigung an alle abonnierten Geraete (oder
 * nur die eines Nutzers). Platzhalter: {entityId}, {newState}, {oldState}.
 * Sendefehler schluckt der PushNotificationService — der Flow laeuft weiter.
 */
@Component
@RequiredArgsConstructor
public class PushSendNodeHandler implements NodeHandler {

    private static final String DEFAULT_TITLE = "Household Manager";

    private final PushNotificationService notificationService;

    @Override
    public String type() {
        return "push-send";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("message").isEmpty()) {
            errors.add("message fehlt");
        }
        config.string("userId")
                .map(String::trim)
                .filter(userId -> !userId.isEmpty())
                .ifPresent(userId -> {
                    try {
                        Long.parseLong(userId);
                    } catch (NumberFormatException ex) {
                        errors.add("userId muss numerisch sein");
                    }
                });
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String title = render(config.string("title").filter(s -> !s.isBlank()).orElse(DEFAULT_TITLE), message);
        String body = render(config.string("message").orElse(""), message);
        config.string("userId")
                .map(String::trim)
                .filter(userId -> !userId.isEmpty())
                .ifPresentOrElse(
                        userId -> notificationService.sendToUser(Long.parseLong(userId), title, body),
                        () -> notificationService.sendToAll(title, body));
        return NodeResult.single(message);
    }

    private String render(String template, FlowMessage message) {
        return template
                .replace("{entityId}", stringValue(message, "entityId"))
                .replace("{newState}", stringValue(message, "newState"))
                .replace("{oldState}", stringValue(message, "oldState"));
    }

    private String stringValue(FlowMessage message, String key) {
        Object value = message.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("message", "Nachricht", NodeFieldType.STRING, true),
                NodeFieldDescriptor.field("title", "Titel (leer = Household Manager)", NodeFieldType.STRING, false),
                NodeFieldDescriptor.field("userId", "Nutzer-ID (leer = alle Geraete)", NodeFieldType.STRING, false));
    }
}
```

- [ ] **Step 4: Tests laufen lassen — Node-Test und Katalog-Test müssen grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test -Dtest='PushSendNodeHandlerTest,NodeCatalogFieldsTest'`
Expected: alle grün (der Katalog-Test läuft über alle registrierten Handler — schlägt er fehl, die Meldung lesen: vermutlich Feld-Konvention).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes/PushSendNodeHandler.java backend/src/test/java/com/household/manager/flowengine/nodes/PushSendNodeHandlerTest.java
git commit -m "feat(flow): Aktions-Node push-send"
```

---

### Task 8: Frontend — Model + PushService

**Files:**
- Create: `frontend/src/app/models/push.model.ts`
- Create: `frontend/src/app/services/push.service.ts`

- [ ] **Step 1: Model anlegen**

```typescript
/** Ein beim Backend registriertes Push-Geraet (eigene Subscription). */
export interface PushDevice {
  id: number;
  deviceLabel: string;
  createdAt: string;
  lastUsedAt: string | null;
  endpoint: string;
}
```

- [ ] **Step 2: Service anlegen**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { SwPush } from '@angular/service-worker';
import { Observable, firstValueFrom, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PushDevice } from '../models/push.model';

/** Web-Push: Anmeldung dieses Geraets + Verwaltung der eigenen Geraete. */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly baseUrl = '/api/v1/push';

  /** Push braucht einen aktiven Service Worker und die Notification-API (Dev-Server: SW aus -> false). */
  get isSupported(): boolean {
    return this.swPush.isEnabled && 'Notification' in window;
  }

  get permission(): NotificationPermission | null {
    return 'Notification' in window ? Notification.permission : null;
  }

  getDevices(): Observable<PushDevice[]> {
    return this.http.get<PushDevice[]>(`${this.baseUrl}/subscriptions`)
      .pipe(catchError(this.handleError));
  }

  /** Endpoint der auf DIESEM Geraet aktiven Subscription (null = keine). */
  async currentEndpoint(): Promise<string | null> {
    if (!this.isSupported) {
      return null;
    }
    const subscription = await firstValueFrom(this.swPush.subscription);
    return subscription?.endpoint ?? null;
  }

  /** Fragt die Berechtigung an (nur aus einer Nutzer-Geste aufrufen!) und registriert das Geraet. */
  async subscribeThisDevice(): Promise<void> {
    const { publicKey } = await firstValueFrom(
      this.http.get<{ publicKey: string }>(`${this.baseUrl}/vapid-public-key`));
    const subscription = await this.swPush.requestSubscription({ serverPublicKey: publicKey });
    const json = subscription.toJSON();
    await firstValueFrom(this.http.post(`${this.baseUrl}/subscriptions`, {
      endpoint: json.endpoint,
      p256dh: json.keys?.['p256dh'],
      auth: json.keys?.['auth'],
      userAgent: navigator.userAgent
    }));
  }

  /** Meldet DIESES Geraet ab (serverseitig + lokal). */
  async unsubscribeThisDevice(devices: PushDevice[]): Promise<void> {
    const endpoint = await this.currentEndpoint();
    const device = endpoint ? devices.find(d => d.endpoint === endpoint) : undefined;
    if (device) {
      await firstValueFrom(this.deleteDevice(device.id));
    }
    await this.swPush.unsubscribe().catch(() => undefined);
  }

  deleteDevice(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/subscriptions/${id}`)
      .pipe(catchError(this.handleError));
  }

  sendTest(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/test`, {})
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Push-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Push-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/models/push.model.ts frontend/src/app/services/push.service.ts
git commit -m "feat(frontend): PushService fuer Web-Push-Anmeldung"
```

---

### Task 9: Frontend — Seite „Benachrichtigungen" + Route + Navi

**Files:**
- Create: `frontend/src/app/pages/notifications/notifications.component.ts`
- Create: `frontend/src/app/pages/notifications/notifications.component.html`
- Create: `frontend/src/app/pages/notifications/notifications.component.scss`
- Modify: `frontend/src/app/app.routes.ts` (vor der `login`-Route)
- Modify: `frontend/src/app/components/header/header.component.ts` (Smart-Home-Children, nach `Futtervorrat`)

- [ ] **Step 1: Component-Klasse anlegen**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { PushService } from '../../services/push.service';
import { PushDevice } from '../../models/push.model';

type PushStatus = 'unsupported' | 'denied' | 'inactive' | 'active';

/** Seite "Benachrichtigungen": Web-Push fuer dieses Geraet aktivieren und eigene Geraete verwalten. */
@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './notifications.component.html',
  styleUrls: ['./notifications.component.scss']
})
export class NotificationsComponent implements OnInit {
  private readonly pushService = inject(PushService);

  status: PushStatus = 'inactive';
  devices: PushDevice[] = [];
  currentEndpoint: string | null = null;
  busy = false;
  errorMessage = '';
  infoMessage = '';

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    if (!this.pushService.isSupported) {
      this.status = 'unsupported';
    } else if (this.pushService.permission === 'denied') {
      this.status = 'denied';
    } else {
      this.currentEndpoint = await this.pushService.currentEndpoint();
      this.status = this.currentEndpoint ? 'active' : 'inactive';
    }
    this.loadDevices();
  }

  private loadDevices(): void {
    this.pushService.getDevices().subscribe({
      next: devices => this.devices = devices,
      error: err => this.errorMessage = err.message
    });
  }

  async activate(): Promise<void> {
    this.busy = true;
    this.errorMessage = '';
    this.infoMessage = '';
    try {
      await this.pushService.subscribeThisDevice();
      this.infoMessage = 'Benachrichtigungen aktiviert.';
    } catch (err) {
      this.errorMessage = this.pushService.permission === 'denied'
        ? 'Berechtigung verweigert — bitte in den Browser-Einstellungen erlauben.'
        : 'Aktivierung fehlgeschlagen: ' + (err instanceof Error ? err.message : String(err));
    } finally {
      this.busy = false;
      await this.refresh();
    }
  }

  async deactivate(): Promise<void> {
    this.busy = true;
    this.errorMessage = '';
    this.infoMessage = '';
    try {
      await this.pushService.unsubscribeThisDevice(this.devices);
      this.infoMessage = 'Benachrichtigungen fuer dieses Geraet deaktiviert.';
    } catch (err) {
      this.errorMessage = err instanceof Error ? err.message : String(err);
    } finally {
      this.busy = false;
      await this.refresh();
    }
  }

  removeDevice(device: PushDevice): void {
    this.pushService.deleteDevice(device.id).subscribe({
      next: () => this.refresh(),
      error: err => this.errorMessage = err.message
    });
  }

  sendTest(): void {
    this.infoMessage = '';
    this.pushService.sendTest().subscribe({
      next: () => this.infoMessage = 'Testnachricht verschickt.',
      error: err => this.errorMessage = err.message
    });
  }

  isCurrentDevice(device: PushDevice): boolean {
    return device.endpoint === this.currentEndpoint;
  }
}
```

- [ ] **Step 2: Template anlegen**

```html
<div class="notifications-page">
  <h1>Benachrichtigungen</h1>

  <div class="ios-hint">
    <strong>iPhone/iPad:</strong> Push funktioniert nur in der installierten App
    (Safari &rarr; Teilen &rarr; &bdquo;Zum Home-Bildschirm&ldquo;) und ab iOS 16.4 &mdash;
    nicht im Safari-Tab.
  </div>

  <div class="status-card">
    @switch (status) {
      @case ('unsupported') {
        <p>Dieses Geraet bzw. dieser Browser unterstuetzt keine Push-Benachrichtigungen.</p>
      }
      @case ('denied') {
        <p>Die Benachrichtigungs-Berechtigung wurde verweigert. Bitte in den Browser- bzw.
          iOS-Einstellungen wieder erlauben.</p>
      }
      @case ('inactive') {
        <p>Push-Benachrichtigungen sind auf diesem Geraet nicht aktiv.</p>
        <button type="button" class="primary" (click)="activate()" [disabled]="busy">
          Benachrichtigungen aktivieren
        </button>
      }
      @case ('active') {
        <p>Push-Benachrichtigungen sind auf diesem Geraet aktiv.</p>
        <div class="actions">
          <button type="button" class="primary" (click)="sendTest()" [disabled]="busy">
            Testnachricht senden
          </button>
          <button type="button" class="secondary" (click)="deactivate()" [disabled]="busy">
            Auf diesem Geraet deaktivieren
          </button>
        </div>
      }
    }
  </div>

  @if (errorMessage) {
    <p class="message error">{{ errorMessage }}</p>
  }
  @if (infoMessage) {
    <p class="message info">{{ infoMessage }}</p>
  }

  <h2>Angemeldete Geraete</h2>
  @if (devices.length === 0) {
    <p class="empty">Keine Geraete angemeldet.</p>
  }
  <ul class="device-list">
    @for (device of devices; track device.id) {
      <li>
        <span class="device-label">
          {{ device.deviceLabel }}
          @if (isCurrentDevice(device)) {
            <em>(dieses Geraet)</em>
          }
        </span>
        <span class="device-date">angemeldet {{ device.createdAt | date:'dd.MM.yyyy' }}</span>
        <button type="button" class="danger" (click)="removeDevice(device)">Entfernen</button>
      </li>
    }
  </ul>
</div>
```

- [ ] **Step 3: SCSS anlegen** (schlicht; Farbwerte ggf. an die Variablen/Patterns benachbarter Seiten wie `pages/pet-food/` angleichen)

```scss
.notifications-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 1.5rem;

  h1 {
    margin-bottom: 1rem;
  }

  .ios-hint {
    background: rgba(255, 193, 7, 0.12);
    border: 1px solid rgba(255, 193, 7, 0.4);
    border-radius: 8px;
    padding: 0.75rem 1rem;
    margin-bottom: 1.5rem;
  }

  .status-card {
    border: 1px solid rgba(128, 128, 128, 0.3);
    border-radius: 8px;
    padding: 1rem 1.25rem;
    margin-bottom: 1rem;

    .actions {
      display: flex;
      gap: 0.75rem;
      flex-wrap: wrap;
    }

    button {
      padding: 0.5rem 1rem;
      border-radius: 6px;
      cursor: pointer;

      &:disabled {
        opacity: 0.6;
        cursor: default;
      }
    }
  }

  .message {
    padding: 0.5rem 0.75rem;
    border-radius: 6px;

    &.error {
      background: rgba(220, 53, 69, 0.12);
      color: #c0392b;
    }

    &.info {
      background: rgba(40, 167, 69, 0.12);
      color: #1e7e34;
    }
  }

  .device-list {
    list-style: none;
    padding: 0;

    li {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 0.6rem 0;
      border-bottom: 1px solid rgba(128, 128, 128, 0.2);

      .device-label {
        flex: 1;
        font-weight: 500;

        em {
          font-weight: 400;
          opacity: 0.7;
        }
      }

      .device-date {
        opacity: 0.7;
        font-size: 0.85rem;
      }

      .danger {
        background: transparent;
        border: 1px solid rgba(220, 53, 69, 0.5);
        color: #c0392b;
        border-radius: 6px;
        padding: 0.3rem 0.7rem;
        cursor: pointer;
      }
    }
  }
}
```

- [ ] **Step 4: Route ergänzen** (in `app.routes.ts`, vor der `login`-Route)

```typescript
  {
    path: 'notifications',
    loadComponent: () => import('./pages/notifications/notifications.component').then(m => m.NotificationsComponent),
    canActivate: [authGuard],
    title: 'Benachrichtigungen - Household Manager'
  },
```

- [ ] **Step 5: Navi-Eintrag ergänzen** (in `header.component.ts`, Smart-Home-Children nach `Futtervorrat`)

```typescript
        { path: '/notifications', label: 'Benachrichtigungen' },
```

- [ ] **Step 6: Build prüfen**

Run: `cd frontend && npx ng build --configuration production`
Expected: Build erfolgreich. (Bekannte Falle: nur `dashboard.component.scss` hat ein Budget-Problem — diese Datei wird hier nicht angefasst. Ein Budget-ERROR zu einer anderen Datei wäre eine echte Regression.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app
git commit -m "feat(frontend): Seite Benachrichtigungen mit Web-Push-Anmeldung"
```

---

### Task 10: Gesamt-Verifikation + Doku

**Files:**
- Modify: `CLAUDE.md` (neuer Abschnitt unter „Smart Device Integrations", z. B. nach „Toni-Futtervorrat")

- [ ] **Step 1: Backend-Gesamtlauf**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"; cd backend && mvn test`
Expected: Alle Tests grün **außer** den zwei bekannten, vorbestehenden DB-Fails (`HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest` — „Access denied for user 'root'"). Jeder andere Fail ist eine Regression: stoppen und fixen.

- [ ] **Step 2: CLAUDE.md-Abschnitt ergänzen**

```markdown
### Web-Push-Benachrichtigungen (PWA)
- Standard-Web-Push in die installierte PWA via `nl.martijndwars:web-push` (BouncyCastle transitiv); Zustellung laeuft immer ueber die Push-Dienste von Apple/Google (`web.push.apple.com` etc.), Payload E2E-verschluesselt (`aes128gcm`) — bewusste Erweiterung des LAN-only-Trade-offs. iOS: erst ab 16.4 und **nur in der zum Home-Bildschirm hinzugefuegten PWA**; Berechtigungsanfrage nur aus einer Nutzer-Geste
- **VAPID-Schluesselpaar erzeugt sich beim ersten Zugriff selbst** und liegt in `application_settings` (Kategorie `PUSH_VAPID`) — bewusst keine Env-Variable, kein Rollout-Schritt. VAPID-Subject ist `mailto:benedikt.lind@gmail.com` (`MartijnDwarsWebPushClient`)
- Tabelle `push_subscription` (ein Geraet = eine Zeile, Upsert per `endpoint`, `user_id` mit `ON DELETE CASCADE`); antwortet der Push-Dienst 404/410, wird die Zeile geloescht (iOS laesst Subscriptions verfallen — Selbstbereinigung). `PushNotificationService` wirft nie (Muster Telegram); alle Library-Spezifika stecken hinter dem Interface `WebPushClient`
- Flow-Node `push-send` (analog `telegram-send`): `message` Pflicht, `title` optional (Default „Household Manager"), `userId` optional (leer = alle Geraete); Platzhalter `{entityId}`/`{newState}`/`{oldState}`. Klick auf die Benachrichtigung oeffnet das Dashboard (ngsw-Notification-Schema, `onActionClick` -> `openWindow`)
- API `/api/v1/push`: `GET /vapid-public-key`, `GET/POST /subscriptions`, `DELETE /subscriptions/{id}`, `POST /test`. Lesen KIOSK (generische `GET /v1/**`-Regel), Schreiben MEMBER (`anyRequest`) — bewusst keine eigene Security-Zeile (`SecurityRulesTest` haelt beide Richtungen fest). User-Aufloesung via `CurrentUserService` (Service-Token -> `IllegalStateException` -> 400). Audit: `push.subscribe`/`push.unsubscribe`
- Frontend: Seite „Benachrichtigungen" (`pages/notifications/`, Route `/notifications`, Navi unter Smart Home) mit Aktivieren-Button, Geraeteliste und Testnachricht; `services/push.service.ts` um `SwPush`. Der vorhandene `ngsw-worker.js` zeigt Nachrichten selbst an — kein eigener Service-Worker-Code. Auf dem KIOSK-Wandtablet (Android-WebView) zeigt die Seite „nicht unterstuetzt"
- **Nach dem Prod-Deploy:** bestehende aktive Telegram-Flows via flow-mcp um einen parallelen `push-send`-Zweig ergaenzen (vorher kennt `flow_deploy` den Node-Typ nicht). Voraussetzung fuers iPhone: PWA-/HTTPS-Rollout (ca.crt + :4443) und PWA-Installation
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Web-Push-Abschnitt in CLAUDE.md"
```

---

## Nach dem Prod-Deploy (separater Schritt, nicht Teil dieses Plans)

1. Backend deployen (Liquibase legt `push_subscription` an; VAPID-Keys entstehen beim ersten Zugriff).
2. Auf dem iPhone: PWA installiert? (Voraussetzung: PWA-/HTTPS-Rollout mit ca.crt). Seite „Benachrichtigungen" → aktivieren → Testnachricht.
3. Via flow-mcp (`flow_list` → betroffene Flows lesen): jedem aktiven Telegram-Flow einen `push-send`-Node parallel zum `telegram-send` hinzufügen (gleiche Message), `flow_update` → `flow_deploy` → `flow_set_enabled`. **Nicht vorher versuchen** — die Validierung kennt `push-send` erst nach dem Deploy.
