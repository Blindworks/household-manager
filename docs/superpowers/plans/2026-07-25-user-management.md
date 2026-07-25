# Usermanagement — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Login mit drei festen Rollen (ADMIN/MEMBER/KIOSK), Service-Tokens für Maschinen-Clients und ein Audit-Log — gemäß Spec `docs/superpowers/specs/2026-07-25-user-management-design.md`.

**Architecture:** Spring Security mit Server-Sessions (HttpOnly-Cookie) + Remember-Me-Cookie (überlebt Backend-Neustarts), eigener `X-API-Token`-Filter für Maschinen-Clients, URL-basierte Rollenregeln mit Role-Hierarchy (ADMIN > MEMBER > KIOSK). Angular bekommt Login-Seite, 401-Interceptor, Guards und drei Admin-Seiten. Sidecars/Tablet/MCP-Server senden den Token als Header.

**Tech Stack:** Spring Boot 3.4.1 / Spring Security 6.4, Liquibase, Lombok, Angular 19 (standalone, Signals), OkHttp (Tablet), httpx (blink-vision), fetch (flow-mcp-server).

---

## Umgebungs-Hinweise (vor Beginn lesen)

- **Backend bauen/testen:** JAVA_HOME muss auf JDK 21 zeigen (Default der Maschine ist JDK 17):
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
  cd backend; mvn test "-Dtest=<TestKlasse>"
  ```
- **Bekannte Test-Baseline:** `HouseholdManagerApplicationTests.contextLoads` schlägt lokal ohne erreichbare DB fehl (by design). Frontend: 4 vorbestehende Fails (Header/App/Hero) + gelegentlicher SmartDeviceList-Karma-Flake. Frontend-Tests: `cd frontend; npm test -- --watch=false --browsers=ChromeHeadless`.
- **Konventionen:** Alle JPA-Repositories in `com.household.manager.repository` (JpaConfig scannt nur dort). Controller-Pfade OHNE `/api`-Präfix (Kontextpfad `server.servlet.context-path=/api`). Frontend-Services nutzen relative URLs `/api/v1/...`.
- Neue Backend-Pakete: `com.household.manager.security` (Auth) und `com.household.manager.audit` (Audit).

---

### Task 1: Spring-Security-Dependencies

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: Dependencies ergänzen**

In `backend/pom.xml` nach dem `spring-boot-starter-validation`-Block einfügen:

```xml
        <!-- Spring Security - Benutzerverwaltung, Rollen, Service-Tokens -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
```

Und bei den Test-Dependencies (nach `spring-boot-starter-test`):

```xml
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Kompilieren**

Run: `cd backend; mvn -q compile`
Expected: BUILD SUCCESS

**Achtung:** Ab jetzt ist das Backend „zu“ (Security-Default: alles 401). Das ist okay — die folgenden Tasks bauen die Konfiguration auf; der lokale Dev-Betrieb funktioniert erst nach Task 8 wieder.

- [ ] **Step 3: Commit**

```bash
git add backend/pom.xml
git commit -m "feat(auth): Spring-Security-Dependencies"
```

---

### Task 2: Liquibase-Changeset für app_user, service_token, audit_log

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260725-0041-create-user-management-tables.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (am Ende der includes)
- Test: `backend/src/test/java/com/household/manager/db/ChangelogParseTest.java` (bestehend)

- [ ] **Step 1: Changeset schreiben**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260725-0041" author="household-manager">
        <comment>Benutzerverwaltung: Nutzerkonten, Service-Tokens, Audit-Log.</comment>

        <createTable tableName="app_user">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="username" type="VARCHAR(100)">
                <constraints nullable="false" unique="true" uniqueConstraintName="uq_app_user_username"/>
            </column>
            <column name="display_name" type="VARCHAR(200)">
                <constraints nullable="false"/>
            </column>
            <column name="password_hash" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="role" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createTable tableName="service_token">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(100)">
                <constraints nullable="false" unique="true" uniqueConstraintName="uq_service_token_name"/>
            </column>
            <column name="token_hash" type="VARCHAR(64)">
                <constraints nullable="false" unique="true" uniqueConstraintName="uq_service_token_hash"/>
            </column>
            <column name="role" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="last_used_at" type="TIMESTAMP"/>
        </createTable>

        <createTable tableName="audit_log">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="timestamp" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="actor_type" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="actor" type="VARCHAR(200)">
                <constraints nullable="false"/>
            </column>
            <column name="action" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="detail" type="VARCHAR(500)"/>
        </createTable>

        <createIndex indexName="idx_audit_log_timestamp" tableName="audit_log">
            <column name="timestamp"/>
        </createIndex>
        <createIndex indexName="idx_audit_log_actor" tableName="audit_log">
            <column name="actor"/>
        </createIndex>

        <rollback>
            <dropTable tableName="audit_log"/>
            <dropTable tableName="service_token"/>
            <dropTable tableName="app_user"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Include in Master-Changelog** (ans Ende, vor `</databaseChangeLog>`):

```xml
    <!-- Benutzerverwaltung -->
    <include file="db/changelog/changes/20260725-0041-create-user-management-tables.xml"/>
```

- [ ] **Step 3: Parse-Test laufen lassen**

Run: `cd backend; mvn test "-Dtest=ChangelogParseTest"`
Expected: PASS (2 Tests)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog
git commit -m "feat(auth): Tabellen app_user, service_token, audit_log"
```

---

### Task 3: Entities und Repositories

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/UserRole.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/AppUser.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/ServiceToken.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/AuditActorType.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/AuditLog.java`
- Create: `backend/src/main/java/com/household/manager/repository/AppUserRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/ServiceTokenRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/AuditLogRepository.java`

- [ ] **Step 1: Enums**

```java
package com.household.manager.model.entity;

/** Feste Rollen; Hierarchie ADMIN > MEMBER > KIOSK wird in SecurityConfig definiert. */
public enum UserRole {
    ADMIN,
    MEMBER,
    KIOSK
}
```

```java
package com.household.manager.model.entity;

/** Wer eine auditierte Aktion ausgeloest hat. */
public enum AuditActorType {
    USER,
    SERVICE,
    SYSTEM,
    TELEGRAM
}
```

- [ ] **Step 2: Entities** (Muster wie `VisionPerson`: `@PrePersist`/`@PreUpdate` für Timestamps)

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Nutzerkonto eines Haushaltsmitglieds bzw. Geraetekontos (Tablet). */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    /** Deaktivierte Nutzer verlieren sofort den Zugang (DisabledUserSessionFilter). */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** API-Token eines Maschinen-Clients; gespeichert wird nur der SHA-256-Hash. */
@Entity
@Table(name = "service_token")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Ein Eintrag im Audit-Log (wer hat wann was ausgeloest). */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private AuditActorType actorType;

    @Column(name = "actor", nullable = false, length = 200)
    private String actor;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "detail", length = 500)
    private String detail;

    @PrePersist
    void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
```

- [ ] **Step 3: Repositories**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

```java
package com.household.manager.repository;

import com.household.manager.model.entity.ServiceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceTokenRepository extends JpaRepository<ServiceToken, Long> {
    Optional<ServiceToken> findByTokenHashAndEnabledTrue(String tokenHash);
    boolean existsByName(String name);
}
```

```java
package com.household.manager.repository;

import com.household.manager.model.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByOrderByTimestampDesc(Pageable pageable);
    List<AuditLog> findByActorOrderByTimestampDesc(String actor, Pageable pageable);
}
```

- [ ] **Step 4: Kompilieren + Commit**

Run: `cd backend; mvn -q compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/com/household/manager/model/entity backend/src/main/java/com/household/manager/repository
git commit -m "feat(auth): Entities und Repositories fuer Nutzer, Tokens, Audit"
```

---

### Task 4: TokenHasher (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/TokenHasher.java`
- Test: `backend/src/test/java/com/household/manager/security/TokenHasherTest.java`

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    @Test
    void liefertDeterministischenSha256HexHash() {
        // SHA-256("abc") ist ein bekannter Testvektor
        assertThat(TokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void unterschiedlicheEingabenLiefernUnterschiedlicheHashes() {
        assertThat(TokenHasher.sha256Hex("token-a")).isNotEqualTo(TokenHasher.sha256Hex("token-b"));
    }
}
```

- [ ] **Step 2: Test rot sehen**

Run: `cd backend; mvn test "-Dtest=TokenHasherTest"`
Expected: FAIL (Klasse existiert nicht)

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256-Hex-Hashing fuer Service-Tokens (nur der Hash liegt in der DB). */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", ex);
        }
    }
}
```

- [ ] **Step 4: Test grün + Commit**

Run: `cd backend; mvn test "-Dtest=TokenHasherTest"`
Expected: PASS

```bash
git add backend/src/main/java/com/household/manager/security backend/src/test/java/com/household/manager/security
git commit -m "feat(auth): TokenHasher"
```

---

### Task 5: AppUserPrincipal + AppUserDetailsService (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/AppUserPrincipal.java`
- Create: `backend/src/main/java/com/household/manager/security/AppUserDetailsService.java`
- Test: `backend/src/test/java/com/household/manager/security/AppUserDetailsServiceTest.java`

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppUserDetailsServiceTest {

    @Mock
    private AppUserRepository repository;

    @InjectMocks
    private AppUserDetailsService service;

    @Test
    void mapptNutzerAufPrincipalMitRollenPrefix() {
        when(repository.findByUsername("bene")).thenReturn(Optional.of(AppUser.builder()
                .username("bene").displayName("Benedikt").passwordHash("hash")
                .role(UserRole.ADMIN).enabled(true).build()));

        UserDetails details = service.loadUserByUsername("bene");

        assertThat(details.getUsername()).isEqualTo("bene");
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(details.isEnabled()).isTrue();
        assertThat(((AppUserPrincipal) details).getDisplayName()).isEqualTo("Benedikt");
    }

    @Test
    void unbekannterNutzerWirftUsernameNotFound() {
        when(repository.findByUsername("nix")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nix"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void deaktivierterNutzerIstDisabled() {
        when(repository.findByUsername("alt")).thenReturn(Optional.of(AppUser.builder()
                .username("alt").displayName("Alt").passwordHash("hash")
                .role(UserRole.MEMBER).enabled(false).build()));

        assertThat(service.loadUserByUsername("alt").isEnabled()).isFalse();
    }
}
```

- [ ] **Step 2: Test rot sehen**

Run: `cd backend; mvn test "-Dtest=AppUserDetailsServiceTest"`
Expected: FAIL (Klassen existieren nicht)

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import lombok.Getter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/** UserDetails mit Anzeigename — Grundlage fuer /v1/auth/me. */
@Getter
public class AppUserPrincipal extends User {

    private final String displayName;

    public AppUserPrincipal(AppUser user) {
        super(user.getUsername(), user.getPasswordHash(), user.isEnabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.displayName = user.getDisplayName();
    }
}
```

```java
package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Laedt Nutzerkonten fuer Login und Remember-Me. */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository repository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unbekannter Benutzer: " + username));
        return new AppUserPrincipal(user);
    }
}
```

- [ ] **Step 4: Test grün + Commit**

Run: `cd backend; mvn test "-Dtest=AppUserDetailsServiceTest"`
Expected: PASS

```bash
git add backend/src/main/java/com/household/manager/security backend/src/test/java/com/household/manager/security
git commit -m "feat(auth): AppUserDetailsService mit AppUserPrincipal"
```

---

### Task 6: ServiceTokenService (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/ServiceTokenService.java`
- Test: `backend/src/test/java/com/household/manager/security/ServiceTokenServiceTest.java`

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.security;

import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.ServiceTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTokenServiceTest {

    @Mock
    private ServiceTokenRepository repository;

    @InjectMocks
    private ServiceTokenService service;

    @Test
    void createLiefertKlartextGenauEinmalUndSpeichertNurDenHash() {
        when(repository.existsByName("tablet")).thenReturn(false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceTokenService.CreatedToken created = service.create("tablet", UserRole.KIOSK);

        assertThat(created.plaintext()).startsWith("hm_").hasSizeGreaterThan(20);
        assertThat(created.token().getTokenHash())
                .isEqualTo(TokenHasher.sha256Hex(created.plaintext()))
                .isNotEqualTo(created.plaintext());
        assertThat(created.token().getRole()).isEqualTo(UserRole.KIOSK);
    }

    @Test
    void createMitVergebenemNamenWirftDuplicate() {
        when(repository.existsByName("tablet")).thenReturn(true);

        assertThatThrownBy(() -> service.create("tablet", UserRole.KIOSK))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void authenticateFindetTokenUeberHashUndAktualisiertLastUsed() {
        ServiceToken token = ServiceToken.builder().name("tablet")
                .tokenHash(TokenHasher.sha256Hex("hm_geheim")).role(UserRole.KIOSK).enabled(true).build();
        when(repository.findByTokenHashAndEnabledTrue(TokenHasher.sha256Hex("hm_geheim")))
                .thenReturn(Optional.of(token));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<ServiceToken> result = service.authenticate("hm_geheim");

        assertThat(result).isPresent();
        assertThat(result.get().getLastUsedAt()).isNotNull();
        verify(repository).save(token);
    }

    @Test
    void authenticateMitUnbekanntemTokenLiefertEmpty() {
        when(repository.findByTokenHashAndEnabledTrue(any())).thenReturn(Optional.empty());

        assertThat(service.authenticate("falsch")).isEmpty();
    }
}
```

- [ ] **Step 2: Test rot sehen**

Run: `cd backend; mvn test "-Dtest=ServiceTokenServiceTest"`
Expected: FAIL

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.security;

import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.ServiceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Service-Tokens der Maschinen-Clients. Der Klartext wird nur bei der
 * Erstellung zurueckgegeben; danach existiert nur noch der SHA-256-Hash.
 */
@Service
@RequiredArgsConstructor
public class ServiceTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceTokenRepository repository;

    /** Ergebnis der Token-Erstellung: Entity + einmalig sichtbarer Klartext. */
    public record CreatedToken(ServiceToken token, String plaintext) {
    }

    @Transactional
    public CreatedToken create(String name, UserRole role) {
        if (repository.existsByName(name)) {
            throw new DuplicateEntityException("Token-Name bereits vergeben: " + name);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String plaintext = "hm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ServiceToken token = repository.save(ServiceToken.builder()
                .name(name)
                .tokenHash(TokenHasher.sha256Hex(plaintext))
                .role(role)
                .build());
        return new CreatedToken(token, plaintext);
    }

    /** Prueft einen Klartext-Token; bei Erfolg wird last_used_at fortgeschrieben. */
    @Transactional
    public Optional<ServiceToken> authenticate(String rawToken) {
        return repository.findByTokenHashAndEnabledTrue(TokenHasher.sha256Hex(rawToken))
                .map(token -> {
                    token.setLastUsedAt(LocalDateTime.now());
                    return repository.save(token);
                });
    }

    public List<ServiceToken> list() {
        return repository.findAll();
    }

    /** Widerruf = deaktivieren; die Zeile bleibt fuer Audit-Bezuege erhalten. */
    @Transactional
    public ServiceToken revoke(Long id) {
        ServiceToken token = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service-Token nicht gefunden: " + id));
        token.setEnabled(false);
        return repository.save(token);
    }
}
```

- [ ] **Step 4: Test grün + Commit**

Run: `cd backend; mvn test "-Dtest=ServiceTokenServiceTest"`
Expected: PASS

```bash
git add backend/src/main/java/com/household/manager/security backend/src/test/java/com/household/manager/security
git commit -m "feat(auth): ServiceTokenService (Erstellung, Pruefung, Widerruf)"
```

---

### Task 7: Audit-Baustein (Actor, Context, Resolver, AuditService) (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/audit/AuditActor.java`
- Create: `backend/src/main/java/com/household/manager/audit/AuditActorContext.java`
- Create: `backend/src/main/java/com/household/manager/audit/AuditActorResolver.java`
- Create: `backend/src/main/java/com/household/manager/audit/AuditService.java`
- Test: `backend/src/test/java/com/household/manager/audit/AuditActorResolverTest.java`
- Test: `backend/src/test/java/com/household/manager/audit/AuditServiceTest.java`

- [ ] **Step 1: Failing Tests**

```java
package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActorResolverTest {

    private final AuditActorResolver resolver = new AuditActorResolver();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        AuditActorContext.clear();
    }

    @Test
    void ohneAuthentifizierungIstDerAktorSystem() {
        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.SYSTEM, "system"));
    }

    @Test
    void anonymeAuthentifizierungIstSystem() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(resolver.currentActor().type()).isEqualTo(AuditActorType.SYSTEM);
    }

    @Test
    void nutzerSessionWirdAlsUserErkannt() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "bene", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.USER, "bene"));
    }

    @Test
    void serviceTokenWirdUeberDieServiceAuthorityErkannt() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "tablet", null, AuthorityUtils.createAuthorityList("ROLE_KIOSK", "SERVICE")));

        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.SERVICE, "tablet"));
    }

    @Test
    void threadLocalOverrideGewinnt() {
        AuditActorContext.set(AuditActor.telegram(1234L));

        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.TELEGRAM, "TELEGRAM:1234"));
    }
}
```

```java
package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import com.household.manager.model.entity.AuditLog;
import com.household.manager.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository repository;

    @Test
    void schreibtEintragMitAufgeloestemAktor() {
        AuditService service = new AuditService(repository, new AuditActorResolver());

        service.record("switch.toggle", "switch.meross_abc");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(captor.getValue().getAction()).isEqualTo("switch.toggle");
        assertThat(captor.getValue().getDetail()).isEqualTo("switch.meross_abc");
    }

    @Test
    void auditFehlerBrechenDieFachlicheAktionNicht() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB weg"));
        AuditService service = new AuditService(repository, new AuditActorResolver());

        assertThatCode(() -> service.record("nuki.lock", "123")).doesNotThrowAnyException();
    }

    @Test
    void expliziterAktorWirdUebernommen() {
        AuditService service = new AuditService(repository, new AuditActorResolver());

        service.record(AuditActor.user("bene"), "auth.login", null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorType()).isEqualTo(AuditActorType.USER);
        assertThat(captor.getValue().getActor()).isEqualTo("bene");
    }
}
```

- [ ] **Step 2: Tests rot sehen**

Run: `cd backend; mvn test "-Dtest=AuditActorResolverTest+AuditServiceTest"`
Expected: FAIL

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;

/** Aktor eines Audit-Eintrags. */
public record AuditActor(AuditActorType type, String name) {

    public static AuditActor user(String username) {
        return new AuditActor(AuditActorType.USER, username);
    }

    public static AuditActor service(String tokenName) {
        return new AuditActor(AuditActorType.SERVICE, tokenName);
    }

    public static AuditActor system() {
        return new AuditActor(AuditActorType.SYSTEM, "system");
    }

    public static AuditActor telegram(long chatId) {
        return new AuditActor(AuditActorType.TELEGRAM, "TELEGRAM:" + chatId);
    }
}
```

```java
package com.household.manager.audit;

/**
 * ThreadLocal-Override fuer Aktoren ohne SecurityContext (z. B. der
 * Telegram-Bot). Muss im finally-Block wieder geleert werden.
 */
public final class AuditActorContext {

    private static final ThreadLocal<AuditActor> CURRENT = new ThreadLocal<>();

    private AuditActorContext() {
    }

    public static void set(AuditActor actor) {
        CURRENT.set(actor);
    }

    public static AuditActor get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
```

```java
package com.household.manager.audit;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Loest den aktuellen Aktor auf: ThreadLocal-Override > SecurityContext > SYSTEM. */
@Component
public class AuditActorResolver {

    /** Muss mit SecurityConfig.SERVICE_AUTHORITY uebereinstimmen (kein Import — Zykusvermeidung). */
    static final String SERVICE_AUTHORITY = "SERVICE";

    public AuditActor currentActor() {
        AuditActor override = AuditActorContext.get();
        if (override != null) {
            return override;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return AuditActor.system();
        }
        Set<String> authorities = AuthorityUtils.authorityListToSet(auth.getAuthorities());
        if (authorities.contains(SERVICE_AUTHORITY)) {
            return AuditActor.service(auth.getName());
        }
        return AuditActor.user(auth.getName());
    }
}
```

```java
package com.household.manager.audit;

import com.household.manager.model.entity.AuditLog;
import com.household.manager.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Zentrales Audit-Log. record() wirft nie — ein Audit-Fehler darf die
 * fachliche Aktion nicht brechen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository repository;
    private final AuditActorResolver actorResolver;

    public void record(String action, String detail) {
        record(actorResolver.currentActor(), action, detail);
    }

    public void record(AuditActor actor, String action, String detail) {
        try {
            repository.save(AuditLog.builder()
                    .actorType(actor.type())
                    .actor(actor.name())
                    .action(action)
                    .detail(detail)
                    .build());
        } catch (Exception ex) {
            log.warn("Audit-Eintrag fehlgeschlagen ({} / {}): {}", action, detail, ex.getMessage());
        }
    }

    public List<AuditLog> recent(int limit, String actorFilter) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        return StringUtils.hasText(actorFilter)
                ? repository.findByActorOrderByTimestampDesc(actorFilter, page)
                : repository.findByOrderByTimestampDesc(page);
    }
}
```

- [ ] **Step 4: Tests grün + Commit**

Run: `cd backend; mvn test "-Dtest=AuditActorResolverTest+AuditServiceTest"`
Expected: PASS

```bash
git add backend/src/main/java/com/household/manager/audit backend/src/test/java/com/household/manager/audit
git commit -m "feat(audit): AuditService mit Aktor-Aufloesung"
```

---

### Task 8: Security-Konfiguration (Filter, Regeln, CSRF, Remember-Me)

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/ServiceTokenAuthFilter.java`
- Create: `backend/src/main/java/com/household/manager/security/DisabledUserSessionFilter.java`
- Create: `backend/src/main/java/com/household/manager/security/SpaCsrfTokenRequestHandler.java`
- Create: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Modify: `backend/src/main/resources/application.properties`
- Test: `backend/src/test/java/com/household/manager/security/ServiceTokenAuthFilterTest.java`

- [ ] **Step 1: Failing Test für den Token-Filter**

```java
package com.household.manager.security;

import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTokenAuthFilterTest {

    @Mock
    private ServiceTokenService serviceTokenService;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void gueltigerTokenSetztAuthentifizierungMitRolleUndServiceAuthority() throws Exception {
        when(serviceTokenService.authenticate("hm_ok")).thenReturn(Optional.of(
                ServiceToken.builder().name("tablet").role(UserRole.KIOSK).enabled(true).build()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Token", "hm_ok");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ServiceTokenAuthFilter(serviceTokenService).doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("tablet");
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_KIOSK", "SERVICE");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void ungueltigerTokenLaesstDenRequestUnauthentifiziertWeiterlaufen() throws Exception {
        when(serviceTokenService.authenticate("falsch")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Token", "falsch");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ServiceTokenAuthFilter(serviceTokenService).doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void ohneHeaderPassiertNichts() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ServiceTokenAuthFilter(serviceTokenService).doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
```

- [ ] **Step 2: Test rot sehen**

Run: `cd backend; mvn test "-Dtest=ServiceTokenAuthFilterTest"`
Expected: FAIL

- [ ] **Step 3: Filter implementieren**

```java
package com.household.manager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authentifiziert Maschinen-Clients ueber den Header X-API-Token. Ein
 * ungueltiger Token fuehrt nicht zum Abbruch — der Request laeuft
 * unauthentifiziert weiter und scheitert dann an der Autorisierung (401).
 */
@Component
@RequiredArgsConstructor
public class ServiceTokenAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-API-Token";

    private final ServiceTokenService serviceTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawToken = request.getHeader(TOKEN_HEADER);
        if (StringUtils.hasText(rawToken)) {
            serviceTokenService.authenticate(rawToken).ifPresent(token -> {
                List<GrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + token.getRole().name()),
                        new SimpleGrantedAuthority(SecurityConfig.SERVICE_AUTHORITY));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        token.getName(), null, authorities));
                SecurityContextHolder.setContext(context);
            });
        }
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Restliche Security-Klassen implementieren**

`DisabledUserSessionFilter` — setzt „deaktivierte Nutzer verlieren sofort den Zugang“ ohne Session-Registry um (eine DB-Abfrage pro Request mit Nutzer-Session; bei Haushaltsgrößen unkritisch):

```java
package com.household.manager.security;

import com.household.manager.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Entwertet Sessions deaktivierter Nutzer sofort. Ohne diesen Filter bliebe
 * eine bestehende Session bis zu ihrem Ablauf gueltig, weil der
 * UserDetailsService nur beim Login konsultiert wird.
 */
@Component
@RequiredArgsConstructor
public class DisabledUserSessionFilter extends OncePerRequestFilter {

    private final AppUserRepository appUserRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetails user
                && appUserRepository.findByUsername(user.getUsername())
                        .map(u -> !u.isEnabled()).orElse(true)) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        filterChain.doFilter(request, response);
    }
}
```

`SpaCsrfTokenRequestHandler` — das dokumentierte Spring-Security-Muster für SPAs (BREACH-Schutz bei gerenderten Tokens, Klartext-Vergleich für den Angular-Header; `csrfToken.get()` erzwingt das Setzen des Cookies):

```java
package com.household.manager.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/** CSRF-Handler fuer die Angular-SPA (Cookie XSRF-TOKEN / Header X-XSRF-TOKEN). */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken);
        // Deferred-Token aufloesen, damit das XSRF-TOKEN-Cookie bei jeder Antwort gesetzt wird
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
```

`SecurityConfig` — **die autoritative Umsetzung der Rollenmatrix**. Reihenfolge der Regeln ist relevant (erste Übereinstimmung gewinnt):

```java
package com.household.manager.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Zentrale Security-Konfiguration: Session-Login fuer Menschen,
 * X-API-Token fuer Maschinen, Rollenmatrix laut Spec
 * docs/superpowers/specs/2026-07-25-user-management-design.md.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    /** Zusatz-Authority aller Service-Token-Requests (Maschinen-Endpunkte). */
    public static final String SERVICE_AUTHORITY = "SERVICE";

    private final ServiceTokenAuthFilter serviceTokenAuthFilter;
    private final DisabledUserSessionFilter disabledUserSessionFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_MEMBER
                ROLE_MEMBER > ROLE_KIOSK
                """);
    }

    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public TokenBasedRememberMeServices rememberMeServices(
            @Value("${auth.remember-me-key:}") String configuredKey,
            AppUserDetailsService userDetailsService) {
        String key = configuredKey;
        if (!StringUtils.hasText(key)) {
            key = UUID.randomUUID().toString();
            log.warn("auth.remember-me-key ist nicht gesetzt — Remember-Me-Logins "
                    + "ueberleben den naechsten Neustart nicht. REMEMBER_ME_KEY setzen!");
        }
        TokenBasedRememberMeServices services = new TokenBasedRememberMeServices(key, userDetailsService);
        services.setAlwaysRemember(true);
        services.setTokenValiditySeconds((int) Duration.ofDays(90).toSeconds());
        services.setCookieName("HM_REMEMBER");
        return services;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository securityContextRepository,
                                           TokenBasedRememberMeServices rememberMeServices) throws Exception {
        http
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // Token-Requests haben keinen Cookie-Kontext -> kein CSRF-Risiko
                        .ignoringRequestMatchers(
                                request -> request.getHeader(ServiceTokenAuthFilter.TOKEN_HEADER) != null))
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
                .addFilterBefore(serviceTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(disabledUserSessionFilter, SecurityContextHolderFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                                        "Anmeldung erforderlich."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpStatus.FORBIDDEN, "Forbidden",
                                        "Keine Berechtigung fuer diese Aktion.")))
                .authorizeHttpRequests(auth -> auth
                        // Login/Logout und Actuator-Health bleiben offen
                        .requestMatchers("/v1/auth/login", "/v1/auth/logout").permitAll()
                        .requestMatchers("/management/**").permitAll()
                        // Maschinen-Endpunkte: beliebiger gueltiger Service-Token
                        .requestMatchers(HttpMethod.POST,
                                "/v1/vision/recognitions", "/v1/vision/heartbeat").hasAuthority(SERVICE_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/v1/vision/embeddings").hasAuthority(SERVICE_AUTHORITY)
                        .requestMatchers("/v1/tablet-presence/**").hasAuthority(SERVICE_AUTHORITY)
                        // Admin-Bereiche (inkl. bestehender /v1/admin/*-Polling-Controller)
                        .requestMatchers("/v1/flows/**", "/v1/admin/**", "/v1/vision/**",
                                "/v1/alexa/auth/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/utility-prices/**").hasRole("KIOSK")
                        .requestMatchers("/v1/utility-prices/**").hasRole("ADMIN")
                        // Finanzdaten sind privat — nicht fuers Kiosk-Tablet
                        .requestMatchers("/v1/finance/**").hasRole("MEMBER")
                        // KIOSK-Whitelist: Dashboard lesen + Schalter/Modi/Nuki
                        // (LOCK-only fuer KIOSK erzwingt der NukiController)
                        .requestMatchers(HttpMethod.POST, "/v1/switches/*/toggle",
                                "/v1/modes/*/toggle", "/v1/nuki/locks/*/actions").hasRole("KIOSK")
                        .requestMatchers(HttpMethod.GET, "/v1/**", "/energy/**", "/devices/**",
                                "/kasa/**", "/tapo/**", "/meross/**", "/shelly/**").hasRole("KIOSK")
                        // Alles Uebrige (Geraete schalten, Kalender/Zaehler pflegen, Ansagen ...)
                        .anyRequest().hasRole("MEMBER"));
        return http.build();
    }

    private void writeError(HttpServletResponse response, HttpStatus status,
                            String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(error)
                .message(message)
                .build());
    }
}
```

**Hinweis:** `ErrorResponse` nutzt Lombok `@Builder` — prüfen, dass die Felder `timestamp/status/error/message` existieren (tun sie, siehe `GlobalExceptionHandler`). Falls der ObjectMapper `LocalDateTime` nicht serialisieren kann, ist `jackson-datatype-jsr310` über `spring-boot-starter-web` bereits an Bord.

- [ ] **Step 5: Properties ergänzen** (`application.properties`, ans Ende):

```properties
# Benutzerverwaltung
# Initialpasswort des ersten Admins; leer -> Zufallspasswort einmalig im Log
auth.initial-admin-password=${INITIAL_ADMIN_PASSWORD:}
# Fester Schluessel, damit Remember-Me-Cookies Backend-Neustarts ueberleben
auth.remember-me-key=${REMEMBER_ME_KEY:}
server.servlet.session.timeout=12h
```

- [ ] **Step 6: Tests + Kompilieren**

Run: `cd backend; mvn test "-Dtest=ServiceTokenAuthFilterTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/security backend/src/test/java/com/household/manager/security backend/src/main/resources/application.properties
git commit -m "feat(auth): SecurityFilterChain mit Rollenmatrix, Token-Filter, CSRF, Remember-Me"
```

---

### Task 9: AuthController (Login/Logout/Me)

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/household/manager/security/dto/CurrentUserResponse.java`
- Create: `backend/src/main/java/com/household/manager/security/AuthController.java`
- Modify: `backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/household/manager/security/CurrentUserResponseTest.java`

- [ ] **Step 1: Failing Test für das Response-Mapping**

```java
package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.security.dto.CurrentUserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserResponseTest {

    @Test
    void nutzerSessionLiefertAnzeigenameUndRolle() {
        AppUserPrincipal principal = new AppUserPrincipal(AppUser.builder()
                .username("bene").displayName("Benedikt").passwordHash("x")
                .role(UserRole.ADMIN).enabled(true).build());
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        CurrentUserResponse response = CurrentUserResponse.from(auth);

        assertThat(response.username()).isEqualTo("bene");
        assertThat(response.displayName()).isEqualTo("Benedikt");
        assertThat(response.role()).isEqualTo("ADMIN");
    }

    @Test
    void serviceTokenLiefertTokenNamenAlsAnzeigename() {
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                "tablet", null, AuthorityUtils.createAuthorityList("ROLE_KIOSK", "SERVICE"));

        CurrentUserResponse response = CurrentUserResponse.from(auth);

        assertThat(response.username()).isEqualTo("tablet");
        assertThat(response.displayName()).isEqualTo("tablet");
        assertThat(response.role()).isEqualTo("KIOSK");
    }
}
```

- [ ] **Step 2: Test rot sehen**

Run: `cd backend; mvn test "-Dtest=CurrentUserResponseTest"`
Expected: FAIL

- [ ] **Step 3: DTOs + Controller implementieren**

```java
package com.household.manager.security.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}
```

```java
package com.household.manager.security.dto;

import com.household.manager.security.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Der angemeldete Aktor, wie ihn das Frontend braucht. */
public record CurrentUserResponse(String username, String displayName, String role) {

    public static CurrentUserResponse from(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse("KIOSK");
        String displayName = authentication.getPrincipal() instanceof AppUserPrincipal principal
                ? principal.getDisplayName()
                : authentication.getName();
        return new CurrentUserResponse(authentication.getName(), displayName, role);
    }
}
```

```java
package com.household.manager.security;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditService;
import com.household.manager.security.dto.CurrentUserResponse;
import com.household.manager.security.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

/** Session-Login der Browser-Clients (Frontend + Tablet-WebView). */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final TokenBasedRememberMeServices rememberMeServices;
    private final AuditService auditService;

    @PostMapping("/login")
    public CurrentUserResponse login(@Valid @RequestBody LoginRequest loginRequest,
                                     HttpServletRequest request, HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            loginRequest.username(), loginRequest.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            rememberMeServices.loginSuccess(request, response, authentication);
            auditService.record(AuditActor.user(authentication.getName()), "auth.login", null);
            return CurrentUserResponse.from(authentication);
        } catch (AuthenticationException ex) {
            auditService.record(AuditActor.user(loginRequest.username()), "auth.login-failed", null);
            throw ex;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && !(auth instanceof AnonymousAuthenticationToken)) {
            auditService.record("auth.logout", null);
        }
        rememberMeServices.logout(request, response, auth);
        new SecurityContextLogoutHandler().logout(request, response, auth);
    }

    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        return CurrentUserResponse.from(authentication);
    }
}
```

- [ ] **Step 4: GlobalExceptionHandler ergänzen** — **wichtig**, sonst macht der Catch-all aus 401/403 einen 500. VOR dem `handleGlobalException`-Handler einfügen:

```java
    /**
     * AccessDeniedException aus Methoden-/Controller-Pruefungen (z. B. Nuki-KIOSK-Regel).
     * Ohne diesen Handler wuerde der Catch-all unten daraus einen 500 machen.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            org.springframework.security.access.AccessDeniedException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("Keine Berechtigung fuer diese Aktion.")
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    /**
     * Fehlgeschlagene Logins (AuthController wirft die AuthenticationException weiter).
     * Bewusst unspezifische Meldung — kein User-Enumeration-Leak.
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            org.springframework.security.core.AuthenticationException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message("Benutzername oder Passwort falsch.")
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
```

- [ ] **Step 5: Tests grün + Commit**

Run: `cd backend; mvn test "-Dtest=CurrentUserResponseTest"`
Expected: PASS

```bash
git add backend/src/main/java/com/household/manager/security backend/src/main/java/com/household/manager/exception backend/src/test/java/com/household/manager/security
git commit -m "feat(auth): AuthController mit Login, Logout, Me"
```

---

### Task 10: AppUserService + Admin-Bootstrap (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/AppUserService.java`
- Create: `backend/src/main/java/com/household/manager/security/AdminUserInitializer.java`
- Test: `backend/src/test/java/com/household/manager/security/AppUserServiceTest.java`

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.security;

import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

    @Mock
    private AppUserRepository repository;

    private AppUserService service;

    @BeforeEach
    void setUp() {
        service = new AppUserService(repository, new BCryptPasswordEncoder());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createHashtDasPasswortMitBcrypt() {
        when(repository.existsByUsername("mia")).thenReturn(false);

        AppUser user = service.create("mia", "Mia", "geheim123", UserRole.MEMBER);

        assertThat(user.getPasswordHash()).startsWith("$2").isNotEqualTo("geheim123");
        assertThat(new BCryptPasswordEncoder().matches("geheim123", user.getPasswordHash())).isTrue();
    }

    @Test
    void createMitVergebenemNamenWirftDuplicate() {
        when(repository.existsByUsername("mia")).thenReturn(true);

        assertThatThrownBy(() -> service.create("mia", "Mia", "x", UserRole.MEMBER))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void letzterAktiverAdminKannNichtDeaktiviertOderDegradiertWerden() {
        AppUser admin = AppUser.builder().id(1L).username("admin").displayName("Admin")
                .passwordHash("x").role(UserRole.ADMIN).enabled(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(admin));
        when(repository.findAll()).thenReturn(List.of(admin));

        assertThatThrownBy(() -> service.update(1L, "Admin", UserRole.MEMBER, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.update(1L, "Admin", UserRole.ADMIN, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bootstrapLegtAdminNurAufLeererTabelleAn() {
        when(repository.count()).thenReturn(0L);

        Optional<String> generated = service.bootstrapAdmin("");

        assertThat(generated).isPresent();
        assertThat(generated.get()).hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    void bootstrapMitKonfiguriertemPasswortLiefertKeinGeneriertes() {
        when(repository.count()).thenReturn(0L);

        assertThat(service.bootstrapAdmin("konfiguriert")).isEmpty();
    }

    @Test
    void bootstrapTutNichtsWennNutzerExistieren() {
        when(repository.count()).thenReturn(3L);

        assertThat(service.bootstrapAdmin("egal")).isEmpty();
    }
}
```

- [ ] **Step 2: Test rot sehen**

Run: `cd backend; mvn test "-Dtest=AppUserServiceTest"`
Expected: FAIL

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.security;

import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Verwaltung der Nutzerkonten (Admin-API + Bootstrap des ersten Admins). */
@Service
@RequiredArgsConstructor
public class AppUserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public List<AppUser> list() {
        return repository.findAll();
    }

    @Transactional
    public AppUser create(String username, String displayName, String password, UserRole role) {
        if (repository.existsByUsername(username)) {
            throw new DuplicateEntityException("Benutzername bereits vergeben: " + username);
        }
        return repository.save(AppUser.builder()
                .username(username)
                .displayName(displayName)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .build());
    }

    @Transactional
    public AppUser update(Long id, String displayName, UserRole role, boolean enabled) {
        AppUser user = getOrThrow(id);
        boolean losesAdmin = user.getRole() == UserRole.ADMIN && (role != UserRole.ADMIN || !enabled);
        if (losesAdmin && countOtherActiveAdmins(user) == 0) {
            throw new IllegalStateException("Der letzte aktive Admin kann nicht deaktiviert oder degradiert werden.");
        }
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setEnabled(enabled);
        return repository.save(user);
    }

    @Transactional
    public void setPassword(Long id, String password) {
        AppUser user = getOrThrow(id);
        user.setPasswordHash(passwordEncoder.encode(password));
        repository.save(user);
    }

    /**
     * Legt beim ersten Start einen Admin an. Liefert das generierte
     * Zufallspasswort, falls keines konfiguriert war (fuer die Log-Ausgabe).
     */
    @Transactional
    public Optional<String> bootstrapAdmin(String configuredPassword) {
        if (repository.count() > 0) {
            return Optional.empty();
        }
        boolean generate = !StringUtils.hasText(configuredPassword);
        String password = generate ? generatePassword() : configuredPassword;
        repository.save(AppUser.builder()
                .username("admin")
                .displayName("Administrator")
                .passwordHash(passwordEncoder.encode(password))
                .role(UserRole.ADMIN)
                .build());
        return generate ? Optional.of(password) : Optional.empty();
    }

    private AppUser getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Nutzer nicht gefunden: " + id));
    }

    private long countOtherActiveAdmins(AppUser excluded) {
        return repository.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN && u.isEnabled()
                        && !u.getId().equals(excluded.getId()))
                .count();
    }

    private String generatePassword() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

```java
package com.household.manager.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Legt beim allerersten Start den Admin an (INITIAL_ADMIN_PASSWORD oder Zufall). */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserInitializer implements ApplicationRunner {

    private final AppUserService appUserService;

    @Value("${auth.initial-admin-password:}")
    private String initialPassword;

    @Override
    public void run(ApplicationArguments args) {
        appUserService.bootstrapAdmin(initialPassword).ifPresent(generated ->
                log.warn("Initialer Admin 'admin' angelegt. Einmal-Passwort: {} — bitte sofort aendern!",
                        generated));
    }
}
```

- [ ] **Step 4: Test grün + Commit**

Run: `cd backend; mvn test "-Dtest=AppUserServiceTest"`
Expected: PASS

```bash
git add backend/src/main/java/com/household/manager/security backend/src/test/java/com/household/manager/security
git commit -m "feat(auth): AppUserService und Admin-Bootstrap"
```

---

### Task 11: Nuki-KIOSK-Regel (nur Verriegeln) (TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/nuki/NukiController.java`
- Test: `backend/src/test/java/com/household/manager/nuki/NukiControllerKioskRuleTest.java` (neu)

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiActionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NukiControllerKioskRuleTest {

    @Mock
    private NukiLockService lockService;

    private Authentication authWithRoles(String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(
                "wer", null, AuthorityUtils.createAuthorityList(roles));
    }

    @Test
    void kioskDarfVerriegeln() {
        NukiController controller = new NukiController(lockService);

        assertThatCode(() -> controller.executeAction(1L,
                new NukiActionRequest(NukiLockAction.LOCK), authWithRoles("ROLE_KIOSK")))
                .doesNotThrowAnyException();
        verify(lockService).executeAction(1L, NukiLockAction.LOCK);
    }

    @Test
    void kioskDarfNichtEntsperren() {
        NukiController controller = new NukiController(lockService);

        assertThatThrownBy(() -> controller.executeAction(1L,
                new NukiActionRequest(NukiLockAction.UNLATCH), authWithRoles("ROLE_KIOSK")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(lockService);
    }

    @Test
    void memberDarfEntsperren() {
        NukiController controller = new NukiController(lockService);

        assertThatCode(() -> controller.executeAction(1L,
                new NukiActionRequest(NukiLockAction.UNLOCK), authWithRoles("ROLE_MEMBER")))
                .doesNotThrowAnyException();
        verify(lockService).executeAction(1L, NukiLockAction.UNLOCK);
    }
}
```

- [ ] **Step 2: Test rot sehen** (Compile-Fehler: Signatur hat noch kein `Authentication`)

Run: `cd backend; mvn test "-Dtest=NukiControllerKioskRuleTest"`
Expected: FAIL

- [ ] **Step 3: Controller anpassen** — `executeAction` erhält `Authentication` und prüft die Rolle. Die Aktion LOCK/UNLOCK/UNLATCH steckt im Request-Body, deshalb kann die URL-Regel in SecurityConfig hier nicht differenzieren:

```java
    @PostMapping("/locks/{smartlockId}/actions")
    public ResponseEntity<Void> executeAction(@PathVariable long smartlockId,
                                              @Valid @RequestBody NukiActionRequest request,
                                              Authentication authentication) {
        if (request.action() != NukiLockAction.LOCK && lacksMemberRole(authentication)) {
            throw new AccessDeniedException("Diese Rolle darf nur verriegeln.");
        }
        lockService.executeAction(smartlockId, request.action());
        return ResponseEntity.noContent().build();
    }

    private static boolean lacksMemberRole(Authentication authentication) {
        Set<String> roles = AuthorityUtils.authorityListToSet(authentication.getAuthorities());
        return !roles.contains("ROLE_MEMBER") && !roles.contains("ROLE_ADMIN");
    }
```

Neue Imports: `org.springframework.security.access.AccessDeniedException`, `org.springframework.security.core.Authentication`, `org.springframework.security.core.authority.AuthorityUtils`, `java.util.Set`.

- [ ] **Step 4: Test grün + Commit**

Run: `cd backend; mvn test "-Dtest=NukiControllerKioskRuleTest"`
Expected: PASS

```bash
git add backend/src/main/java/com/household/manager/nuki backend/src/test/java/com/household/manager/nuki
git commit -m "feat(auth): KIOSK darf Nuki nur verriegeln"
```

---

### Task 12: Admin-REST-API (Nutzer, Tokens, Audit-Log)

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/dto/UserAdminDtos.java`
- Create: `backend/src/main/java/com/household/manager/security/UserAdminController.java`
- Create: `backend/src/main/java/com/household/manager/security/ServiceTokenAdminController.java`
- Create: `backend/src/main/java/com/household/manager/audit/AuditLogController.java`

- [ ] **Step 1: DTOs**

```java
package com.household.manager.security.dto;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** DTOs der Admin-Endpunkte fuer Nutzer- und Token-Verwaltung. */
public final class UserAdminDtos {

    private UserAdminDtos() {
    }

    public record UserResponse(Long id, String username, String displayName, UserRole role,
                               boolean enabled, LocalDateTime createdAt) {
        public static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                    user.getRole(), user.isEnabled(), user.getCreatedAt());
        }
    }

    public record CreateUserRequest(@NotBlank @Size(max = 100) String username,
                                    @NotBlank @Size(max = 200) String displayName,
                                    @NotBlank @Size(min = 8) String password,
                                    @NotNull UserRole role) {
    }

    public record UpdateUserRequest(@NotBlank @Size(max = 200) String displayName,
                                    @NotNull UserRole role,
                                    boolean enabled) {
    }

    public record PasswordRequest(@NotBlank @Size(min = 8) String password) {
    }

    public record TokenResponse(Long id, String name, UserRole role, boolean enabled,
                                LocalDateTime createdAt, LocalDateTime lastUsedAt) {
        public static TokenResponse from(ServiceToken token) {
            return new TokenResponse(token.getId(), token.getName(), token.getRole(),
                    token.isEnabled(), token.getCreatedAt(), token.getLastUsedAt());
        }
    }

    public record CreateTokenRequest(@NotBlank @Size(max = 100) String name, @NotNull UserRole role) {
    }

    /** Nur direkt nach der Erstellung enthaelt token den Klartext. */
    public record CreatedTokenResponse(TokenResponse info, String token) {
    }
}
```

- [ ] **Step 2: Controller**

```java
package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AppUser;
import com.household.manager.security.dto.UserAdminDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Nutzerverwaltung (nur ADMIN — via URL-Regel /v1/admin/** in SecurityConfig). */
@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final AppUserService appUserService;
    private final AuditService auditService;

    @GetMapping
    public List<UserResponse> list() {
        return appUserService.list().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        AppUser user = appUserService.create(request.username(), request.displayName(),
                request.password(), request.role());
        auditService.record("user.create", user.getUsername() + " (" + user.getRole() + ")");
        return UserResponse.from(user);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        AppUser user = appUserService.update(id, request.displayName(), request.role(), request.enabled());
        auditService.record("user.update", user.getUsername() + " (" + user.getRole()
                + (user.isEnabled() ? ", aktiv" : ", deaktiviert") + ")");
        return UserResponse.from(user);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest request) {
        appUserService.setPassword(id, request.password());
        auditService.record("user.set-password", "Nutzer-Id " + id);
    }
}
```

```java
package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.security.dto.UserAdminDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Service-Token-Verwaltung (nur ADMIN). */
@RestController
@RequestMapping("/v1/admin/service-tokens")
@RequiredArgsConstructor
public class ServiceTokenAdminController {

    private final ServiceTokenService serviceTokenService;
    private final AuditService auditService;

    @GetMapping
    public List<TokenResponse> list() {
        return serviceTokenService.list().stream().map(TokenResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTokenResponse create(@Valid @RequestBody CreateTokenRequest request) {
        ServiceTokenService.CreatedToken created = serviceTokenService.create(request.name(), request.role());
        auditService.record("token.create", created.token().getName()
                + " (" + created.token().getRole() + ")");
        return new CreatedTokenResponse(TokenResponse.from(created.token()), created.plaintext());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id) {
        ServiceToken token = serviceTokenService.revoke(id);
        auditService.record("token.revoke", token.getName());
    }
}
```

```java
package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** Audit-Log-Einsicht (nur ADMIN). */
@RestController
@RequestMapping("/v1/admin/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    public record AuditEntryResponse(Long id, LocalDateTime timestamp, AuditActorType actorType,
                                     String actor, String action, String detail) {
    }

    @GetMapping
    public List<AuditEntryResponse> recent(@RequestParam(defaultValue = "100") int limit,
                                           @RequestParam(required = false) String actor) {
        return auditService.recent(limit, actor).stream()
                .map(entry -> new AuditEntryResponse(entry.getId(), entry.getTimestamp(),
                        entry.getActorType(), entry.getActor(), entry.getAction(), entry.getDetail()))
                .toList();
    }
}
```

- [ ] **Step 3: Kompilieren + Commit**

Run: `cd backend; mvn -q compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/com/household/manager/security backend/src/main/java/com/household/manager/audit
git commit -m "feat(auth): Admin-API fuer Nutzer, Service-Tokens und Audit-Log"
```

---

### Task 13: Audit-Verdrahtung in bestehende Services

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/SwitchCommandService.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/ManualEntityService.java`
- Modify: `backend/src/main/java/com/household/manager/nuki/NukiLockService.java`
- Modify: `backend/src/main/java/com/household/manager/flowengine/FlowService.java`
- Modify: `backend/src/main/java/com/household/manager/calendar/CalendarEventService.java`
- Modify: `backend/src/main/java/com/household/manager/telegram/TelegramAgentService.java`
- Modify: betroffene bestehende Tests (Konstruktor-Aufrufe um `mock(AuditService.class)` erweitern)

Vorgehen für jede Service-Klasse identisch: `private final AuditService auditService;` als neues Feld ergänzen (Lombok `@RequiredArgsConstructor` zieht es in den Konstruktor) und nach der erfolgreichen Aktion `auditService.record(...)` aufrufen.

- [ ] **Step 1: Schalter und Modi**

In `SwitchCommandService.toggle(...)` unmittelbar vor dem `return` des Ergebnisses:

```java
auditService.record("switch.toggle", entityId);
```

In `ManualEntityService.toggle(...)` unmittelbar vor dem `return`:

```java
auditService.record("entity.toggle", entityId);
```

(Beide Methoden sind die Chokepoints — UI, Flows und Telegram laufen hier durch; Aktor ergibt sich automatisch aus dem Resolver.)

- [ ] **Step 2: Nuki**

In `NukiLockService.executeAction(long smartlockId, NukiLockAction action)` nach erfolgreicher Ausführung (nach dem API-Aufruf, vor dem Nachpollen/return):

```java
auditService.record("nuki." + action.name().toLowerCase(), String.valueOf(smartlockId));
```

- [ ] **Step 3: Flows**

In `FlowService`:
- `create(...)`: `auditService.record("flow.create", name);`
- `importFlow(...)`: `auditService.record("flow.import", name);`
- `update(...)`: `auditService.record("flow.update", "Flow " + id);`
- `deploy(...)`: nur bei `result.valid()`: `auditService.record("flow.deploy", "Flow " + id);`
- `setEnabled(id, enabled)`: `auditService.record(enabled ? "flow.enable" : "flow.disable", "Flow " + id);`
- `delete(id)`: `auditService.record("flow.delete", "Flow " + id);`

- [ ] **Step 4: Kalender**

In `CalendarEventService`:
- `create(request)`: `auditService.record("calendar.create", request.title());`
- `update(id, request)`: `auditService.record("calendar.update", request.title());`
- `delete(id)`: `auditService.record("calendar.delete", "Termin " + id);`
- `deleteOccurrence(id, date)`: `auditService.record("calendar.delete-occurrence", "Termin " + id + " am " + date);`
- `updateOccurrence(id, date, request)`: `auditService.record("calendar.update-occurrence", "Termin " + id + " am " + date);`

- [ ] **Step 5: Telegram-Aktor**

In `TelegramAgentService`: Die öffentliche Methode, die eine eingehende Chat-Nachricht verarbeitet (die Methode, die `TelegramPollingService` aufruft und die `chatId` als Parameter bekommt), so umschließen, dass während der Tool-Ausführung der Telegram-Aktor gesetzt ist:

```java
AuditActorContext.set(AuditActor.telegram(chatId));
try {
    // ... bisheriger Methodenrumpf unveraendert ...
} finally {
    AuditActorContext.clear();
}
```

Imports: `com.household.manager.audit.AuditActor`, `com.household.manager.audit.AuditActorContext`.

- [ ] **Step 6: Bestehende Tests reparieren**

Die Konstruktoren der geänderten Services haben jetzt einen Parameter mehr. Betroffen (mindestens): `SwitchCommandServiceTest`, `ManualEntityServiceTest`, `NukiLockServiceTest`, `FlowServiceTest`, `TelegramAgentServiceTest` sowie die Tests des `CalendarEventService`. In jedem Test ein `@Mock AuditService auditService;` (bzw. `mock(AuditService.class)`) ergänzen und dem Konstruktor mitgeben. Suche nach allen Verwendungen:

Run: `cd backend; mvn test-compile 2>&1 | Select-String "constructor"`

- [ ] **Step 7: Tests grün**

Run: `cd backend; mvn test "-Dtest=SwitchCommandServiceTest+ManualEntityServiceTest+NukiLockServiceTest+FlowServiceTest+TelegramAgentServiceTest"`
Expected: PASS (plus Kalender-Service-Tests, Namen per `Glob backend/src/test/java/**/calendar/*.java` ermitteln)

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat(audit): Schalter, Modi, Nuki, Flows, Kalender und Telegram auditiert"
```

---

### Task 14: Security-Regel-Tests (WebMvc-Slice)

**Files:**
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` (neu)

- [ ] **Step 1: Test schreiben**

Der Slice lädt drei repräsentative Controller + die echte SecurityConfig. URLs ohne Controller (z. B. `/v1/flows`) liefern bei erlaubter Rolle 404 — das reicht, um die Regel zu testen (403 wäre der Fehlerfall).

```java
package com.household.manager.security;

import com.household.manager.calendar.CalendarEventController;
import com.household.manager.calendar.CalendarEventService;
import com.household.manager.controller.SwitchController;
import com.household.manager.controller.TabletPresenceController;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import com.household.manager.nuki.NukiController;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.tablet.TabletPresenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {SwitchController.class, CalendarEventController.class,
        NukiController.class, TabletPresenceController.class})
@Import({SecurityConfig.class, ServiceTokenAuthFilter.class, DisabledUserSessionFilter.class})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SwitchQueryService switchQueryService;
    @MockitoBean
    private SwitchCommandService switchCommandService;
    @MockitoBean
    private CalendarEventService calendarEventService;
    @MockitoBean
    private NukiLockService nukiLockService;
    @MockitoBean
    private TabletPresenceService tabletPresenceService;
    @MockitoBean
    private ServiceTokenService serviceTokenService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @Test
    void anonymBekommt401() throws Exception {
        mockMvc.perform(get("/v1/switches")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfSchalterLesenUndSchalten() throws Exception {
        when(switchQueryService.listSwitches(null, false)).thenReturn(List.of());
        mockMvc.perform(get("/v1/switches")).andExpect(status().isOk());
        mockMvc.perform(post("/v1/switches/switch.x/toggle").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeineKalenderTermineAnlegen() throws Exception {
        mockMvc.perform(post("/v1/calendar/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKalenderTermineAnlegen() throws Exception {
        mockMvc.perform(post("/v1/calendar/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeineFlowsVerwalten() throws Exception {
        mockMvc.perform(get("/v1/flows")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminKommtAnFlowsVorbei() throws Exception {
        // Kein FlowController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst
        mockMvc.perform(get("/v1/flows")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfNukiNurVerriegeln() throws Exception {
        mockMvc.perform(post("/v1/nuki/locks/1/actions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"UNLATCH\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/nuki/locks/1/actions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"LOCK\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void postOhneCsrfTokenWirdAbgelehnt() throws Exception {
        mockMvc.perform(post("/v1/switches/switch.x/toggle"))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceTokenDarfTabletPresenceMelden() throws Exception {
        when(serviceTokenService.authenticate(anyString())).thenReturn(Optional.of(
                ServiceToken.builder().name("tablet").role(UserRole.KIOSK).enabled(true).build()));
        mockMvc.perform(post("/v1/tablet-presence/wandtablet")
                        .header("X-API-Token", "hm_ok")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"present\":true}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void browserSessionOhneServiceAuthorityKommtNichtAnTabletPresence() throws Exception {
        mockMvc.perform(post("/v1/tablet-presence/wandtablet").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"present\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskKommtNichtAnFinanzdaten() throws Exception {
        mockMvc.perform(get("/v1/finance/transactions")).andExpect(status().isForbidden());
    }
}
```

Hinweis: Falls `CalendarEventController`/`CalendarEventService` andere Klassennamen/Pakete haben als hier angenommen, die Imports anpassen (Paket `com.household.manager.calendar`). Falls der leere Body `{}` im Kalender-Test an Validierung scheitert (400 statt 201), Mock so einstellen, dass er ein Response-Objekt liefert, und den Status-Erwartungswert beibehalten — 403 vs. nicht-403 ist die eigentliche Aussage.

- [ ] **Step 2: Tests laufen lassen**

Run: `cd backend; mvn test "-Dtest=SecurityRulesTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/household/manager/security
git commit -m "test(auth): Rollenmatrix-Tests ueber WebMvc-Slice"
```

---

### Task 15: Frontend — Auth-Model und AuthService (TDD)

**Files:**
- Create: `frontend/src/app/models/auth.model.ts`
- Create: `frontend/src/app/services/auth.service.ts`
- Test: `frontend/src/app/services/auth.service.spec.ts`

- [ ] **Step 1: Model**

```ts
export type UserRole = 'ADMIN' | 'MEMBER' | 'KIOSK';

export interface CurrentUser {
  username: string;
  displayName: string;
  role: UserRole;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface AppUser {
  id: number;
  username: string;
  displayName: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
}

export interface ServiceTokenInfo {
  id: number;
  name: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface CreatedServiceToken {
  info: ServiceTokenInfo;
  token: string;
}

export interface AuditEntry {
  id: number;
  timestamp: string;
  actorType: 'USER' | 'SERVICE' | 'SYSTEM' | 'TELEGRAM';
  actor: string;
  action: string;
  detail: string | null;
}
```

- [ ] **Step 2: Failing Spec**

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { CurrentUser } from '../models/auth.model';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  const user: CurrentUser = { username: 'bene', displayName: 'Benedikt', role: 'ADMIN' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt den aktuellen Nutzer und cached ihn', () => {
    service.ensureLoaded().subscribe(result => expect(result).toEqual(user));
    httpMock.expectOne('/api/v1/auth/me').flush(user);

    // zweiter Aufruf geht nicht mehr ans Netz
    service.ensureLoaded().subscribe(result => expect(result).toEqual(user));
    expect(service.currentUser()).toEqual(user);
    expect(service.isAdmin()).toBeTrue();
  });

  it('behandelt 401 als nicht angemeldet', () => {
    let result: CurrentUser | null | undefined;
    service.ensureLoaded().subscribe(r => (result = r));
    httpMock.expectOne('/api/v1/auth/me').flush('nein', { status: 401, statusText: 'Unauthorized' });
    expect(result).toBeNull();
    expect(service.currentUser()).toBeNull();
  });

  it('setzt den Nutzer nach Login', () => {
    service.login({ username: 'bene', password: 'pw' }).subscribe();
    const req = httpMock.expectOne('/api/v1/auth/login');
    expect(req.request.method).toBe('POST');
    req.flush(user);
    expect(service.currentUser()).toEqual(user);
  });

  it('meldet 401 beim Login als verstaendlichen Fehler', () => {
    let message = '';
    service.login({ username: 'bene', password: 'falsch' })
      .subscribe({ error: (e: Error) => (message = e.message) });
    httpMock.expectOne('/api/v1/auth/login')
      .flush('nein', { status: 401, statusText: 'Unauthorized' });
    expect(message).toBe('Benutzername oder Passwort falsch.');
  });

  it('leert den Nutzer nach Logout', () => {
    service.login({ username: 'bene', password: 'pw' }).subscribe();
    httpMock.expectOne('/api/v1/auth/login').flush(user);

    service.logout().subscribe();
    httpMock.expectOne('/api/v1/auth/logout').flush(null);
    expect(service.currentUser()).toBeNull();
  });
});
```

- [ ] **Step 3: Spec rot sehen**

Run: `cd frontend; npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL (AuthService existiert nicht) — Baseline-Fails (Header/App/Hero) ignorieren.

- [ ] **Step 4: Implementierung**

```ts
import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { CurrentUser, LoginRequest } from '../models/auth.model';

/** Session-Login und aktueller Nutzer (Cookie-basiert, Spring Security). */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/auth';

  /** undefined = noch nicht geladen, null = nicht angemeldet */
  private readonly user = signal<CurrentUser | null | undefined>(undefined);

  readonly currentUser = computed(() => this.user() ?? null);
  readonly isAdmin = computed(() => this.user()?.role === 'ADMIN');
  readonly isMember = computed(() => this.user()?.role === 'ADMIN' || this.user()?.role === 'MEMBER');

  /** Laedt den Nutzer genau einmal; 401 wird zu null (nicht angemeldet). */
  ensureLoaded(): Observable<CurrentUser | null> {
    const known = this.user();
    if (known !== undefined) {
      return of(known);
    }
    return this.http.get<CurrentUser>(`${this.baseUrl}/me`).pipe(
      tap(user => this.user.set(user)),
      map((user): CurrentUser | null => user),
      catchError(() => {
        this.user.set(null);
        return of(null);
      })
    );
  }

  login(request: LoginRequest): Observable<CurrentUser> {
    return this.http.post<CurrentUser>(`${this.baseUrl}/login`, request).pipe(
      tap(user => this.user.set(user)),
      catchError((error: HttpErrorResponse) => throwError(() => new Error(
        error.status === 401 ? 'Benutzername oder Passwort falsch.' : 'Anmeldung fehlgeschlagen.')))
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout`, {}).pipe(
      tap(() => this.user.set(null))
    );
  }

  /** Vom Interceptor nach einem 401 aufgerufen. */
  clearUser(): void {
    this.user.set(null);
  }
}
```

- [ ] **Step 5: Spec grün + Commit**

Run: `cd frontend; npm test -- --watch=false --browsers=ChromeHeadless`
Expected: AuthService-Tests PASS (Baseline unverändert)

```bash
git add frontend/src/app/models/auth.model.ts frontend/src/app/services/auth.service.ts frontend/src/app/services/auth.service.spec.ts
git commit -m "feat(auth): AuthService und Auth-Modelle im Frontend"
```

---

### Task 16: Frontend — Interceptor, Guards, Login-Seite, Routen

**Files:**
- Create: `frontend/src/app/interceptors/auth.interceptor.ts`
- Create: `frontend/src/app/guards/auth.guard.ts`
- Create: `frontend/src/app/pages/login/login.component.ts` / `.html` / `.scss`
- Modify: `frontend/src/app/app.config.ts`
- Modify: `frontend/src/app/app.routes.ts`

- [ ] **Step 1: Interceptor**

```ts
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';

/** Leitet bei abgelaufener Session (401) zur Login-Seite um. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);
  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && !req.url.includes('/v1/auth/')) {
        auth.clearUser();
        router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
      }
      return throwError(() => error);
    })
  );
};
```

- [ ] **Step 2: Guards**

```ts
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';

/** Nur mit Anmeldung; sonst Login mit Ruecksprung-URL. */
export const authGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.ensureLoaded().pipe(map(user => user
    ? true
    : router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } })));
};

/** Nur fuer ADMIN; angemeldete Nicht-Admins landen auf dem Dashboard. */
export const adminGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.ensureLoaded().pipe(map(user => {
    if (!user) {
      return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
    }
    return user.role === 'ADMIN' ? true : router.createUrlTree(['/']);
  }));
};
```

- [ ] **Step 3: Login-Seite**

`login.component.ts`:

```ts
import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss'
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  username = '';
  password = '';
  errorMessage: string | null = null;
  isLoading = false;

  submit(): void {
    if (!this.username || !this.password || this.isLoading) {
      return;
    }
    this.isLoading = true;
    this.errorMessage = null;
    this.auth.login({ username: this.username, password: this.password }).subscribe({
      next: () => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/';
        this.router.navigateByUrl(returnUrl);
      },
      error: (e: Error) => {
        this.errorMessage = e.message;
        this.isLoading = false;
      }
    });
  }
}
```

`login.component.html`:

```html
<div class="login">
  <form class="login__card" (ngSubmit)="submit()">
    <h1 class="login__title">Household Manager</h1>
    <p class="login__subtitle">Bitte anmelden</p>

    @if (errorMessage) {
      <div class="login__error">{{ errorMessage }}</div>
    }

    <label class="login__label" for="username">Benutzername</label>
    <input class="login__input" id="username" name="username" type="text"
           [(ngModel)]="username" autocomplete="username" autofocus>

    <label class="login__label" for="password">Passwort</label>
    <input class="login__input" id="password" name="password" type="password"
           [(ngModel)]="password" autocomplete="current-password">

    <button class="login__submit" type="submit" [disabled]="isLoading || !username || !password">
      {{ isLoading ? 'Anmelden …' : 'Anmelden' }}
    </button>
  </form>
</div>
```

`login.component.scss`:

```scss
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 70vh;
  padding: var(--spacing-md);

  &__card {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-sm);
    width: 100%;
    max-width: 22rem;
    padding: var(--spacing-xl);
    border-radius: var(--radius-lg, 12px);
    box-shadow: var(--shadow-md);
    background: var(--color-surface, #fff);
  }

  &__title {
    margin: 0;
    font-size: var(--font-size-xl);
    color: var(--color-primary);
  }

  &__subtitle {
    margin: 0 0 var(--spacing-sm);
    color: var(--color-text-muted, #666);
  }

  &__error {
    padding: var(--spacing-sm);
    border-radius: var(--radius-sm, 6px);
    background: #fdecea;
    color: #b3261e;
    font-size: var(--font-size-sm);
  }

  &__label {
    font-size: var(--font-size-sm);
    font-weight: 600;
  }

  &__input {
    padding: var(--spacing-sm);
    border: 1px solid var(--color-border, #ccc);
    border-radius: var(--radius-sm, 6px);
    font-size: var(--font-size-md);
  }

  &__submit {
    margin-top: var(--spacing-sm);
    padding: var(--spacing-sm) var(--spacing-md);
    border: none;
    border-radius: var(--radius-sm, 6px);
    background: var(--color-primary);
    color: #fff;
    font-size: var(--font-size-md);
    cursor: pointer;
    transition: opacity var(--transition-fast);

    &:disabled {
      opacity: 0.6;
      cursor: default;
    }
  }
}
```

- [ ] **Step 4: app.config.ts erweitern**

```ts
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor]))
  ]
};
```

(Der Angular-Default-XSRF-Support — Cookie `XSRF-TOKEN` → Header `X-XSRF-TOKEN` — ist bei relativen URLs aktiv; keine weitere Konfiguration nötig.)

- [ ] **Step 5: Routen**

In `app.routes.ts`:
1. Neue Login-Route **vor** der Catch-all-Route `**` einfügen:
   ```ts
   { path: 'login', loadComponent: () => import('./pages/login/login.component').then(m => m.LoginComponent), title: 'Anmelden - Household Manager' },
   ```
2. Import ergänzen: `import { authGuard, adminGuard } from './guards/auth.guard';`
3. **Jede** bestehende Route erhält `canActivate: [authGuard]` — außer diesen fünf, die stattdessen `canActivate: [adminGuard]` bekommen: `flows`, `flows/:id` (dort zusätzlich zum bestehenden `canDeactivate`), `vision`, `admin`, `utility-prices`.

- [ ] **Step 6: Build prüfen + Commit**

Run: `cd frontend; npx ng build 2>&1 | Select-Object -Last 5`
Expected: Build erfolgreich

```bash
git add frontend/src/app
git commit -m "feat(auth): Login-Seite, 401-Interceptor und Route-Guards"
```

---

### Task 17: Frontend — Header (Nutzeranzeige, Logout, Rollenfilter)

**Files:**
- Modify: `frontend/src/app/components/header/header.component.ts`
- Modify: `frontend/src/app/components/header/header.component.html`
- Modify: `frontend/src/app/components/header/header.component.scss`

- [ ] **Step 1: NavLink um minRole erweitern und filtern**

In `header.component.ts`:

```ts
interface NavLink {
  path: string;
  label: string;
  exact?: boolean;
  minRole?: 'MEMBER' | 'ADMIN';
  children?: NavLink[];
}
```

- `AuthService` injizieren (`readonly auth = inject(AuthService);` — public, das Template braucht ihn) und `Router` wie bisher.
- Markierungen im bestehenden `navLinks`-Array: die Gruppe `/finance` bekommt `minRole: 'MEMBER'`; in der Gruppe `/admin` bekommen die Kinder Übersicht (`/admin`), Automatisierungen (`/flows`) und Gesichtserkennung (`/vision`) je `minRole: 'ADMIN'`, das Kind Ansagen (`/announcements`) bleibt ohne minRole; neue Kinder am Ende der Admin-Gruppe:
  ```ts
  { path: '/admin/users', label: 'Nutzer', minRole: 'ADMIN' },
  { path: '/admin/service-tokens', label: 'API-Tokens', minRole: 'ADMIN' },
  { path: '/admin/audit-log', label: 'Audit-Log', minRole: 'ADMIN' },
  ```
- Computed + Logout ergänzen:

```ts
  readonly visibleNavLinks = computed(() => this.navLinks
    .map(link => ({
      ...link,
      children: link.children?.filter(child => this.allows(child.minRole ?? link.minRole))
    }))
    .filter(link => this.allows(link.minRole)
      && (link.children === undefined || link.children.length > 0)));

  private allows(minRole?: 'MEMBER' | 'ADMIN'): boolean {
    if (!minRole) {
      return true;
    }
    return minRole === 'ADMIN' ? this.auth.isAdmin() : this.auth.isMember();
  }

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigate(['/login']));
  }
```

Imports: `computed`, `inject` aus `@angular/core`, `AuthService`.

- [ ] **Step 2: Template umstellen**

In `header.component.html` alle `@for`-Schleifen über `navLinks` auf `visibleNavLinks()` umstellen. Am Ende der Desktop-Nav (innerhalb `header__nav--desktop`) und im Mobile-Menü ergänzen:

```html
@if (auth.currentUser(); as user) {
  <div class="header__user">
    <span class="header__user-name">{{ user.displayName }}</span>
    <button class="header__logout" type="button" (click)="logout()">Abmelden</button>
  </div>
}
```

- [ ] **Step 3: SCSS ergänzen** (in `header.component.scss` innerhalb `.header`):

```scss
  &__user {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    margin-left: auto;
    padding-left: var(--spacing-md);
  }

  &__user-name {
    font-size: var(--font-size-sm);
    font-weight: 600;
  }

  &__logout {
    padding: var(--spacing-xs) var(--spacing-sm);
    border: 1px solid var(--color-border, #ccc);
    border-radius: var(--radius-sm, 6px);
    background: transparent;
    font-size: var(--font-size-sm);
    cursor: pointer;
    transition: background var(--transition-fast);

    &:hover {
      background: rgba(0, 0, 0, 0.05);
    }
  }
```

- [ ] **Step 4: Header-Spec anpassen**

`header.component.spec.ts` braucht jetzt HTTP-Provider: in `TestBed.configureTestingModule` `provideHttpClient(), provideHttpClientTesting()` zu den providers hinzufügen. (Der Header-Spec ist Teil der bekannten Fail-Baseline — Ziel ist nur: nicht **zusätzlich** brechen.)

- [ ] **Step 5: Build + Commit**

Run: `cd frontend; npx ng build 2>&1 | Select-Object -Last 5`
Expected: Build erfolgreich

```bash
git add frontend/src/app/components/header
git commit -m "feat(auth): Header mit Nutzeranzeige, Logout und Rollenfilter"
```

---

### Task 18: Frontend — Admin-Services (TDD) und Admin-Seiten

**Files:**
- Create: `frontend/src/app/services/user-admin.service.ts` + `.spec.ts`
- Create: `frontend/src/app/services/service-token-admin.service.ts`
- Create: `frontend/src/app/services/audit-log.service.ts`
- Create: `frontend/src/app/pages/admin-users/admin-users.component.ts` / `.html` / `.scss`
- Create: `frontend/src/app/pages/admin-service-tokens/admin-service-tokens.component.ts` / `.html` / `.scss`
- Create: `frontend/src/app/pages/admin-audit-log/admin-audit-log.component.ts` / `.html` / `.scss`
- Modify: `frontend/src/app/app.routes.ts` (drei neue Admin-Routen)

- [ ] **Step 1: Failing Spec für UserAdminService**

```ts
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UserAdminService } from './user-admin.service';
import { AppUser } from '../models/auth.model';

describe('UserAdminService', () => {
  let service: UserAdminService;
  let httpMock: HttpTestingController;

  const user: AppUser = {
    id: 1, username: 'mia', displayName: 'Mia', role: 'MEMBER', enabled: true,
    createdAt: '2026-07-25T10:00:00'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(UserAdminService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt die Nutzerliste', () => {
    service.getUsers().subscribe(result => expect(result).toEqual([user]));
    const req = httpMock.expectOne('/api/v1/admin/users');
    expect(req.request.method).toBe('GET');
    req.flush([user]);
  });

  it('legt einen Nutzer an', () => {
    service.createUser({ username: 'mia', displayName: 'Mia', password: 'geheim123', role: 'MEMBER' })
      .subscribe(result => expect(result).toEqual(user));
    const req = httpMock.expectOne('/api/v1/admin/users');
    expect(req.request.method).toBe('POST');
    req.flush(user);
  });
});
```

- [ ] **Step 2: Services implementieren**

`user-admin.service.ts`:

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AppUser, UserRole } from '../models/auth.model';

export interface CreateUserRequest {
  username: string;
  displayName: string;
  password: string;
  role: UserRole;
}

export interface UpdateUserRequest {
  displayName: string;
  role: UserRole;
  enabled: boolean;
}

/** Admin-API der Nutzerverwaltung. */
@Injectable({ providedIn: 'root' })
export class UserAdminService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/users';

  getUsers(): Observable<AppUser[]> {
    return this.http.get<AppUser[]>(this.baseUrl).pipe(catchError(this.handleError));
  }

  createUser(request: CreateUserRequest): Observable<AppUser> {
    return this.http.post<AppUser>(this.baseUrl, request).pipe(catchError(this.handleError));
  }

  updateUser(id: number, request: UpdateUserRequest): Observable<AppUser> {
    return this.http.put<AppUser>(`${this.baseUrl}/${id}`, request).pipe(catchError(this.handleError));
  }

  setPassword(id: number, password: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/${id}/password`, { password })
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Nutzerverwaltungs-API-Fehler:', error);
    const message = error.error?.message ?? 'Fehler bei der Nutzerverwaltung.';
    return throwError(() => new Error(message));
  }
}
```

`service-token-admin.service.ts`:

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CreatedServiceToken, ServiceTokenInfo, UserRole } from '../models/auth.model';

/** Admin-API der Service-Tokens (Maschinen-Clients). */
@Injectable({ providedIn: 'root' })
export class ServiceTokenAdminService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/service-tokens';

  getTokens(): Observable<ServiceTokenInfo[]> {
    return this.http.get<ServiceTokenInfo[]>(this.baseUrl).pipe(catchError(this.handleError));
  }

  createToken(name: string, role: UserRole): Observable<CreatedServiceToken> {
    return this.http.post<CreatedServiceToken>(this.baseUrl, { name, role })
      .pipe(catchError(this.handleError));
  }

  revokeToken(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Token-API-Fehler:', error);
    const message = error.error?.message ?? 'Fehler bei der Token-Verwaltung.';
    return throwError(() => new Error(message));
  }
}
```

`audit-log.service.ts`:

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuditEntry } from '../models/auth.model';

/** Admin-API des Audit-Logs. */
@Injectable({ providedIn: 'root' })
export class AuditLogService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/audit-log';

  getEntries(limit: number, actor?: string): Observable<AuditEntry[]> {
    let params = new HttpParams().set('limit', limit);
    if (actor) {
      params = params.set('actor', actor);
    }
    return this.http.get<AuditEntry[]>(this.baseUrl, { params }).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Audit-Log-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden des Audit-Logs.'));
  }
}
```

- [ ] **Step 3: Admin-Seiten** (Muster `finance-accounts`: Tabelle + Formular, template-driven)

`admin-users.component.ts`:

```ts
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { UserAdminService, CreateUserRequest } from '../../services/user-admin.service';
import { AppUser, UserRole } from '../../models/auth.model';

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-users.component.html',
  styleUrl: './admin-users.component.scss'
})
export class AdminUsersComponent implements OnInit {
  private readonly userAdminService = inject(UserAdminService);

  readonly roles: UserRole[] = ['ADMIN', 'MEMBER', 'KIOSK'];
  users: AppUser[] = [];
  form: CreateUserRequest = { username: '', displayName: '', password: '', role: 'MEMBER' };
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.userAdminService.getUsers().subscribe({
      next: users => (this.users = users),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  create(): void {
    if (!this.form.username || !this.form.displayName || this.form.password.length < 8) {
      return;
    }
    this.userAdminService.createUser(this.form).subscribe({
      next: () => {
        this.form = { username: '', displayName: '', password: '', role: 'MEMBER' };
        this.load();
      },
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  save(user: AppUser): void {
    this.userAdminService.updateUser(user.id, {
      displayName: user.displayName, role: user.role, enabled: user.enabled
    }).subscribe({
      next: () => this.load(),
      error: (e: Error) => { this.errorMessage = e.message; this.load(); }
    });
  }

  changePassword(user: AppUser): void {
    const password = prompt(`Neues Passwort für ${user.username} (min. 8 Zeichen):`);
    if (!password || password.length < 8) {
      return;
    }
    this.userAdminService.setPassword(user.id, password).subscribe({
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }
}
```

`admin-users.component.html`:

```html
<div class="admin-users">
  <h1>Nutzerverwaltung</h1>

  @if (errorMessage) {
    <div class="admin-users__error">{{ errorMessage }}</div>
  }

  <form class="admin-users__form" (ngSubmit)="create()">
    <input name="username" placeholder="Benutzername" [(ngModel)]="form.username">
    <input name="displayName" placeholder="Anzeigename" [(ngModel)]="form.displayName">
    <input name="password" type="password" placeholder="Passwort (min. 8)" [(ngModel)]="form.password">
    <select name="role" [(ngModel)]="form.role">
      @for (role of roles; track role) {
        <option [value]="role">{{ role }}</option>
      }
    </select>
    <button type="submit">Anlegen</button>
  </form>

  <table class="admin-users__table">
    <thead>
      <tr><th>Benutzername</th><th>Anzeigename</th><th>Rolle</th><th>Aktiv</th><th></th></tr>
    </thead>
    <tbody>
      @for (user of users; track user.id) {
        <tr>
          <td>{{ user.username }}</td>
          <td><input [name]="'dn-' + user.id" [(ngModel)]="user.displayName"></td>
          <td>
            <select [name]="'role-' + user.id" [(ngModel)]="user.role">
              @for (role of roles; track role) {
                <option [value]="role">{{ role }}</option>
              }
            </select>
          </td>
          <td><input type="checkbox" [name]="'en-' + user.id" [(ngModel)]="user.enabled"></td>
          <td>
            <button type="button" (click)="save(user)">Speichern</button>
            <button type="button" (click)="changePassword(user)">Passwort</button>
          </td>
        </tr>
      }
    </tbody>
  </table>
</div>
```

`admin-users.component.scss`:

```scss
.admin-users {
  padding: var(--spacing-lg);

  &__error {
    margin-bottom: var(--spacing-md);
    padding: var(--spacing-sm);
    border-radius: var(--radius-sm, 6px);
    background: #fdecea;
    color: #b3261e;
  }

  &__form {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-sm);
    margin-bottom: var(--spacing-lg);

    input,
    select {
      padding: var(--spacing-xs) var(--spacing-sm);
      border: 1px solid var(--color-border, #ccc);
      border-radius: var(--radius-sm, 6px);
    }

    button {
      padding: var(--spacing-xs) var(--spacing-md);
      border: none;
      border-radius: var(--radius-sm, 6px);
      background: var(--color-primary);
      color: #fff;
      cursor: pointer;
    }
  }

  &__table {
    width: 100%;
    border-collapse: collapse;

    th,
    td {
      padding: var(--spacing-sm);
      border-bottom: 1px solid var(--color-border, #eee);
      text-align: left;
    }
  }
}
```

`admin-service-tokens.component.ts`:

```ts
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ServiceTokenAdminService } from '../../services/service-token-admin.service';
import { ServiceTokenInfo, UserRole } from '../../models/auth.model';

@Component({
  selector: 'app-admin-service-tokens',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-service-tokens.component.html',
  styleUrl: './admin-service-tokens.component.scss'
})
export class AdminServiceTokensComponent implements OnInit {
  private readonly tokenService = inject(ServiceTokenAdminService);

  readonly roles: UserRole[] = ['ADMIN', 'MEMBER', 'KIOSK'];
  tokens: ServiceTokenInfo[] = [];
  name = '';
  role: UserRole = 'KIOSK';
  createdToken: string | null = null;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.tokenService.getTokens().subscribe({
      next: tokens => (this.tokens = tokens),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  create(): void {
    if (!this.name) {
      return;
    }
    this.tokenService.createToken(this.name, this.role).subscribe({
      next: created => {
        this.createdToken = created.token;
        this.name = '';
        this.load();
      },
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  revoke(token: ServiceTokenInfo): void {
    if (!confirm(`Token "${token.name}" widerrufen? Der Client verliert sofort den Zugriff.`)) {
      return;
    }
    this.tokenService.revokeToken(token.id).subscribe({
      next: () => this.load(),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }
}
```

`admin-service-tokens.component.html`:

```html
<div class="admin-tokens">
  <h1>API-Tokens</h1>

  @if (errorMessage) {
    <div class="admin-tokens__error">{{ errorMessage }}</div>
  }

  @if (createdToken) {
    <div class="admin-tokens__created">
      <strong>Token erstellt — jetzt kopieren, er wird nie wieder angezeigt:</strong>
      <code>{{ createdToken }}</code>
    </div>
  }

  <form class="admin-tokens__form" (ngSubmit)="create()">
    <input name="name" placeholder="Name (z. B. wandtablet)" [(ngModel)]="name">
    <select name="role" [(ngModel)]="role">
      @for (r of roles; track r) {
        <option [value]="r">{{ r }}</option>
      }
    </select>
    <button type="submit">Erstellen</button>
  </form>

  <table class="admin-tokens__table">
    <thead>
      <tr><th>Name</th><th>Rolle</th><th>Status</th><th>Zuletzt benutzt</th><th></th></tr>
    </thead>
    <tbody>
      @for (token of tokens; track token.id) {
        <tr>
          <td>{{ token.name }}</td>
          <td>{{ token.role }}</td>
          <td>{{ token.enabled ? 'aktiv' : 'widerrufen' }}</td>
          <td>{{ token.lastUsedAt ? (token.lastUsedAt | date: 'dd.MM.yyyy HH:mm') : 'nie' }}</td>
          <td>
            @if (token.enabled) {
              <button type="button" (click)="revoke(token)">Widerrufen</button>
            }
          </td>
        </tr>
      }
    </tbody>
  </table>
</div>
```

`admin-service-tokens.component.scss` (gleiches Muster wie admin-users, plus Hervorhebung):

```scss
.admin-tokens {
  padding: var(--spacing-lg);

  &__error {
    margin-bottom: var(--spacing-md);
    padding: var(--spacing-sm);
    border-radius: var(--radius-sm, 6px);
    background: #fdecea;
    color: #b3261e;
  }

  &__created {
    margin-bottom: var(--spacing-md);
    padding: var(--spacing-md);
    border-radius: var(--radius-sm, 6px);
    background: #e6f4ea;
    color: #1e4620;

    code {
      display: block;
      margin-top: var(--spacing-xs);
      word-break: break-all;
      user-select: all;
    }
  }

  &__form {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-sm);
    margin-bottom: var(--spacing-lg);

    input,
    select {
      padding: var(--spacing-xs) var(--spacing-sm);
      border: 1px solid var(--color-border, #ccc);
      border-radius: var(--radius-sm, 6px);
    }

    button {
      padding: var(--spacing-xs) var(--spacing-md);
      border: none;
      border-radius: var(--radius-sm, 6px);
      background: var(--color-primary);
      color: #fff;
      cursor: pointer;
    }
  }

  &__table {
    width: 100%;
    border-collapse: collapse;

    th,
    td {
      padding: var(--spacing-sm);
      border-bottom: 1px solid var(--color-border, #eee);
      text-align: left;
    }
  }
}
```

`admin-audit-log.component.ts`:

```ts
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuditLogService } from '../../services/audit-log.service';
import { AuditEntry } from '../../models/auth.model';

@Component({
  selector: 'app-admin-audit-log',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-audit-log.component.html',
  styleUrl: './admin-audit-log.component.scss'
})
export class AdminAuditLogComponent implements OnInit {
  private readonly auditLogService = inject(AuditLogService);

  entries: AuditEntry[] = [];
  actorFilter = '';
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.auditLogService.getEntries(200, this.actorFilter || undefined).subscribe({
      next: entries => (this.entries = entries),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }
}
```

`admin-audit-log.component.html`:

```html
<div class="audit-log">
  <h1>Audit-Log</h1>

  @if (errorMessage) {
    <div class="audit-log__error">{{ errorMessage }}</div>
  }

  <form class="audit-log__filter" (ngSubmit)="load()">
    <input name="actor" placeholder="Aktor filtern (z. B. bene)" [(ngModel)]="actorFilter">
    <button type="submit">Filtern</button>
  </form>

  <table class="audit-log__table">
    <thead>
      <tr><th>Zeitpunkt</th><th>Aktor</th><th>Aktion</th><th>Detail</th></tr>
    </thead>
    <tbody>
      @for (entry of entries; track entry.id) {
        <tr>
          <td>{{ entry.timestamp | date: 'dd.MM.yyyy HH:mm:ss' }}</td>
          <td>{{ entry.actorType }} / {{ entry.actor }}</td>
          <td>{{ entry.action }}</td>
          <td>{{ entry.detail }}</td>
        </tr>
      }
    </tbody>
  </table>
</div>
```

`admin-audit-log.component.scss`:

```scss
.audit-log {
  padding: var(--spacing-lg);

  &__error {
    margin-bottom: var(--spacing-md);
    padding: var(--spacing-sm);
    border-radius: var(--radius-sm, 6px);
    background: #fdecea;
    color: #b3261e;
  }

  &__filter {
    display: flex;
    gap: var(--spacing-sm);
    margin-bottom: var(--spacing-lg);

    input {
      padding: var(--spacing-xs) var(--spacing-sm);
      border: 1px solid var(--color-border, #ccc);
      border-radius: var(--radius-sm, 6px);
    }

    button {
      padding: var(--spacing-xs) var(--spacing-md);
      border: none;
      border-radius: var(--radius-sm, 6px);
      background: var(--color-primary);
      color: #fff;
      cursor: pointer;
    }
  }

  &__table {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--font-size-sm);

    th,
    td {
      padding: var(--spacing-xs) var(--spacing-sm);
      border-bottom: 1px solid var(--color-border, #eee);
      text-align: left;
    }
  }
}
```

- [ ] **Step 4: Routen ergänzen** (in `app.routes.ts`, vor der Catch-all-Route; **vor** der bestehenden `admin`-Route einsortieren, damit die spezifischeren Pfade greifen):

```ts
  { path: 'admin/users', loadComponent: () => import('./pages/admin-users/admin-users.component').then(m => m.AdminUsersComponent), canActivate: [adminGuard], title: 'Nutzer - Household Manager' },
  { path: 'admin/service-tokens', loadComponent: () => import('./pages/admin-service-tokens/admin-service-tokens.component').then(m => m.AdminServiceTokensComponent), canActivate: [adminGuard], title: 'API-Tokens - Household Manager' },
  { path: 'admin/audit-log', loadComponent: () => import('./pages/admin-audit-log/admin-audit-log.component').then(m => m.AdminAuditLogComponent), canActivate: [adminGuard], title: 'Audit-Log - Household Manager' },
```

- [ ] **Step 5: Tests + Build**

Run: `cd frontend; npm test -- --watch=false --browsers=ChromeHeadless`
Expected: UserAdminService-Tests PASS, Baseline unverändert

Run: `cd frontend; npx ng build 2>&1 | Select-Object -Last 5`
Expected: Build erfolgreich

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app
git commit -m "feat(auth): Admin-Seiten fuer Nutzer, Tokens und Audit-Log"
```

---

### Task 19: blink-vision-Sidecar sendet den Service-Token

**Files:**
- Modify: `blink-vision/app/config.py`
- Modify: `blink-vision/app/backend_client.py`
- Modify: `blink-vision/tests/test_backend_url.py`

- [ ] **Step 1: Config erweitern** (`config.py`):

```python
API_TOKEN = os.environ.get("API_TOKEN", "")
```

- [ ] **Step 2: Header zentral ergänzen** (`backend_client.py`):

```python
def _headers() -> dict:
    """X-API-Token fuer das Backend; leer konfiguriert -> kein Header (Dev ohne Auth)."""
    return {"X-API-Token": config.API_TOKEN} if config.API_TOKEN else {}
```

Alle drei `httpx.AsyncClient`-Aufrufe erhalten `headers=_headers()`:

```python
    async with httpx.AsyncClient(timeout=30, headers=_headers()) as client:
```

(analog bei `post_heartbeat` mit `timeout=10` und `fetch_embeddings` mit `timeout=30`).

- [ ] **Step 3: Test ergänzen** (`tests/test_backend_url.py`):

```python
def test_headers_enthalten_token_wenn_konfiguriert(monkeypatch):
    from app import backend_client, config
    monkeypatch.setattr(config, "API_TOKEN", "hm_test")
    assert backend_client._headers() == {"X-API-Token": "hm_test"}


def test_headers_leer_ohne_token(monkeypatch):
    from app import backend_client, config
    monkeypatch.setattr(config, "API_TOKEN", "")
    assert backend_client._headers() == {}
```

Run: `cd blink-vision; python -m pytest tests/ -q` (falls die Python-Umgebung lokal fehlt, diesen Schritt dokumentiert überspringen — der Sidecar läuft nur im Docker-Deployment)

- [ ] **Step 4: Commit**

```bash
git add blink-vision
git commit -m "feat(auth): blink-vision sendet X-API-Token"
```

---

### Task 20: flow-mcp-server sendet den Service-Token

**Files:**
- Modify: `flow-mcp-server/src/api-client.js`
- Modify: `flow-mcp-server/README.md`
- Modify: `.mcp.json`

- [ ] **Step 1: Header in `api-client.js`** — im `request()` die Header-Zeile ersetzen durch:

```js
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
    if (process.env.HOUSEHOLD_API_TOKEN) {
      headers['X-API-Token'] = process.env.HOUSEHOLD_API_TOKEN;
    }
```

und im `fetch`-Aufruf `headers` verwenden (statt des Inline-Objekts).

- [ ] **Step 2: `.mcp.json`** — env ergänzen:

```json
      "env": {
        "HOUSEHOLD_API_URL": "http://localhost:8080/api",
        "HOUSEHOLD_API_TOKEN": "${HOUSEHOLD_API_TOKEN}"
      }
```

- [ ] **Step 3: README** — bei den Env-Variablen dokumentieren: `HOUSEHOLD_API_TOKEN` — Service-Token mit Rolle ADMIN (über die Admin-Seite „API-Tokens“ erstellen); ohne Token antwortet das Backend mit 401.

- [ ] **Step 4: Commit**

```bash
git add flow-mcp-server .mcp.json
git commit -m "feat(auth): flow-mcp-server sendet X-API-Token"
```

---

### Task 21: Tablet-App — Token, Settings-Feld, Cookie-Persistenz

**Files:**
- Modify: `tablet-app/app/src/main/java/com/household/manager/tabletapp/AppSettings.kt`
- Modify: `tablet-app/app/src/main/java/com/household/manager/tabletapp/PresenceReporter.kt`
- Modify: `tablet-app/app/src/main/java/com/household/manager/tabletapp/SettingsActivity.kt`
- Modify: `tablet-app/app/src/main/res/layout/activity_settings.xml`
- Modify: `tablet-app/app/src/main/java/com/household/manager/tabletapp/KioskActivity.kt`

- [ ] **Step 1: AppSettings** — neben `tabletId` ergänzen:

```kotlin
var apiToken: String
    get() = prefs.getString(KEY_API_TOKEN, "")!!
    set(value) = prefs.edit().putString(KEY_API_TOKEN, value.trim()).apply()
```

mit `private const val KEY_API_TOKEN = "api_token"` bei den übrigen Key-Konstanten.

- [ ] **Step 2: PresenceReporter** — im `post(...)` den Header setzen:

```kotlin
val builder = Request.Builder().url(url).post(body)
if (settings.apiToken.isNotBlank()) {
    builder.addHeader("X-API-Token", settings.apiToken)
}
client.newCall(builder.build()).execute().use { response ->
```

- [ ] **Step 3: Settings-UI** — in `activity_settings.xml` unter dem Tablet-ID-Feld ein weiteres Eingabefeld nach dem Muster der bestehenden Felder anlegen (`android:id="@+id/input_api_token"`, Hint „API-Token“); in `SettingsActivity.kt` analog zu `input_tablet_id` laden und speichern (`settings.apiToken = findViewById<EditText>(R.id.input_api_token).text.toString()` bzw. beim Öffnen vorbelegen).

- [ ] **Step 4: Cookie-Persistenz im WebView** — in `KioskActivity.setupWebView()` vor `loadDashboard()`:

```kotlin
CookieManager.getInstance().setAcceptCookie(true)
CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
```

und in `onPause()` (bzw. neu anlegen, `super.onPause()` zuerst):

```kotlin
CookieManager.getInstance().flush()
```

Import: `android.webkit.CookieManager`. Damit überlebt das Session-/Remember-Me-Cookie App- und Geräteneustarts; der einmalige Login am Tablet erfolgt über die Login-Seite im WebView mit dem KIOSK-Gerätekonto.

- [ ] **Step 5: Build (falls SDK lokal verfügbar)**

Run: `cd tablet-app; .\gradlew assembleDebug 2>&1 | Select-Object -Last 3`
Expected: BUILD SUCCESSFUL (falls das Android-SDK fehlt: Schritt dokumentiert überspringen)

- [ ] **Step 6: Commit**

```bash
git add tablet-app
git commit -m "feat(auth): Tablet-App mit API-Token und Cookie-Persistenz"
```

---

### Task 22: docker-compose, Doku, Gesamt-Testlauf

**Files:**
- Modify: `docker-compose.yml`
- Modify: `CLAUDE.md`

- [ ] **Step 1: docker-compose.yml** — beim Service `backend` in `environment` ergänzen:

```yaml
      INITIAL_ADMIN_PASSWORD: ${INITIAL_ADMIN_PASSWORD:-}
      REMEMBER_ME_KEY: ${REMEMBER_ME_KEY:-}
```

Beim Service `blink-vision` in `environment` ergänzen:

```yaml
      API_TOKEN: ${VISION_API_TOKEN:-}
```

- [ ] **Step 2: CLAUDE.md** — neuen Abschnitt unter „Smart Device Integrations“ (nach dem Haushaltskalender-Abschnitt) einfügen:

```markdown
### Benutzerverwaltung & API-Sicherheit
- Spring Security mit Server-Sessions (HttpOnly-Cookie) + Remember-Me-Cookie (90 Tage, überlebt Backend-Neustarts nur mit gesetztem `REMEMBER_ME_KEY`); kein JWT — bewusste Entscheidung für LAN-only-Betrieb
- **3 feste Rollen** mit Hierarchie ADMIN > MEMBER > KIOSK: KIOSK (Wandtablet) darf lesen, Schalter/Modi schalten und Nuki nur **verriegeln** (Body-abhängige Prüfung im `NukiController`, nicht per URL-Regel); MEMBER zusätzlich Tür öffnen, Kalender/Zähler/Ansagen; ADMIN alles (Flows, Nutzer, Tokens, Audit, Preise, Vision, Alexa-Login). Autoritative Regelliste: `SecurityConfig.filterChain` — die Reihenfolge der Matcher ist relevant
- **Service-Tokens** (Header `X-API-Token`, SHA-256-gehasht in `service_token`, einzeln widerrufbar): blink-vision (Env `API_TOKEN`), Tablet-Presence (App-Einstellung), flow-mcp-server (`HOUSEHOLD_API_TOKEN`). Die reinen Maschinen-Endpunkte (Vision-Webhook/Embeddings, tablet-presence) verlangen die SERVICE-Authority — eine Browser-Session kommt dort nicht ran
- **Audit-Log** (`audit_log`): Login/Logout, Schalter, Modi, Nuki, Flow-/Nutzer-/Token-/Kalender-Änderungen. Aktor-Auflösung: ThreadLocal-Override (Telegram: `TELEGRAM:<chatId>`) > SecurityContext (USER/SERVICE) > SYSTEM (Flows, Scheduler). `AuditService.record` wirft nie — Audit darf die Aktion nicht brechen
- **Bootstrap:** Erster Start ohne Nutzer legt `admin` an (`INITIAL_ADMIN_PASSWORD` oder Zufallspasswort einmalig im Log). Der letzte aktive Admin kann nicht deaktiviert/degradiert werden
- Deaktivierte Nutzer verlieren sofort den Zugang (`DisabledUserSessionFilter`, eine DB-Abfrage pro Session-Request — Haushaltsgröße)
- **Rollout-Reihenfolge beachten:** Erst Tokens über die Admin-Seite „API-Tokens“ anlegen, dann Envs setzen (`VISION_API_TOKEN`, `HOUSEHOLD_API_TOKEN`, Tablet-Einstellung), dann Sidecars neu starten — sonst fallen Vision-Webhooks und Tablet-Presence **still** aus
- Frontend: Login-Seite (`pages/login/`), 401-Interceptor, `authGuard`/`adminGuard`, Admin-Seiten `admin/users`, `admin/service-tokens`, `admin/audit-log`; Header filtert Menüpunkte nach Rolle
- GlobalExceptionHandler hat explizite 401/403-Handler — ohne sie würde der Catch-all `AccessDeniedException` in 500 verwandeln
```

- [ ] **Step 3: Backend-Gesamtlauf**

Run: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"; cd backend; mvn test 2>&1 | Select-Object -Last 20`
Expected: Alle neuen Tests PASS; einzige tolerierte Abweichung ist die dokumentierte DB-Baseline (`HouseholdManagerApplicationTests`). Jeden anderen Fail fixen, bevor es weitergeht.

- [ ] **Step 4: Frontend-Gesamtlauf**

Run: `cd frontend; npm test -- --watch=false --browsers=ChromeHeadless 2>&1 | Select-Object -Last 10`
Expected: Keine neuen Fails gegenüber der Baseline (4 bekannte Fails Header/App/Hero + evtl. SmartDeviceList-Flake).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml CLAUDE.md
git commit -m "docs(auth): Usermanagement dokumentiert, Deployment-Envs ergaenzt"
```

---

## Rollout (manuell, nach dem Merge)

1. `REMEMBER_ME_KEY` und optional `INITIAL_ADMIN_PASSWORD` in der Server-`.env` setzen, Backend deployen.
2. Als `admin` einloggen (Passwort aus Env oder Backend-Log), eigene Nutzerkonten + Tablet-Gerätekonto (Rolle KIOSK) anlegen.
3. Auf der Admin-Seite „API-Tokens“ drei Tokens erstellen: `blink-vision` (MEMBER), `wandtablet` (KIOSK), `flow-mcp` (ADMIN).
4. `VISION_API_TOKEN` in die Server-`.env`, `HOUSEHOLD_API_TOKEN` in die lokale Umgebung (für `.mcp.json`), Token in den Tablet-Einstellungen eintragen; blink-vision-Container neu starten.
5. Am Tablet einmalig mit dem Gerätekonto einloggen.
6. Kontrolle: Audit-Log zeigt Presence-/Vision-Aktivität; `last_used_at` der Tokens füllt sich.
