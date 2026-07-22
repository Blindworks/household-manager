# Blink-Gesichtserkennung mit Nuki-Auto-Unlock — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bewegungs-Clips der Blink-Türkamera automatisch auf registrierte Bewohner analysieren, Erkennungen als Entity-Events melden und (per deaktiviert angelegtem Flow) das Nuki-Schloss öffnen.

**Architecture:** Neuer Python-Sidecar `blink-vision/` (FastAPI + blinkpy + InsightFace) pollt Blink-Clips, erkennt Gesichter und meldet Ergebnisse per Webhook an das Spring-Boot-Backend (neues Package `vision/`). Das Backend verwaltet Personen + Referenzfotos (führend), spiegelt Erkennungen als EVENT-Entität in den Entity-State-Layer; ein Flow reagiert darauf mit `nuki-lock-action`.

**Tech Stack:** Python 3.12, FastAPI, blinkpy, InsightFace (`buffalo_s`, CPU/onnxruntime), OpenCV; Spring Boot 3.4.1/Java 21, Liquibase, Lombok; Angular 19.

**Spec:** `docs/superpowers/specs/2026-07-22-blink-gesichtserkennung-design.md`

**Wichtige Umgebungs-Hinweise (diese Maschine):**
- Vor jedem `mvn`: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` (Bash) — Default-JDK ist 17 und bricht.
- `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen lokal immer fehl (Test-DB nicht erreichbar) — ignorieren, nie „fixen“.
- Entity-ID folgt der Konvention `<domain>.<source>_<ref>[_<suffix>]` → die Event-Entität heißt **`event.vision_blink_door_person`** (die Spec schrieb verkürzt `event.blink_door_person`; die Konvention gewinnt, Spec wird in Task 14 korrigiert).
- Python auf dieser Maschine: `python` (Windows). Für den Sidecar wird ein venv unter `blink-vision/.venv` genutzt.

---

### Task 1: Blink-Spike — blinkpy-Login und Local-Storage-Manifest verifizieren

Der riskanteste Baustein zuerst: prüfen, dass wir mit blinkpy an Konto, Kamera und Clips kommen. **Kein Produktionscode**, nur ein Probe-Skript. Ergebnis bestimmt die exakten API-Aufrufe in Task 8/10.

**Files:**
- Create: `blink-vision/probe.py`
- Create: `blink-vision/requirements.txt`
- Create: `blink-vision/.gitignore`

- [ ] **Step 1: Projektordner + venv anlegen**

```bash
mkdir -p blink-vision && cd blink-vision && python -m venv .venv && .venv/Scripts/pip install --upgrade pip
```

- [ ] **Step 2: `blink-vision/.gitignore` schreiben**

```
.venv/
__pycache__/
*.pyc
data/
```

- [ ] **Step 3: `blink-vision/requirements.txt` schreiben (nur Spike-Teil)**

```
blinkpy>=0.23
aiohttp
```

- [ ] **Step 4: Installieren**

```bash
cd blink-vision && .venv/Scripts/pip install -r requirements.txt
```

- [ ] **Step 5: `blink-vision/probe.py` schreiben**

Interaktives Skript: fragt Benutzer/Passwort/2FA-PIN auf der Konsole ab, listet Sync-Module, Kameras und das Local-Storage-Manifest, lädt den neuesten Clip nach `blink-vision/data/probe-clip.mp4`.

```python
"""Einmaliger Spike: verifiziert blinkpy-Login, Kameraliste und Local-Storage-Clips.
Aufruf:  .venv/Scripts/python probe.py   (fragt Zugangsdaten interaktiv ab)"""
import asyncio
import getpass
import json
from pathlib import Path

from aiohttp import ClientSession
from blinkpy.blinkpy import Blink
from blinkpy.auth import Auth

DATA = Path(__file__).parent / "data"
CREDS = DATA / "blink-session.json"


async def main():
    DATA.mkdir(exist_ok=True)
    async with ClientSession() as session:
        blink = Blink(session=session)
        if CREDS.exists():
            auth_data = json.loads(CREDS.read_text())
            blink.auth = Auth(auth_data, no_prompt=True, session=session)
        else:
            username = input("Blink/Amazon E-Mail: ")
            password = getpass.getpass("Passwort: ")
            blink.auth = Auth({"username": username, "password": password},
                              no_prompt=True, session=session)
        await blink.start()
        if blink.key_required:
            key = input("2FA-PIN (per SMS/E-Mail): ")
            await blink.auth.send_auth_key(blink, key)
            await blink.setup_post_verify()
        await blink.save(str(CREDS))  # persistiert NUR Token/Session, keine Passwörter

        print("== Sync-Module ==")
        for name, sync in blink.sync.items():
            print(f"  {name}: {type(sync).__name__}")
            print(f"  local_storage: {getattr(sync, 'local_storage', 'n/a')}")
        print("== Kameras ==")
        for name, cam in blink.cameras.items():
            print(f"  {name}: id={cam.camera_id} type={cam.camera_type}")

        # Local-Storage-Manifest (Sync Module 2 + USB)
        for name, sync in blink.sync.items():
            if not getattr(sync, "local_storage", False):
                continue
            await sync.refresh()
            manifest = getattr(sync, "_local_storage", {}).get("manifest", [])
            print(f"== Manifest {name}: {len(manifest)} Clips ==")
            for item in list(manifest)[:5]:
                print(f"  id={item.id} camera={item.name} created={item.created_at}")
            if manifest:
                newest = manifest[0]
                await newest.prepare_download(blink)
                await newest.download_video(blink, str(DATA / "probe-clip.mp4"))
                print("Clip gespeichert:", DATA / "probe-clip.mp4")


asyncio.run(main())
```

- [ ] **Step 6: Spike ausführen und Ergebnis protokollieren**

```bash
cd blink-vision && .venv/Scripts/python probe.py
```

Erwartet: Login klappt (ggf. 2FA-PIN), Sync-Modul mit `local_storage: True`, Manifest mit Clips, `data/probe-clip.mp4` existiert und ist abspielbar.
**Falls Attribut-/Methodennamen abweichen** (blinkpy-Version!): mit `python -c "import blinkpy, inspect; ..."` bzw. der installierten Quelle (`.venv/Lib/site-packages/blinkpy/sync_module.py`) die tatsächlichen Namen ermitteln und **in diesem Plan die Tasks 8/10 vor Ausführung entsprechend anpassen**. Die Wrapper-Klasse `BlinkClient` (Task 10) ist die einzige Stelle, die diese Namen kennt.

- [ ] **Step 7: Commit (nur Gerüst, keine Session-Daten)**

```bash
git add blink-vision/.gitignore blink-vision/requirements.txt blink-vision/probe.py
git commit -m "chore(blink-vision): Spike-Skript fuer blinkpy-Login und Local-Storage-Manifest"
```

---

### Task 2: `EntitySource.VISION`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`

- [ ] **Step 1: Enum-Wert ergänzen**

Nach dem Eintrag `NUKI,`:

```java
    /** Blink-Gesichtserkennung (blink-vision-Sidecar). */
    VISION,
```

- [ ] **Step 2: Backend kompilieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile
```

Erwartet: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntitySource.java
git commit -m "feat(vision): EntitySource.VISION fuer die Blink-Gesichtserkennung"
```

---

### Task 3: Liquibase-Changeset + JPA-Entities + Repositories

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260722-0037-create-vision-tables.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (Include ans Ende)
- Create: `backend/src/main/java/com/household/manager/model/entity/VisionPerson.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/VisionPersonPhoto.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/VisionRecognition.java`
- Create: `backend/src/main/java/com/household/manager/repository/VisionPersonRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/VisionPersonPhotoRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/VisionRecognitionRepository.java`

**WICHTIG:** Repositories MÜSSEN in `com.household.manager.repository` liegen (JpaConfig schränkt das Scanning auf dieses Package ein).

- [ ] **Step 1: Changeset schreiben** (`20260722-0037-create-vision-tables.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260722-0037" author="household-manager">
        <comment>Create vision_person, vision_person_photo and vision_recognition tables</comment>

        <createTable tableName="vision_person">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(255)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="active" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createTable tableName="vision_person_photo">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="person_id" type="BIGINT">
                <constraints nullable="false"
                             foreignKeyName="fk_vision_photo_person"
                             references="vision_person(id)"/>
            </column>
            <column name="photo" type="MEDIUMBLOB">
                <constraints nullable="false"/>
            </column>
            <column name="embedding" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createTable tableName="vision_recognition">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="recognized_at" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="person_id" type="BIGINT"/>
            <column name="person_name" type="VARCHAR(255)"/>
            <column name="confidence" type="DECIMAL(5,4)"/>
            <column name="unknown_faces" type="INT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="thumbnail" type="MEDIUMBLOB"/>
        </createTable>

        <rollback>
            <dropTable tableName="vision_recognition"/>
            <dropTable tableName="vision_person_photo"/>
            <dropTable tableName="vision_person"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Include in `db.changelog-master.xml` ergänzen** (ans Ende, vor `</databaseChangeLog>`)

```xml
    <!-- Blink-Gesichtserkennung -->
    <include file="db/changelog/changes/20260722-0037-create-vision-tables.xml"/>
```

- [ ] **Step 3: Entities schreiben**

`VisionPerson.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Registrierter Bewohner fuer die Gesichtserkennung. */
@Entity
@Table(name = "vision_person")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    /** Inaktive Personen bleiben erhalten, werden aber nicht mehr erkannt. */
    @Column(name = "active", nullable = false)
    private boolean active;

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

`VisionPersonPhoto.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Referenzfoto eines Bewohners inkl. vom Sidecar berechnetem Embedding (JSON-Float-Array). */
@Entity
@Table(name = "vision_person_photo")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionPersonPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Lob
    @Column(name = "photo", nullable = false)
    private byte[] photo;

    /** Gesichts-Embedding als JSON-Array (z. B. "[0.01, -0.2, ...]"), Quelle: Sidecar. */
    @Column(name = "embedding", nullable = false, columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

`VisionRecognition.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Historieneintrag einer Gesichtserkennung (person* null = nur Unbekannte gesehen). */
@Entity
@Table(name = "vision_recognition")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionRecognition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recognized_at", nullable = false)
    private LocalDateTime recognizedAt;

    @Column(name = "person_id")
    private Long personId;

    /** Name zum Erkennungszeitpunkt (Snapshot, uebersteht Umbenennen/Loeschen). */
    @Column(name = "person_name", length = 255)
    private String personName;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "unknown_faces", nullable = false)
    private int unknownFaces;

    @Lob
    @Column(name = "thumbnail")
    private byte[] thumbnail;
}
```

- [ ] **Step 4: Repositories schreiben**

`VisionPersonRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.VisionPerson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisionPersonRepository extends JpaRepository<VisionPerson, Long> {
    List<VisionPerson> findAllByOrderByNameAsc();
    List<VisionPerson> findByActiveTrueOrderByNameAsc();
}
```

`VisionPersonPhotoRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.VisionPersonPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisionPersonPhotoRepository extends JpaRepository<VisionPersonPhoto, Long> {
    List<VisionPersonPhoto> findByPersonId(Long personId);
    void deleteByPersonId(Long personId);
}
```

`VisionRecognitionRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.VisionRecognition;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisionRecognitionRepository extends JpaRepository<VisionRecognition, Long> {
    List<VisionRecognition> findAllByOrderByRecognizedAtDesc(Pageable pageable);
}
```

- [ ] **Step 5: Kompilieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/Vision*.java backend/src/main/java/com/household/manager/repository/Vision*.java
git commit -m "feat(vision): Tabellen, Entities und Repositories fuer Personen und Erkennungen"
```

---

### Task 4: `VisionProperties`, `VisionException`, `VisionSidecarClient`

**Files:**
- Create: `backend/src/main/java/com/household/manager/vision/VisionProperties.java`
- Create: `backend/src/main/java/com/household/manager/vision/VisionException.java`
- Create: `backend/src/main/java/com/household/manager/vision/VisionSidecarClient.java`
- Create: `backend/src/test/java/com/household/manager/vision/VisionSidecarClientTest.java`
- Modify: `backend/src/main/resources/application.properties` (vision-Properties)

- [ ] **Step 1: `VisionProperties.java`**

```java
package com.household.manager.vision;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Konfiguration der Blink-Gesichtserkennung (blink-vision-Sidecar). */
@Component
@ConfigurationProperties(prefix = "vision")
@Getter
@Setter
public class VisionProperties {

    /** Integration aktiv (Heartbeat-Ueberwachung, Sidecar-Aufrufe). */
    private boolean enabled = true;

    /** Basis-URL des blink-vision-Sidecars. */
    private String sidecarBaseUrl = "http://localhost:8090";

    /** Nach so vielen Sekunden ohne Heartbeat gilt der Sidecar als offline. */
    private long heartbeatTimeoutSeconds = 180;
}
```

- [ ] **Step 2: `VisionException.java`**

```java
package com.household.manager.vision;

/** Fachlicher Fehler der Vision-Integration (Sidecar nicht erreichbar, Foto unbrauchbar, ...). */
public class VisionException extends RuntimeException {

    public VisionException(String message) {
        super(message);
    }

    public VisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: Failing Test schreiben** (`VisionSidecarClientTest.java`) — testet die Parse-Helfer, nicht HTTP:

```java
package com.household.manager.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisionSidecarClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesEmbeddingResponse() throws Exception {
        var json = mapper.readTree("{\"embedding\":[0.5,-0.25,0.0],\"faces\":1,\"ignoredField\":true}");
        float[] embedding = VisionSidecarClient.parseEmbedding(json);
        assertThat(embedding).containsExactly(0.5f, -0.25f, 0.0f);
    }

    @Test
    void embeddingResponseWithoutFaceThrows() throws Exception {
        var json = mapper.readTree("{\"embedding\":null,\"faces\":0}");
        org.junit.jupiter.api.Assertions.assertThrows(VisionException.class,
                () -> VisionSidecarClient.parseEmbedding(json));
    }

    @Test
    void parsesStatusResponse() throws Exception {
        var json = mapper.readTree(
                "{\"loggedIn\":true,\"cameraFound\":true,\"cameraName\":\"Haustuer\",\"lastPollAt\":\"2026-07-22T10:00:00Z\",\"extra\":1}");
        VisionSidecarClient.SidecarStatus status = VisionSidecarClient.parseStatus(json);
        assertThat(status.loggedIn()).isTrue();
        assertThat(status.cameraFound()).isTrue();
        assertThat(status.cameraName()).isEqualTo("Haustuer");
    }

    @Test
    void buildsPersonsPayload() throws Exception {
        var payload = VisionSidecarClient.buildPersonsPayload(mapper, List.of(
                new VisionSidecarClient.SidecarPerson(1L, "Benedikt", List.of(new float[]{0.1f, 0.2f}))));
        assertThat(payload.get(0).get("personId").asLong()).isEqualTo(1L);
        assertThat(payload.get(0).get("name").asText()).isEqualTo("Benedikt");
        assertThat(payload.get(0).get("embeddings").get(0).get(1).floatValue()).isEqualTo(0.2f);
    }
}
```

- [ ] **Step 4: Test ausführen — muss fehlschlagen (Klasse existiert nicht)**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VisionSidecarClientTest
```

Erwartet: Kompilierfehler „cannot find symbol VisionSidecarClient“.

- [ ] **Step 5: `VisionSidecarClient.java` schreiben** — HTTP-Gerüst analog `AlexaSidecarClient`, Parsing in testbaren statischen Helfern:

```java
package com.household.manager.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * HTTP-Client fuer den blink-vision-Sidecar. Der Sidecar kapselt den kompletten
 * Blink-/InsightFace-Teil; dieses Backend ruft ihn nur ueber eine schlanke HTTP-API auf.
 */
@Service
@Slf4j
public class VisionSidecarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final VisionProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public VisionSidecarClient(VisionProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Login-/Kamerastatus laut Sidecar. */
    public record SidecarStatus(boolean loggedIn, boolean cameraFound, String cameraName, String lastPollAt) {}

    /** Eine Person mit ihren Referenz-Embeddings (Payload fuer den Sidecar). */
    public record SidecarPerson(Long personId, String name, List<float[]> embeddings) {}

    public SidecarStatus getStatus() {
        return parseStatus(get("/status"));
    }

    /** Startet den Blink-Login (E-Mail/Passwort); danach folgt i. d. R. ein 2FA-Verify. */
    public void login(String username, String password) {
        ObjectNode body = mapper.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        post("/auth/login", body);
    }

    /** Schliesst den Login mit der 2FA-PIN ab. */
    public void verify(String code) {
        ObjectNode body = mapper.createObjectNode();
        body.put("code", code);
        post("/auth/verify", body);
    }

    /** Berechnet das Embedding fuer ein Referenzfoto (JPEG/PNG-Bytes). */
    public float[] computeEmbedding(byte[] photo) {
        ObjectNode body = mapper.createObjectNode();
        body.put("imageBase64", Base64.getEncoder().encodeToString(photo));
        return parseEmbedding(post("/embeddings", body));
    }

    /** Pusht die vollstaendige Personenliste (Embeddings) an den Sidecar. */
    public void pushPersons(List<SidecarPerson> persons) {
        put("/persons", buildPersonsPayload(mapper, persons));
    }

    // ==================== Parsing (testbar) ====================

    static SidecarStatus parseStatus(JsonNode root) {
        return new SidecarStatus(
                root.path("loggedIn").asBoolean(false),
                root.path("cameraFound").asBoolean(false),
                root.path("cameraName").asText(null),
                root.path("lastPollAt").asText(null));
    }

    static float[] parseEmbedding(JsonNode root) {
        JsonNode embedding = root.path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new VisionException("Auf dem Foto wurde kein Gesicht erkannt.");
        }
        float[] values = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            values[i] = (float) embedding.get(i).asDouble();
        }
        return values;
    }

    static ArrayNode buildPersonsPayload(ObjectMapper mapper, List<SidecarPerson> persons) {
        ArrayNode payload = mapper.createArrayNode();
        for (SidecarPerson person : persons) {
            ObjectNode node = payload.addObject();
            node.put("personId", person.personId());
            node.put("name", person.name());
            ArrayNode embeddings = node.putArray("embeddings");
            for (float[] embedding : person.embeddings()) {
                ArrayNode values = embeddings.addArray();
                for (float value : embedding) {
                    values.add(value);
                }
            }
        }
        return payload;
    }

    // ==================== HTTP ====================

    private JsonNode get(String path) {
        return send("GET", path, null);
    }

    private JsonNode post(String path, JsonNode body) {
        return send("POST", path, body);
    }

    private JsonNode put(String path, JsonNode body) {
        return send("PUT", path, body);
    }

    private JsonNode send(String method, String path, JsonNode body) {
        String url = properties.getSidecarBaseUrl() + path;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json");
            if ("GET".equals(method)) {
                req.GET();
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                body == null ? "{}" : mapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new VisionException("blink-vision " + path + " HTTP " + response.statusCode()
                        + ": " + extractError(response.body()));
            }
            if (response.body() == null || response.body().isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(response.body());
        } catch (VisionException ex) {
            throw ex;
        } catch (java.net.ConnectException ex) {
            throw new VisionException("blink-vision-Sidecar ist nicht erreichbar (" + url + "). Laeuft der Dienst?", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new VisionException("blink-vision-Kommunikation unterbrochen.", ex);
        } catch (Exception ex) {
            throw new VisionException("blink-vision-Kommunikation fehlgeschlagen: " + path, ex);
        }
    }

    private String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return mapper.readTree(body).path("error").asText(body);
        } catch (Exception ex) {
            return body;
        }
    }
}
```

- [ ] **Step 6: Properties in `application.properties` ergänzen**

```properties
# Blink-Gesichtserkennung (blink-vision-Sidecar)
vision.enabled=true
vision.sidecar-base-url=${VISION_SIDECAR_URL:http://localhost:8090}
vision.heartbeat-timeout-seconds=180
```

- [ ] **Step 7: Test ausführen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VisionSidecarClientTest
```

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/vision backend/src/test/java/com/household/manager/vision backend/src/main/resources/application.properties
git commit -m "feat(vision): Properties, Exception und Sidecar-HTTP-Client"
```

---

### Task 5: `VisionPersonService` (CRUD, Foto→Embedding, Embedding-Push)

**Files:**
- Create: `backend/src/main/java/com/household/manager/vision/VisionPersonService.java`
- Create: `backend/src/test/java/com/household/manager/vision/VisionPersonServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben**

```java
package com.household.manager.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.VisionPerson;
import com.household.manager.model.entity.VisionPersonPhoto;
import com.household.manager.repository.VisionPersonPhotoRepository;
import com.household.manager.repository.VisionPersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisionPersonServiceTest {

    @Mock
    private VisionPersonRepository personRepository;
    @Mock
    private VisionPersonPhotoRepository photoRepository;
    @Mock
    private VisionSidecarClient sidecarClient;

    private VisionPersonService service;

    @BeforeEach
    void setUp() {
        service = new VisionPersonService(personRepository, photoRepository, sidecarClient, new ObjectMapper());
    }

    @Test
    void addPhotoComputesEmbeddingAndPushesPersons() {
        VisionPerson person = VisionPerson.builder().id(1L).name("Benedikt").active(true).build();
        when(personRepository.findById(1L)).thenReturn(Optional.of(person));
        when(sidecarClient.computeEmbedding(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(photoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(personRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(person));
        when(photoRepository.findByPersonId(1L)).thenReturn(List.of(
                VisionPersonPhoto.builder().personId(1L).embedding("[0.1,0.2]").build()));

        service.addPhoto(1L, new byte[]{1, 2, 3});

        ArgumentCaptor<VisionPersonPhoto> captor = ArgumentCaptor.forClass(VisionPersonPhoto.class);
        verify(photoRepository).save(captor.capture());
        assertThat(captor.getValue().getEmbedding()).isEqualTo("[0.1,0.2]");
        verify(sidecarClient).pushPersons(anyList());
    }

    @Test
    void addPhotoForUnknownPersonThrows() {
        when(personRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(VisionException.class, () -> service.addPhoto(99L, new byte[]{1}));
        verifyNoInteractions(sidecarClient);
    }

    @Test
    void pushFailureDoesNotRollBackPhoto() {
        VisionPerson person = VisionPerson.builder().id(1L).name("Benedikt").active(true).build();
        when(personRepository.findById(1L)).thenReturn(Optional.of(person));
        when(sidecarClient.computeEmbedding(any())).thenReturn(new float[]{0.1f});
        when(photoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(personRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(person));
        when(photoRepository.findByPersonId(1L)).thenReturn(List.of());
        doThrow(new VisionException("Sidecar weg")).when(sidecarClient).pushPersons(anyList());

        service.addPhoto(1L, new byte[]{1});  // darf NICHT werfen

        verify(photoRepository).save(any());
    }

    @Test
    void deletePersonRemovesPhotosAndPushes() {
        when(personRepository.existsById(1L)).thenReturn(true);
        when(personRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of());

        service.deletePerson(1L);

        verify(photoRepository).deleteByPersonId(1L);
        verify(personRepository).deleteById(1L);
        verify(sidecarClient).pushPersons(anyList());
    }

    @Test
    void embeddingsPayloadSkipsUnparseableEmbedding() {
        VisionPerson person = VisionPerson.builder().id(1L).name("Benedikt").active(true).build();
        when(personRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(person));
        when(photoRepository.findByPersonId(1L)).thenReturn(List.of(
                VisionPersonPhoto.builder().personId(1L).embedding("kaputt").build(),
                VisionPersonPhoto.builder().personId(1L).embedding("[0.5]").build()));

        List<VisionSidecarClient.SidecarPerson> payload = service.buildSidecarPersons();

        assertThat(payload).hasSize(1);
        assertThat(payload.get(0).embeddings()).hasSize(1);
        assertThat(payload.get(0).embeddings().get(0)).containsExactly(0.5f);
    }
}
```

- [ ] **Step 2: Test ausführen — Kompilierfehler erwartet**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VisionPersonServiceTest
```

- [ ] **Step 3: `VisionPersonService.java` schreiben**

```java
package com.household.manager.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.VisionPerson;
import com.household.manager.model.entity.VisionPersonPhoto;
import com.household.manager.repository.VisionPersonPhotoRepository;
import com.household.manager.repository.VisionPersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Verwaltung der Bewohner und Referenzfotos. Das Backend ist fuehrend;
 * der Sidecar bekommt nach jeder Aenderung die komplette Embedding-Liste gepusht
 * (best effort — ein nicht erreichbarer Sidecar blockiert keine Verwaltung,
 * er holt sich die Liste beim Start selbst ueber GET /v1/vision/embeddings).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisionPersonService {

    private final VisionPersonRepository personRepository;
    private final VisionPersonPhotoRepository photoRepository;
    private final VisionSidecarClient sidecarClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<VisionPerson> getAll() {
        return personRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<VisionPersonPhoto> getPhotos(Long personId) {
        return photoRepository.findByPersonId(personId);
    }

    @Transactional
    public VisionPerson createPerson(String name) {
        VisionPerson person = personRepository.save(
                VisionPerson.builder().name(name.trim()).active(true).build());
        pushPersonsSafely();
        return person;
    }

    @Transactional
    public VisionPerson updatePerson(Long id, String name, boolean active) {
        VisionPerson person = personRepository.findById(id)
                .orElseThrow(() -> new VisionException("Person " + id + " ist unbekannt."));
        person.setName(name.trim());
        person.setActive(active);
        VisionPerson saved = personRepository.save(person);
        pushPersonsSafely();
        return saved;
    }

    @Transactional
    public void deletePerson(Long id) {
        if (!personRepository.existsById(id)) {
            throw new VisionException("Person " + id + " ist unbekannt.");
        }
        photoRepository.deleteByPersonId(id);
        personRepository.deleteById(id);
        pushPersonsSafely();
    }

    /** Foto speichern: Sidecar berechnet das Embedding, beides wird persistiert. */
    @Transactional
    public VisionPersonPhoto addPhoto(Long personId, byte[] photo) {
        personRepository.findById(personId)
                .orElseThrow(() -> new VisionException("Person " + personId + " ist unbekannt."));
        float[] embedding = sidecarClient.computeEmbedding(photo);
        VisionPersonPhoto saved = photoRepository.save(VisionPersonPhoto.builder()
                .personId(personId)
                .photo(photo)
                .embedding(toJson(embedding))
                .build());
        pushPersonsSafely();
        return saved;
    }

    @Transactional
    public void deletePhoto(Long photoId) {
        photoRepository.deleteById(photoId);
        pushPersonsSafely();
    }

    /** Personen + Embeddings im Sidecar-Format (auch fuer GET /v1/vision/embeddings). */
    @Transactional(readOnly = true)
    public List<VisionSidecarClient.SidecarPerson> buildSidecarPersons() {
        List<VisionSidecarClient.SidecarPerson> result = new ArrayList<>();
        for (VisionPerson person : personRepository.findByActiveTrueOrderByNameAsc()) {
            List<float[]> embeddings = new ArrayList<>();
            for (VisionPersonPhoto photo : photoRepository.findByPersonId(person.getId())) {
                float[] embedding = fromJson(photo.getEmbedding());
                if (embedding != null) {
                    embeddings.add(embedding);
                }
            }
            result.add(new VisionSidecarClient.SidecarPerson(person.getId(), person.getName(), embeddings));
        }
        return result;
    }

    private void pushPersonsSafely() {
        try {
            sidecarClient.pushPersons(buildSidecarPersons());
        } catch (Exception ex) {
            log.warn("Embedding-Push an blink-vision fehlgeschlagen (Sidecar holt sie beim Start selbst): {}",
                    ex.getMessage());
        }
    }

    private String toJson(float[] embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception ex) {
            throw new VisionException("Embedding konnte nicht serialisiert werden.", ex);
        }
    }

    private float[] fromJson(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception ex) {
            log.warn("Unlesbares Embedding wird uebersprungen: {}", ex.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 4: Tests ausführen — grün**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VisionPersonServiceTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/vision/VisionPersonService.java backend/src/test/java/com/household/manager/vision/VisionPersonServiceTest.java
git commit -m "feat(vision): Personenverwaltung mit Foto-Upload und Embedding-Push"
```

---

### Task 6: `VisionRecognitionService` (Webhook, Entity-Event, Heartbeat/unavailable)

**Files:**
- Create: `backend/src/main/java/com/household/manager/vision/VisionRecognitionService.java`
- Create: `backend/src/test/java/com/household/manager/vision/VisionRecognitionServiceTest.java`

Entity-ID: `EntityIds.build(EntityDomain.EVENT, EntitySource.VISION, "blink door", "person")` → **`event.vision_blink_door_person`**.

- [ ] **Step 1: Failing Tests schreiben**

```java
package com.household.manager.vision;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.VisionRecognition;
import com.household.manager.repository.VisionRecognitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisionRecognitionServiceTest {

    @Mock
    private VisionRecognitionRepository repository;
    @Mock
    private EntityStateService entityStateService;

    private final Instant now = Instant.parse("2026-07-22T10:00:00Z");
    private VisionRecognitionService service;

    @BeforeEach
    void setUp() {
        VisionProperties properties = new VisionProperties();
        service = new VisionRecognitionService(repository, entityStateService, properties,
                Clock.fixed(now, ZoneId.of("Europe/Berlin")));
    }

    @Test
    void recognitionIsPersistedAndFiredAsEvent() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processRecognition(new VisionRecognitionService.RecognitionReport(
                List.of(new VisionRecognitionService.RecognizedPerson(1L, "Benedikt", new BigDecimal("0.7234"))),
                0, new byte[]{9}));

        ArgumentCaptor<VisionRecognition> saved = ArgumentCaptor.forClass(VisionRecognition.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPersonName()).isEqualTo("Benedikt");

        ArgumentCaptor<EntityStateUpdate> update = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(update.capture());
        assertThat(update.getValue().entityId()).isEqualTo("event.vision_blink_door_person");
        assertThat(update.getValue().domain()).isEqualTo(EntityDomain.EVENT);
        assertThat(update.getValue().source()).isEqualTo(EntitySource.VISION);
        assertThat(update.getValue().state()).isEqualTo("Benedikt");
        assertThat(update.getValue().attributes()).containsEntry("personId", 1L);
    }

    @Test
    void unknownOnlyRecognitionFiresUnknownEvent() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processRecognition(new VisionRecognitionService.RecognitionReport(List.of(), 2, null));

        ArgumentCaptor<EntityStateUpdate> update = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(update.capture());
        assertThat(update.getValue().state()).isEqualTo("unknown");
        assertThat(update.getValue().attributes()).containsEntry("unknownFaces", 2);
    }

    @Test
    void persistFailureStillFiresEvent() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB weg"));

        service.processRecognition(new VisionRecognitionService.RecognitionReport(
                List.of(new VisionRecognitionService.RecognizedPerson(1L, "Benedikt", new BigDecimal("0.7"))),
                0, null));

        verify(entityStateService).reportEvent(any());
    }

    @Test
    void staleHeartbeatMarksEntityUnavailable() {
        service.heartbeat();
        service.checkHeartbeat();  // frisch -> nichts
        verify(entityStateService, never()).reportState(any());

        service = advanceBeyondTimeout(service);
        service.checkHeartbeat();

        ArgumentCaptor<EntityStateUpdate> update = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(update.capture());
        assertThat(update.getValue().state()).isEqualTo("unavailable");
    }

    /** Baut den Service mit einer Uhr jenseits des Heartbeat-Timeouts neu auf. */
    private VisionRecognitionService advanceBeyondTimeout(VisionRecognitionService old) {
        VisionProperties properties = new VisionProperties();
        VisionRecognitionService fresh = new VisionRecognitionService(repository, entityStateService, properties,
                Clock.fixed(now.plusSeconds(properties.getHeartbeatTimeoutSeconds() + 1), ZoneId.of("Europe/Berlin")));
        fresh.heartbeatAt(now);  // letzter Heartbeat liegt in der Vergangenheit
        return fresh;
    }
}
```

- [ ] **Step 2: Test ausführen — Kompilierfehler erwartet**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VisionRecognitionServiceTest
```

- [ ] **Step 3: `VisionRecognitionService.java` schreiben**

```java
package com.household.manager.vision;

import com.household.manager.entitystate.*;
import com.household.manager.model.entity.VisionRecognition;
import com.household.manager.repository.VisionRecognitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verarbeitet Erkennungs-Webhooks des blink-vision-Sidecars: persistiert die Historie
 * und feuert pro Meldung ein Entity-Event (event.vision_blink_door_person).
 * Hook-Muster: Persistenz- und Event-Fehler reissen einander nicht mit.
 * Ausbleibender Heartbeat setzt die Entitaet auf "unavailable".
 */
@Service
@Slf4j
public class VisionRecognitionService {

    static final String SOURCE_REF = "blink door";
    static final String STATE_UNKNOWN = "unknown";
    static final String STATE_UNAVAILABLE = "unavailable";

    /** Erkennungs-Meldung des Sidecars. */
    public record RecognitionReport(List<RecognizedPerson> persons, int unknownFaces, byte[] thumbnail) {}

    /** Eine erkannte Person mit Konfidenz (0..1). */
    public record RecognizedPerson(Long personId, String name, BigDecimal confidence) {}

    private final VisionRecognitionRepository repository;
    private final EntityStateService entityStateService;
    private final VisionProperties properties;
    private final Clock clock;
    private final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>();
    private final AtomicReference<Boolean> reportedUnavailable = new AtomicReference<>(false);

    public VisionRecognitionService(VisionRecognitionRepository repository,
                                    EntityStateService entityStateService,
                                    VisionProperties properties,
                                    Clock clock) {
        this.repository = repository;
        this.entityStateService = entityStateService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void processRecognition(RecognitionReport report) {
        heartbeat();
        persistSafely(report);
        fireEventSafely(report);
    }

    public void heartbeat() {
        heartbeatAt(clock.instant());
    }

    void heartbeatAt(Instant instant) {
        lastHeartbeat.set(instant);
        reportedUnavailable.set(false);
    }

    @Scheduled(fixedDelayString = "${vision.heartbeat-check-ms:60000}")
    public void checkHeartbeat() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant last = lastHeartbeat.get();
        if (last == null || reportedUnavailable.get()) {
            return;
        }
        Duration timeout = Duration.ofSeconds(properties.getHeartbeatTimeoutSeconds());
        if (last.isBefore(clock.instant().minus(timeout))) {
            log.warn("blink-vision-Sidecar sendet keinen Heartbeat mehr, Entitaet geht auf unavailable");
            reportedUnavailable.set(true);
            entityStateService.reportState(baseUpdate(STATE_UNAVAILABLE, Map.of()));
        }
    }

    @Transactional(readOnly = true)
    public List<VisionRecognition> getRecent(int limit) {
        return repository.findAllByOrderByRecognizedAtDesc(PageRequest.of(0, limit));
    }

    private void persistSafely(RecognitionReport report) {
        try {
            RecognizedPerson best = bestPerson(report);
            repository.save(VisionRecognition.builder()
                    .recognizedAt(LocalDateTime.now(clock))
                    .personId(best == null ? null : best.personId())
                    .personName(best == null ? null : best.name())
                    .confidence(best == null ? null : best.confidence())
                    .unknownFaces(report.unknownFaces())
                    .thumbnail(report.thumbnail())
                    .build());
        } catch (Exception ex) {
            log.warn("Erkennung konnte nicht persistiert werden: {}", ex.getMessage());
        }
    }

    private void fireEventSafely(RecognitionReport report) {
        try {
            RecognizedPerson best = bestPerson(report);
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("unknownFaces", report.unknownFaces());
            String state = STATE_UNKNOWN;
            if (best != null) {
                state = best.name();
                attributes.put("personId", best.personId());
                attributes.put("confidence", best.confidence());
            }
            entityStateService.reportEvent(baseUpdate(state, attributes));
        } catch (Exception ex) {
            log.warn("Erkennungs-Event konnte nicht gefeuert werden: {}", ex.getMessage());
        }
    }

    private RecognizedPerson bestPerson(RecognitionReport report) {
        return report.persons().stream()
                .max(java.util.Comparator.comparing(RecognizedPerson::confidence))
                .orElse(null);
    }

    private EntityStateUpdate baseUpdate(String state, Map<String, Object> attributes) {
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.EVENT, EntitySource.VISION, SOURCE_REF, "person"))
                .domain(EntityDomain.EVENT)
                .source(EntitySource.VISION)
                .sourceRef(SOURCE_REF)
                .friendlyName("Haustür Gesichtserkennung")
                .state(state)
                .attributes(attributes)
                .build();
    }
}
```

- [ ] **Step 4: Tests ausführen — grün**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VisionRecognitionServiceTest
```

Hinweis: Es muss eine `Clock`-Bean geben (die Tablet-Integration nutzt bereits eine — prüfen mit `grep -r "Clock" backend/src/main/java/com/household/manager/config`). Falls keine existiert: `@Bean Clock clock() { return Clock.systemDefaultZone(); }` in einer bestehenden `@Configuration`-Klasse ergänzen.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/vision/VisionRecognitionService.java backend/src/test/java/com/household/manager/vision/VisionRecognitionServiceTest.java
git commit -m "feat(vision): Erkennungs-Webhook-Verarbeitung mit Entity-Event und Heartbeat"
```

---

### Task 7: `VisionController` + DTOs

**Files:**
- Create: `backend/src/main/java/com/household/manager/controller/VisionController.java`
- Create: `backend/src/main/java/com/household/manager/dto/VisionDtos.java`

- [ ] **Step 1: `VisionDtos.java`** (Requests/Responses kompakt in einer Datei)

```java
package com.household.manager.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** DTOs der Vision-REST-API (Personen, Erkennungen, Login, Sidecar-Webhook). */
public final class VisionDtos {

    private VisionDtos() {
    }

    public record PersonRequest(String name, Boolean active) {}

    public record PersonResponse(Long id, String name, boolean active, int photoCount) {}

    public record PhotoResponse(Long id, Long personId, String photoBase64) {}

    public record RecognitionResponse(Long id, LocalDateTime recognizedAt, Long personId, String personName,
                                      BigDecimal confidence, int unknownFaces, String thumbnailBase64) {}

    public record LoginRequest(String username, String password) {}

    public record VerifyRequest(String code) {}

    public record StatusResponse(boolean sidecarReachable, boolean loggedIn, boolean cameraFound,
                                 String cameraName, String lastPollAt) {}

    /** Webhook-Payload des Sidecars. */
    public record RecognitionWebhook(List<WebhookPerson> persons, Integer unknownFaces, String thumbnailBase64) {}

    public record WebhookPerson(Long personId, String name, BigDecimal confidence) {}

    /** Embedding-Liste fuer den Sidecar-Start. */
    public record EmbeddingsPerson(Long personId, String name, List<float[]> embeddings) {}
}
```

- [ ] **Step 2: `VisionController.java`**

```java
package com.household.manager.controller;

import com.household.manager.dto.VisionDtos.*;
import com.household.manager.vision.VisionPersonService;
import com.household.manager.vision.VisionRecognitionService;
import com.household.manager.vision.VisionSidecarClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** REST-API der Blink-Gesichtserkennung (Frontend + Sidecar-Webhooks). */
@RestController
@RequestMapping("/v1/vision")
@RequiredArgsConstructor
@Slf4j
public class VisionController {

    private final VisionPersonService personService;
    private final VisionRecognitionService recognitionService;
    private final VisionSidecarClient sidecarClient;

    // ==================== Frontend ====================

    @GetMapping("/persons")
    public List<PersonResponse> getPersons() {
        return personService.getAll().stream()
                .map(p -> new PersonResponse(p.getId(), p.getName(), p.isActive(),
                        personService.getPhotos(p.getId()).size()))
                .toList();
    }

    @PostMapping("/persons")
    @ResponseStatus(HttpStatus.CREATED)
    public PersonResponse createPerson(@RequestBody PersonRequest request) {
        var person = personService.createPerson(request.name());
        return new PersonResponse(person.getId(), person.getName(), person.isActive(), 0);
    }

    @PutMapping("/persons/{id}")
    public PersonResponse updatePerson(@PathVariable Long id, @RequestBody PersonRequest request) {
        var person = personService.updatePerson(id, request.name(),
                request.active() == null || request.active());
        return new PersonResponse(person.getId(), person.getName(), person.isActive(),
                personService.getPhotos(id).size());
    }

    @DeleteMapping("/persons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePerson(@PathVariable Long id) {
        personService.deletePerson(id);
    }

    @GetMapping("/persons/{id}/photos")
    public List<PhotoResponse> getPhotos(@PathVariable Long id) {
        return personService.getPhotos(id).stream()
                .map(photo -> new PhotoResponse(photo.getId(), photo.getPersonId(),
                        Base64.getEncoder().encodeToString(photo.getPhoto())))
                .toList();
    }

    @PostMapping("/persons/{id}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoResponse addPhoto(@PathVariable Long id, @RequestParam("file") MultipartFile file)
            throws java.io.IOException {
        var photo = personService.addPhoto(id, file.getBytes());
        return new PhotoResponse(photo.getId(), photo.getPersonId(),
                Base64.getEncoder().encodeToString(photo.getPhoto()));
    }

    @DeleteMapping("/persons/{personId}/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@PathVariable Long personId, @PathVariable Long photoId) {
        personService.deletePhoto(photoId);
    }

    @GetMapping("/recognitions")
    public List<RecognitionResponse> getRecognitions(@RequestParam(defaultValue = "50") int limit) {
        return recognitionService.getRecent(limit).stream()
                .map(r -> new RecognitionResponse(r.getId(), r.getRecognizedAt(), r.getPersonId(),
                        r.getPersonName(), r.getConfidence(), r.getUnknownFaces(),
                        r.getThumbnail() == null ? null : Base64.getEncoder().encodeToString(r.getThumbnail())))
                .toList();
    }

    @GetMapping("/status")
    public StatusResponse getStatus() {
        try {
            var status = sidecarClient.getStatus();
            return new StatusResponse(true, status.loggedIn(), status.cameraFound(),
                    status.cameraName(), status.lastPollAt());
        } catch (Exception ex) {
            log.warn("blink-vision-Status nicht abrufbar: {}", ex.getMessage());
            return new StatusResponse(false, false, false, null, null);
        }
    }

    @PostMapping("/auth/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(@RequestBody LoginRequest request) {
        sidecarClient.login(request.username(), request.password());
    }

    @PostMapping("/auth/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@RequestBody VerifyRequest request) {
        sidecarClient.verify(request.code());
    }

    // ==================== Sidecar → Backend ====================

    @PostMapping("/recognitions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportRecognition(@RequestBody RecognitionWebhook webhook) {
        List<VisionRecognitionService.RecognizedPerson> persons =
                Optional.ofNullable(webhook.persons()).orElse(List.of()).stream()
                        .map(p -> new VisionRecognitionService.RecognizedPerson(
                                p.personId(), p.name(),
                                p.confidence() == null ? BigDecimal.ZERO : p.confidence()))
                        .toList();
        byte[] thumbnail = webhook.thumbnailBase64() == null
                ? null : Base64.getDecoder().decode(webhook.thumbnailBase64());
        recognitionService.processRecognition(new VisionRecognitionService.RecognitionReport(
                persons, Optional.ofNullable(webhook.unknownFaces()).orElse(0), thumbnail));
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat() {
        recognitionService.heartbeat();
    }

    @GetMapping("/embeddings")
    public List<EmbeddingsPerson> getEmbeddings() {
        return personService.buildSidecarPersons().stream()
                .map(p -> new EmbeddingsPerson(p.personId(), p.name(), p.embeddings()))
                .toList();
    }
}
```

- [ ] **Step 3: Kompilieren + alle Vision-Tests**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="Vision*"
```

Hinweis: Prüfen, wie der GlobalExceptionHandler (`backend/.../exception/`) unbekannte RuntimeExceptions rendert, und `VisionException` dort analog zu `AlexaException`/`NukiException` registrieren (HTTP 502/400 mit Message), falls ein solcher Handler existiert.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/VisionController.java backend/src/main/java/com/household/manager/dto/VisionDtos.java
git commit -m "feat(vision): REST-API fuer Personen, Erkennungen, Login und Sidecar-Webhooks"
```

---

### Task 8: Sidecar-Kern — Matcher, Cooldown, Persons-Store (mit pytest)

**Files:**
- Create: `blink-vision/app/__init__.py` (leer)
- Create: `blink-vision/app/matcher.py`
- Create: `blink-vision/app/cooldown.py`
- Create: `blink-vision/app/persons.py`
- Create: `blink-vision/tests/__init__.py` (leer)
- Create: `blink-vision/tests/test_matcher.py`
- Create: `blink-vision/tests/test_cooldown.py`
- Modify: `blink-vision/requirements.txt`

- [ ] **Step 1: `requirements.txt` erweitern**

```
blinkpy>=0.23
aiohttp
fastapi
uvicorn[standard]
httpx
insightface
onnxruntime
opencv-python-headless
numpy
pytest
```

```bash
cd blink-vision && .venv/Scripts/pip install -r requirements.txt
```

(InsightFace/onnxruntime-Download dauert; das Modell `buffalo_s` wird beim ersten Lauf nach `~/.insightface` geladen.)

- [ ] **Step 2: Failing Tests schreiben**

`blink-vision/tests/test_matcher.py`:

```python
import numpy as np

from app.matcher import PersonEmbeddings, best_match


def unit(v):
    a = np.array(v, dtype=np.float32)
    return a / np.linalg.norm(a)


PERSONS = [
    PersonEmbeddings(person_id=1, name="Benedikt", embeddings=[unit([1, 0, 0]), unit([0.9, 0.1, 0])]),
    PersonEmbeddings(person_id=2, name="Partnerin", embeddings=[unit([0, 1, 0])]),
]


def test_matches_closest_person_above_threshold():
    match = best_match(unit([0.95, 0.05, 0]), PERSONS, threshold=0.5)
    assert match.person_id == 1
    assert match.name == "Benedikt"
    assert match.confidence > 0.9


def test_below_threshold_returns_no_person_but_confidence():
    match = best_match(unit([0, 0, 1]), PERSONS, threshold=0.5)
    assert match.person_id is None
    assert match.confidence < 0.5


def test_empty_person_list_returns_unknown():
    match = best_match(unit([1, 0, 0]), [], threshold=0.5)
    assert match.person_id is None
    assert match.confidence == 0.0
```

`blink-vision/tests/test_cooldown.py`:

```python
from app.cooldown import Cooldown


def test_first_report_allowed_then_blocked_within_window():
    t = [100.0]
    cd = Cooldown(seconds=120, clock=lambda: t[0])
    assert cd.allow("benedikt") is True
    t[0] = 150.0
    assert cd.allow("benedikt") is False


def test_allowed_again_after_window():
    t = [100.0]
    cd = Cooldown(seconds=120, clock=lambda: t[0])
    assert cd.allow("benedikt") is True
    t[0] = 221.0
    assert cd.allow("benedikt") is True


def test_keys_are_independent():
    t = [100.0]
    cd = Cooldown(seconds=120, clock=lambda: t[0])
    assert cd.allow("benedikt") is True
    assert cd.allow("partnerin") is True
```

- [ ] **Step 3: Tests ausführen — ImportError erwartet**

```bash
cd blink-vision && .venv/Scripts/python -m pytest tests/ -v
```

- [ ] **Step 4: Implementieren**

`blink-vision/app/matcher.py`:

```python
"""Vergleich von Gesichts-Embeddings gegen registrierte Personen.
Embeddings sind L2-normalisiert (InsightFace normed_embedding) -> Skalarprodukt = Cosine-Aehnlichkeit."""
from dataclasses import dataclass

import numpy as np


@dataclass
class PersonEmbeddings:
    person_id: int
    name: str
    embeddings: list  # list[np.ndarray]


@dataclass
class Match:
    person_id: int | None
    name: str | None
    confidence: float


def best_match(face_embedding: np.ndarray, persons: list[PersonEmbeddings], threshold: float) -> Match:
    best = Match(person_id=None, name=None, confidence=0.0)
    for person in persons:
        for ref in person.embeddings:
            similarity = float(np.dot(face_embedding, ref))
            if similarity > best.confidence:
                best = Match(person.person_id, person.name, similarity)
    if best.person_id is not None and best.confidence < threshold:
        return Match(person_id=None, name=None, confidence=best.confidence)
    return best
```

`blink-vision/app/cooldown.py`:

```python
"""Meldet denselben Schluessel (Person) hoechstens einmal pro Zeitfenster."""
import time


class Cooldown:
    def __init__(self, seconds: float, clock=time.monotonic):
        self._seconds = seconds
        self._clock = clock
        self._last: dict[str, float] = {}

    def allow(self, key: str) -> bool:
        now = self._clock()
        last = self._last.get(key)
        if last is not None and (now - last) < self._seconds:
            return False
        self._last[key] = now
        return True
```

`blink-vision/app/persons.py`:

```python
"""In-Memory-Store der Personen-Embeddings. Fuehrend ist das Backend:
gefuellt per PUT /persons (Push) oder beim Start via GET /v1/vision/embeddings (Pull)."""
import numpy as np

from app.matcher import PersonEmbeddings


class PersonStore:
    def __init__(self):
        self._persons: list[PersonEmbeddings] = []

    def replace(self, payload: list[dict]) -> None:
        persons = []
        for entry in payload:
            embeddings = [np.array(e, dtype=np.float32) for e in entry.get("embeddings", [])]
            embeddings = [e / np.linalg.norm(e) for e in embeddings if np.linalg.norm(e) > 0]
            persons.append(PersonEmbeddings(
                person_id=int(entry["personId"]), name=entry["name"], embeddings=embeddings))
        self._persons = persons

    def all(self) -> list[PersonEmbeddings]:
        return self._persons
```

- [ ] **Step 5: Tests ausführen — grün**

```bash
cd blink-vision && .venv/Scripts/python -m pytest tests/ -v
```

- [ ] **Step 6: Commit**

```bash
git add blink-vision/app blink-vision/tests blink-vision/requirements.txt
git commit -m "feat(blink-vision): Matcher, Cooldown und Personen-Store mit Tests"
```

---

### Task 9: Sidecar — Gesichtsanalyse (InsightFace) + Frame-Extraktion

**Files:**
- Create: `blink-vision/app/analyzer.py`

- [ ] **Step 1: `analyzer.py` schreiben**

```python
"""InsightFace-Wrapper: Embeddings aus Bildern/Videoclips (CPU, Modell buffalo_s)."""
import logging

import cv2
import numpy as np
from insightface.app import FaceAnalysis

log = logging.getLogger(__name__)

FRAME_STEP = 5       # jeder 5. Frame
MAX_FRAMES = 12      # Obergrenze pro Clip
THUMB_WIDTH = 320


class FaceAnalyzer:
    def __init__(self):
        self._app = FaceAnalysis(name="buffalo_s", providers=["CPUExecutionProvider"])
        self._app.prepare(ctx_id=-1, det_size=(640, 640))

    def embeddings_from_image(self, image_bytes: bytes) -> list[np.ndarray]:
        """Alle Gesichts-Embeddings eines Einzelbilds (fuer Referenzfotos)."""
        img = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            return []
        return [f.normed_embedding for f in self._app.get(img)]

    def analyze_clip(self, clip_path: str) -> tuple[list[np.ndarray], bytes | None]:
        """Alle Gesichts-Embeddings ueber ausgewaehlte Frames eines Clips
        plus JPEG-Thumbnail des Frames mit den meisten Gesichtern."""
        embeddings: list[np.ndarray] = []
        best_frame = None
        best_face_count = 0
        capture = cv2.VideoCapture(clip_path)
        try:
            index = 0
            used = 0
            while used < MAX_FRAMES:
                ok, frame = capture.read()
                if not ok:
                    break
                if index % FRAME_STEP == 0:
                    used += 1
                    faces = self._app.get(frame)
                    embeddings.extend(f.normed_embedding for f in faces)
                    if len(faces) > best_face_count:
                        best_face_count = len(faces)
                        best_frame = frame
                index += 1
        finally:
            capture.release()
        return embeddings, _to_thumbnail(best_frame)


def _to_thumbnail(frame) -> bytes | None:
    if frame is None:
        return None
    height = int(frame.shape[0] * THUMB_WIDTH / frame.shape[1])
    resized = cv2.resize(frame, (THUMB_WIDTH, height))
    ok, buffer = cv2.imencode(".jpg", resized, [cv2.IMWRITE_JPEG_QUALITY, 80])
    return buffer.tobytes() if ok else None
```

- [ ] **Step 2: Smoke-Test mit dem Probe-Clip aus Task 1**

```bash
cd blink-vision && .venv/Scripts/python -c "
from app.analyzer import FaceAnalyzer
a = FaceAnalyzer()
emb, thumb = a.analyze_clip('data/probe-clip.mp4')
print('Embeddings:', len(emb), 'Thumbnail:', 0 if thumb is None else len(thumb), 'Bytes')
"
```

Erwartet: läuft ohne Exception; Embedding-Anzahl > 0, wenn im Probe-Clip ein Gesicht zu sehen war.

- [ ] **Step 3: Commit**

```bash
git add blink-vision/app/analyzer.py
git commit -m "feat(blink-vision): InsightFace-Analyse fuer Referenzfotos und Clips"
```

---

### Task 10: Sidecar — Blink-Anbindung, Poll-Loop, FastAPI-App

**Files:**
- Create: `blink-vision/app/config.py`
- Create: `blink-vision/app/blink_client.py`
- Create: `blink-vision/app/backend_client.py`
- Create: `blink-vision/app/poller.py`
- Create: `blink-vision/app/main.py`
- Create: `blink-vision/tests/test_poller_dedupe.py`

**Hinweis:** Die exakten blinkpy-Aufrufe in `blink_client.py` wurden in Task 1 verifiziert — bei Abweichungen NUR diese Datei anpassen.

- [ ] **Step 1: `config.py`**

```python
"""Konfiguration aus Umgebungsvariablen."""
import os

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
CAMERA_NAME = os.environ.get("BLINK_CAMERA_NAME", "")   # leer = erste gefundene Kamera
POLL_SECONDS = int(os.environ.get("POLL_SECONDS", "10"))
HEARTBEAT_SECONDS = int(os.environ.get("HEARTBEAT_SECONDS", "60"))
CONFIDENCE_THRESHOLD = float(os.environ.get("CONFIDENCE_THRESHOLD", "0.5"))
COOLDOWN_SECONDS = int(os.environ.get("COOLDOWN_SECONDS", "120"))
DATA_DIR = os.environ.get("DATA_DIR", "./data")
PORT = int(os.environ.get("PORT", "8090"))
```

- [ ] **Step 2: Failing Test für Clip-Dedupe** (`tests/test_poller_dedupe.py`)

```python
from app.poller import ClipDedupe


def test_new_clip_ids_are_reported_once(tmp_path):
    state = tmp_path / "state.json"
    dedupe = ClipDedupe(state_file=str(state))
    assert dedupe.is_new("clip-1") is True
    dedupe.mark_processed("clip-1")
    assert dedupe.is_new("clip-1") is False


def test_state_survives_restart(tmp_path):
    state = tmp_path / "state.json"
    ClipDedupe(state_file=str(state)).mark_processed("clip-1")
    reloaded = ClipDedupe(state_file=str(state))
    assert reloaded.is_new("clip-1") is False
    assert reloaded.is_new("clip-2") is True


def test_old_ids_are_pruned(tmp_path):
    state = tmp_path / "state.json"
    dedupe = ClipDedupe(state_file=str(state), max_ids=3)
    for i in range(5):
        dedupe.mark_processed(f"clip-{i}")
    assert dedupe.is_new("clip-0") is True   # rausgealtert
    assert dedupe.is_new("clip-4") is False
```

```bash
cd blink-vision && .venv/Scripts/python -m pytest tests/test_poller_dedupe.py -v
```

Erwartet: ImportError.

- [ ] **Step 3: `blink_client.py`** (einzige Datei mit blinkpy-Wissen)

```python
"""Duenner Wrapper um blinkpy: Login (2FA), Kamera-Auswahl, neue Local-Storage-Clips.
Alle blinkpy-Spezifika leben HIER — verifiziert durch probe.py (Task 1)."""
import json
import logging
from pathlib import Path

from aiohttp import ClientSession
from blinkpy.blinkpy import Blink
from blinkpy.auth import Auth

log = logging.getLogger(__name__)


class BlinkClient:
    def __init__(self, data_dir: str, camera_name: str):
        self._creds = Path(data_dir) / "blink-session.json"
        self._camera_name = camera_name
        self._session: ClientSession | None = None
        self._blink: Blink | None = None
        self._pending_2fa = False

    @property
    def logged_in(self) -> bool:
        return self._blink is not None and not self._pending_2fa

    @property
    def pending_2fa(self) -> bool:
        return self._pending_2fa

    async def try_restore_session(self) -> bool:
        """Beim Start: gespeicherte Session laden, falls vorhanden."""
        if not self._creds.exists():
            return False
        try:
            self._session = ClientSession()
            self._blink = Blink(session=self._session)
            self._blink.auth = Auth(json.loads(self._creds.read_text()),
                                    no_prompt=True, session=self._session)
            await self._blink.start()
            return not self._blink.key_required
        except Exception as ex:
            log.warning("Blink-Session-Restore fehlgeschlagen: %s", ex)
            await self._close()
            return False

    async def login(self, username: str, password: str) -> None:
        """Start des Logins; bei 2FA bleibt der Client in pending_2fa."""
        await self._close()
        self._session = ClientSession()
        self._blink = Blink(session=self._session)
        self._blink.auth = Auth({"username": username, "password": password},
                                no_prompt=True, session=self._session)
        await self._blink.start()
        self._pending_2fa = bool(self._blink.key_required)
        if not self._pending_2fa:
            await self._blink.save(str(self._creds))

    async def verify(self, code: str) -> None:
        if self._blink is None:
            raise RuntimeError("Kein Login-Vorgang aktiv.")
        await self._blink.auth.send_auth_key(self._blink, code)
        await self._blink.setup_post_verify()
        self._pending_2fa = False
        await self._blink.save(str(self._creds))  # nur Token, keine Passwoerter

    def camera_name(self) -> str | None:
        if self._blink is None or not self._blink.cameras:
            return None
        if self._camera_name and self._camera_name in self._blink.cameras:
            return self._camera_name
        return next(iter(self._blink.cameras))

    async def fetch_new_clips(self, is_new, download_dir: str) -> list[tuple[str, str]]:
        """Liefert [(clip_id, lokaler_pfad)] fuer alle neuen Clips der Zielkamera.
        is_new: Callable[[str], bool] — Dedupe-Check des Aufrufers."""
        results: list[tuple[str, str]] = []
        if self._blink is None:
            return results
        camera = self.camera_name()
        for name, sync in self._blink.sync.items():
            if not getattr(sync, "local_storage", False):
                continue
            await sync.refresh()
            manifest = getattr(sync, "_local_storage", {}).get("manifest", [])
            for item in manifest:
                clip_id = str(item.id)
                if camera and item.name != camera:
                    continue
                if not is_new(clip_id):
                    continue
                path = str(Path(download_dir) / f"clip-{clip_id}.mp4")
                await item.prepare_download(self._blink)
                await item.download_video(self._blink, path)
                results.append((clip_id, path))
        return results

    async def _close(self):
        self._blink = None
        self._pending_2fa = False
        if self._session is not None:
            await self._session.close()
            self._session = None
```

- [ ] **Step 4: `backend_client.py`**

```python
"""HTTP-Client Richtung Spring-Backend (Webhook, Heartbeat, Embedding-Pull)."""
import base64
import logging

import httpx

from app import config

log = logging.getLogger(__name__)


async def post_recognition(persons: list[dict], unknown_faces: int, thumbnail: bytes | None) -> None:
    payload = {
        "persons": persons,
        "unknownFaces": unknown_faces,
        "thumbnailBase64": base64.b64encode(thumbnail).decode() if thumbnail else None,
    }
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(f"{config.BACKEND_URL}/v1/vision/recognitions", json=payload)
        response.raise_for_status()


async def post_heartbeat() -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.post(f"{config.BACKEND_URL}/v1/vision/heartbeat")
        response.raise_for_status()


async def fetch_embeddings() -> list[dict]:
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(f"{config.BACKEND_URL}/v1/vision/embeddings")
        response.raise_for_status()
        return response.json()
```

- [ ] **Step 5: `poller.py`**

```python
"""Poll-Loop: neue Clips -> Analyse -> Matching -> Webhook. Plus ClipDedupe (persistiert)."""
import asyncio
import json
import logging
import os
from pathlib import Path

from app import backend_client, config
from app.cooldown import Cooldown
from app.matcher import best_match

log = logging.getLogger(__name__)


class ClipDedupe:
    """Merkt sich verarbeitete Clip-IDs (Datei im Datenverzeichnis, begrenzte Groesse)."""

    def __init__(self, state_file: str, max_ids: int = 200):
        self._file = Path(state_file)
        self._max_ids = max_ids
        self._ids: list[str] = []
        if self._file.exists():
            try:
                self._ids = json.loads(self._file.read_text())
            except Exception:
                self._ids = []

    def is_new(self, clip_id: str) -> bool:
        return clip_id not in self._ids

    def mark_processed(self, clip_id: str) -> None:
        self._ids.append(clip_id)
        self._ids = self._ids[-self._max_ids:]
        self._file.parent.mkdir(parents=True, exist_ok=True)
        self._file.write_text(json.dumps(self._ids))


class Poller:
    def __init__(self, blink_client, analyzer, person_store):
        self._blink = blink_client
        self._analyzer = analyzer
        self._persons = person_store
        self._dedupe = ClipDedupe(os.path.join(config.DATA_DIR, "processed-clips.json"))
        self._cooldown = Cooldown(config.COOLDOWN_SECONDS)
        self.last_poll_at: str | None = None

    async def run_forever(self):
        heartbeat_due = 0.0
        while True:
            try:
                loop_time = asyncio.get_event_loop().time()
                if loop_time >= heartbeat_due:
                    await backend_client.post_heartbeat()
                    heartbeat_due = loop_time + config.HEARTBEAT_SECONDS
                if self._blink.logged_in:
                    await self._poll_once()
            except Exception as ex:
                log.warning("Poll-Durchlauf fehlgeschlagen: %s", ex)
            await asyncio.sleep(config.POLL_SECONDS)

    async def _poll_once(self):
        import datetime
        clips = await self._blink.fetch_new_clips(self._dedupe.is_new, config.DATA_DIR)
        self.last_poll_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
        for clip_id, path in clips:
            try:
                await self._process_clip(path)
            except Exception as ex:
                log.warning("Clip %s uebersprungen: %s", clip_id, ex)
            finally:
                self._dedupe.mark_processed(clip_id)
                Path(path).unlink(missing_ok=True)

    async def _process_clip(self, path: str):
        embeddings, thumbnail = await asyncio.to_thread(self._analyzer.analyze_clip, path)
        if not embeddings:
            return
        matched: dict[int, dict] = {}
        unknown = 0
        for embedding in embeddings:
            match = best_match(embedding, self._persons.all(), config.CONFIDENCE_THRESHOLD)
            if match.person_id is None:
                unknown += 1
            else:
                existing = matched.get(match.person_id)
                if existing is None or match.confidence > existing["confidence"]:
                    matched[match.person_id] = {
                        "personId": match.person_id,
                        "name": match.name,
                        "confidence": round(match.confidence, 4),
                    }
        persons = [p for p in matched.values() if self._cooldown.allow(str(p["personId"]))]
        if not persons and unknown == 0:
            return
        await backend_client.post_recognition(persons, unknown, thumbnail)
```

- [ ] **Step 6: `main.py` (FastAPI-App)**

```python
"""blink-vision-Sidecar: Blink-Clips -> Gesichtserkennung -> Backend-Webhook."""
import asyncio
import base64
import logging

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app import backend_client, config
from app.analyzer import FaceAnalyzer
from app.blink_client import BlinkClient
from app.persons import PersonStore
from app.poller import Poller

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

app = FastAPI(title="blink-vision")
blink = BlinkClient(config.DATA_DIR, config.CAMERA_NAME)
persons = PersonStore()
analyzer: FaceAnalyzer | None = None
poller: Poller | None = None


class LoginRequest(BaseModel):
    username: str
    password: str


class VerifyRequest(BaseModel):
    code: str


@app.on_event("startup")
async def startup():
    global analyzer, poller
    analyzer = FaceAnalyzer()
    try:
        persons.replace(await backend_client.fetch_embeddings())
    except Exception as ex:
        log.warning("Embeddings vom Backend nicht ladbar (kommt per Push): %s", ex)
    await blink.try_restore_session()
    poller = Poller(blink, analyzer, persons)
    asyncio.create_task(poller.run_forever())


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/status")
async def status():
    return {
        "loggedIn": blink.logged_in,
        "pending2fa": blink.pending_2fa,
        "cameraFound": blink.camera_name() is not None,
        "cameraName": blink.camera_name(),
        "lastPollAt": poller.last_poll_at if poller else None,
    }


@app.post("/auth/login")
async def login(request: LoginRequest):
    try:
        await blink.login(request.username, request.password)
    except Exception as ex:
        raise HTTPException(status_code=502, detail={"error": f"Blink-Login fehlgeschlagen: {ex}"})
    return {"pending2fa": blink.pending_2fa}


@app.post("/auth/verify")
async def verify(request: VerifyRequest):
    try:
        await blink.verify(request.code)
    except Exception as ex:
        raise HTTPException(status_code=502, detail={"error": f"2FA fehlgeschlagen: {ex}"})
    return {"loggedIn": blink.logged_in}


@app.post("/embeddings")
async def embeddings(payload: dict):
    image = base64.b64decode(payload["imageBase64"])
    result = await asyncio.to_thread(analyzer.embeddings_from_image, image)
    if not result:
        return {"embedding": None, "faces": 0}
    if len(result) > 1:
        raise HTTPException(status_code=400,
                            detail={"error": "Mehrere Gesichter auf dem Referenzfoto — bitte Einzelportraet."})
    return {"embedding": [float(x) for x in result[0]], "faces": 1}


@app.put("/persons")
async def put_persons(payload: list[dict]):
    persons.replace(payload)
    return {"count": len(payload)}
```

- [ ] **Step 7: Dedupe-Tests grün, App startet lokal**

```bash
cd blink-vision && .venv/Scripts/python -m pytest tests/ -v
```

```bash
cd blink-vision && BACKEND_URL=http://localhost:8080 .venv/Scripts/python -m uvicorn app.main:app --port 8090
```

Erwartet: startet; `curl http://localhost:8090/health` → `{"status":"ok"}`. (Backend darf dabei fehlen — Startup loggt nur eine Warnung.)

- [ ] **Step 8: Commit**

```bash
git add blink-vision/app blink-vision/tests
git commit -m "feat(blink-vision): Blink-Anbindung, Poll-Loop und FastAPI-Endpoints"
```

---

### Task 11: Sidecar-Dockerfile + docker-compose

**Files:**
- Create: `blink-vision/Dockerfile`
- Modify: `docker-compose.yml`

- [ ] **Step 1: `Dockerfile`**

```dockerfile
FROM python:3.12-slim

WORKDIR /app

# opencv-headless braucht libgl nicht, aber libglib
RUN apt-get update && apt-get install -y --no-install-recommends libglib2.0-0 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ app/

ENV DATA_DIR=/data
VOLUME /data

EXPOSE 8090
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
```

- [ ] **Step 2: `docker-compose.yml` erweitern**

Im Service `backend` unter `environment` (nach dem Nuki-Block):

```yaml
      # Blink-Gesichtserkennung (blink-vision-Sidecar)
      VISION_SIDECAR_URL: http://blink-vision:8090
```

Neuer Service (nach `alexa-sidecar`):

```yaml
  blink-vision:
    build:
      context: ./blink-vision
    restart: unless-stopped
    environment:
      BACKEND_URL: http://backend:8080
      BLINK_CAMERA_NAME: ${BLINK_CAMERA_NAME:-}
      CONFIDENCE_THRESHOLD: ${VISION_CONFIDENCE_THRESHOLD:-0.5}
      POLL_SECONDS: "10"
    volumes:
      - blink_vision_data:/data
    ports:
      - "8090:8090"
    networks:
      - app_net
```

Unter `volumes:` am Dateiende:

```yaml
  blink_vision_data:
```

- [ ] **Step 3: Compose-Syntax prüfen (Build erst beim Deployment auf dem Server)**

```bash
docker compose config -q
```

Erwartet: kein Output (= valide). Falls Docker lokal nicht läuft: Schritt notieren und beim Deployment prüfen.

- [ ] **Step 4: Commit**

```bash
git add blink-vision/Dockerfile docker-compose.yml
git commit -m "feat(deploy): blink-vision-Sidecar im Docker-Compose"
```

---

### Task 12: Frontend — Modelle + `VisionService` (mit Spec)

**Files:**
- Create: `frontend/src/app/models/vision.model.ts`
- Create: `frontend/src/app/services/vision.service.ts`
- Create: `frontend/src/app/services/vision.service.spec.ts`

- [ ] **Step 1: `vision.model.ts`**

```typescript
/** Modelle der Gesichtserkennung (Personen, Erkennungen, Status). */
export interface VisionPerson {
  id: number;
  name: string;
  active: boolean;
  photoCount: number;
}

export interface VisionPhoto {
  id: number;
  personId: number;
  photoBase64: string;
}

export interface VisionRecognition {
  id: number;
  recognizedAt: string;
  personId: number | null;
  personName: string | null;
  confidence: number | null;
  unknownFaces: number;
  thumbnailBase64: string | null;
}

export interface VisionStatus {
  sidecarReachable: boolean;
  loggedIn: boolean;
  cameraFound: boolean;
  cameraName: string | null;
  lastPollAt: string | null;
}
```

- [ ] **Step 2: Failing Spec schreiben** (`vision.service.spec.ts`)

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { VisionService } from './vision.service';

describe('VisionService', () => {
  let service: VisionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(VisionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt Personen', () => {
    service.getPersons().subscribe(persons => {
      expect(persons.length).toBe(1);
      expect(persons[0].name).toBe('Benedikt');
    });
    const req = httpMock.expectOne('/api/v1/vision/persons');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 1, name: 'Benedikt', active: true, photoCount: 2 }]);
  });

  it('laedt ein Foto als multipart hoch', () => {
    const file = new File([new Uint8Array([1, 2, 3])], 'foto.jpg', { type: 'image/jpeg' });
    service.uploadPhoto(1, file).subscribe();
    const req = httpMock.expectOne('/api/v1/vision/persons/1/photos');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBeTrue();
    req.flush({ id: 5, personId: 1, photoBase64: '' });
  });

  it('meldet Login-Daten an das Backend', () => {
    service.login('mail@example.com', 'geheim').subscribe();
    const req = httpMock.expectOne('/api/v1/vision/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'mail@example.com', password: 'geheim' });
    req.flush(null);
  });
});
```

- [ ] **Step 3: Spec ausführen — muss fehlschlagen**

```bash
cd frontend && npx ng test --watch=false --include="**/vision.service.spec.ts"
```

- [ ] **Step 4: `vision.service.ts` schreiben**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { VisionPerson, VisionPhoto, VisionRecognition, VisionStatus } from '../models/vision.model';

/** REST-Service der Gesichtserkennung (Personen, Fotos, Erkennungen, Blink-Login). */
@Injectable({ providedIn: 'root' })
export class VisionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/vision';

  getStatus(): Observable<VisionStatus> {
    return this.http.get<VisionStatus>(`${this.baseUrl}/status`).pipe(catchError(this.handleError));
  }

  login(username: string, password: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/login`, { username, password })
      .pipe(catchError(this.handleError));
  }

  verify(code: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/verify`, { code })
      .pipe(catchError(this.handleError));
  }

  getPersons(): Observable<VisionPerson[]> {
    return this.http.get<VisionPerson[]>(`${this.baseUrl}/persons`).pipe(catchError(this.handleError));
  }

  createPerson(name: string): Observable<VisionPerson> {
    return this.http.post<VisionPerson>(`${this.baseUrl}/persons`, { name })
      .pipe(catchError(this.handleError));
  }

  updatePerson(id: number, name: string, active: boolean): Observable<VisionPerson> {
    return this.http.put<VisionPerson>(`${this.baseUrl}/persons/${id}`, { name, active })
      .pipe(catchError(this.handleError));
  }

  deletePerson(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/persons/${id}`).pipe(catchError(this.handleError));
  }

  getPhotos(personId: number): Observable<VisionPhoto[]> {
    return this.http.get<VisionPhoto[]>(`${this.baseUrl}/persons/${personId}/photos`)
      .pipe(catchError(this.handleError));
  }

  uploadPhoto(personId: number, file: File): Observable<VisionPhoto> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<VisionPhoto>(`${this.baseUrl}/persons/${personId}/photos`, form)
      .pipe(catchError(this.handleError));
  }

  deletePhoto(personId: number, photoId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/persons/${personId}/photos/${photoId}`)
      .pipe(catchError(this.handleError));
  }

  getRecognitions(limit = 50): Observable<VisionRecognition[]> {
    return this.http.get<VisionRecognition[]>(`${this.baseUrl}/recognitions?limit=${limit}`)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Vision-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Gesichtserkennungs-Anfrage.'));
  }
}
```

- [ ] **Step 5: Spec grün**

```bash
cd frontend && npx ng test --watch=false --include="**/vision.service.spec.ts"
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/vision.model.ts frontend/src/app/services/vision.service.ts frontend/src/app/services/vision.service.spec.ts
git commit -m "feat(vision): Frontend-Service und Modelle fuer die Gesichtserkennung"
```

---

### Task 13: Frontend — Seite „Gesichtserkennung“ + Route + Navigation

**Files:**
- Create: `frontend/src/app/pages/vision/vision.component.ts`
- Create: `frontend/src/app/pages/vision/vision.component.html`
- Create: `frontend/src/app/pages/vision/vision.component.scss`
- Create: `frontend/src/app/pages/vision/vision.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts` (Nav-Eintrag unter „Admin“-Gruppe)

Die Seite hat drei Abschnitte: **Blink-Konto** (Status + Login-Formular inkl. 2FA-PIN), **Personen** (Liste, Anlegen, Fotos hochladen/löschen), **Letzte Erkennungen** (Tabelle mit Thumbnail, Name/Unbekannt, Konfidenz, Zeitpunkt). Vorbild für Struktur/Styling: `pages/announcements/` (gleiches Seitenlayout, gekapselte SCSS — KEINE lumina-Klassen).

- [ ] **Step 1: Failing Component-Spec schreiben** (`vision.component.spec.ts`)

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { VisionComponent } from './vision.component';

describe('VisionComponent', () => {
  let fixture: ComponentFixture<VisionComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VisionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(VisionComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  function flushInitialRequests(loggedIn: boolean): void {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/vision/status').flush({
      sidecarReachable: true, loggedIn, cameraFound: true, cameraName: 'Haustuer', lastPollAt: null
    });
    httpMock.expectOne('/api/v1/vision/persons').flush([
      { id: 1, name: 'Benedikt', active: true, photoCount: 3 }
    ]);
    httpMock.expectOne(r => r.url === '/api/v1/vision/recognitions').flush([]);
    fixture.detectChanges();
  }

  it('zeigt Personen mit Fotoanzahl', () => {
    flushInitialRequests(true);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Benedikt');
    expect(text).toContain('3');
  });

  it('zeigt das Login-Formular, wenn nicht angemeldet', () => {
    flushInitialRequests(false);
    const form = (fixture.nativeElement as HTMLElement).querySelector('.vision__login');
    expect(form).toBeTruthy();
  });
});
```

- [ ] **Step 2: Spec ausführen — muss fehlschlagen**

```bash
cd frontend && npx ng test --watch=false --include="**/vision.component.spec.ts"
```

- [ ] **Step 3: Komponente implementieren**

`vision.component.ts`:

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { VisionService } from '../../services/vision.service';
import { VisionPerson, VisionPhoto, VisionRecognition, VisionStatus } from '../../models/vision.model';

/** Seite "Gesichtserkennung": Blink-Konto, Personenverwaltung, Erkennungshistorie. */
@Component({
  selector: 'app-vision',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './vision.component.html',
  styleUrl: './vision.component.scss'
})
export class VisionComponent implements OnInit {
  private readonly visionService = inject(VisionService);

  status: VisionStatus | null = null;
  persons: VisionPerson[] = [];
  recognitions: VisionRecognition[] = [];
  photosByPerson: Record<number, VisionPhoto[]> = {};
  expandedPersonId: number | null = null;

  newPersonName = '';
  loginUsername = '';
  loginPassword = '';
  verifyCode = '';
  loginPending = false;
  errorMessage = '';

  ngOnInit(): void {
    this.reloadStatus();
    this.reloadPersons();
    this.reloadRecognitions();
  }

  reloadStatus(): void {
    this.visionService.getStatus().subscribe({
      next: status => this.status = status,
      error: () => this.status = null
    });
  }

  reloadPersons(): void {
    this.visionService.getPersons().subscribe(persons => this.persons = persons);
  }

  reloadRecognitions(): void {
    this.visionService.getRecognitions().subscribe(recognitions => this.recognitions = recognitions);
  }

  login(): void {
    this.errorMessage = '';
    this.loginPending = true;
    this.visionService.login(this.loginUsername, this.loginPassword).subscribe({
      next: () => { this.loginPassword = ''; this.reloadStatus(); },
      error: err => { this.loginPending = false; this.errorMessage = err.message; }
    });
  }

  verify(): void {
    this.errorMessage = '';
    this.visionService.verify(this.verifyCode).subscribe({
      next: () => { this.loginPending = false; this.verifyCode = ''; this.reloadStatus(); },
      error: err => this.errorMessage = err.message
    });
  }

  createPerson(): void {
    const name = this.newPersonName.trim();
    if (!name) {
      return;
    }
    this.visionService.createPerson(name).subscribe(() => {
      this.newPersonName = '';
      this.reloadPersons();
    });
  }

  deletePerson(person: VisionPerson): void {
    if (!confirm(`Person "${person.name}" samt Fotos löschen?`)) {
      return;
    }
    this.visionService.deletePerson(person.id).subscribe(() => this.reloadPersons());
  }

  togglePhotos(person: VisionPerson): void {
    if (this.expandedPersonId === person.id) {
      this.expandedPersonId = null;
      return;
    }
    this.expandedPersonId = person.id;
    this.visionService.getPhotos(person.id)
      .subscribe(photos => this.photosByPerson[person.id] = photos);
  }

  uploadPhoto(person: VisionPerson, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.errorMessage = '';
    this.visionService.uploadPhoto(person.id, file).subscribe({
      next: () => { input.value = ''; this.reloadPersons(); this.togglePhotosReload(person); },
      error: err => this.errorMessage = err.message
    });
  }

  deletePhoto(person: VisionPerson, photo: VisionPhoto): void {
    this.visionService.deletePhoto(person.id, photo.id).subscribe(() => {
      this.reloadPersons();
      this.togglePhotosReload(person);
    });
  }

  private togglePhotosReload(person: VisionPerson): void {
    this.visionService.getPhotos(person.id)
      .subscribe(photos => this.photosByPerson[person.id] = photos);
  }
}
```

`vision.component.html`:

```html
<div class="vision">
  <h1>Gesichtserkennung</h1>

  <p class="vision__error" *ngIf="errorMessage">{{ errorMessage }}</p>

  <section class="vision__section">
    <h2>Blink-Konto</h2>
    <ng-container *ngIf="status; else statusUnavailable">
      <p *ngIf="status.loggedIn" class="vision__status vision__status--ok">
        Angemeldet — Kamera: {{ status.cameraName ?? 'keine gefunden' }}
        <span *ngIf="status.lastPollAt">(letzter Abruf: {{ status.lastPollAt | date:'short' }})</span>
      </p>
      <div *ngIf="!status.loggedIn" class="vision__login">
        <p>Nicht angemeldet. Anmeldung mit dem Amazon-/Blink-Konto (nur das Sitzungs-Token wird gespeichert):</p>
        <input type="email" placeholder="E-Mail" [(ngModel)]="loginUsername">
        <input type="password" placeholder="Passwort" [(ngModel)]="loginPassword">
        <button type="button" (click)="login()">Anmelden</button>
        <div *ngIf="loginPending" class="vision__verify">
          <input type="text" placeholder="2FA-PIN" [(ngModel)]="verifyCode">
          <button type="button" (click)="verify()">Bestätigen</button>
        </div>
      </div>
    </ng-container>
    <ng-template #statusUnavailable>
      <p class="vision__status vision__status--error">blink-vision-Dienst nicht erreichbar.</p>
    </ng-template>
  </section>

  <section class="vision__section">
    <h2>Personen</h2>
    <div class="vision__new-person">
      <input type="text" placeholder="Name" [(ngModel)]="newPersonName">
      <button type="button" (click)="createPerson()">Anlegen</button>
    </div>
    <div class="vision__person" *ngFor="let person of persons">
      <div class="vision__person-row">
        <strong>{{ person.name }}</strong>
        <span>{{ person.photoCount }} Foto(s)</span>
        <button type="button" (click)="togglePhotos(person)">Fotos</button>
        <label class="vision__upload">
          Foto hochladen
          <input type="file" accept="image/jpeg,image/png" (change)="uploadPhoto(person, $event)">
        </label>
        <button type="button" class="vision__delete" (click)="deletePerson(person)">Löschen</button>
      </div>
      <div class="vision__photos" *ngIf="expandedPersonId === person.id">
        <figure *ngFor="let photo of photosByPerson[person.id]">
          <img [src]="'data:image/jpeg;base64,' + photo.photoBase64" alt="Referenzfoto von {{ person.name }}">
          <button type="button" (click)="deletePhoto(person, photo)">Entfernen</button>
        </figure>
      </div>
    </div>
    <p class="vision__hint">Empfehlung: 3–5 frontale, gut beleuchtete Fotos pro Person.</p>
  </section>

  <section class="vision__section">
    <h2>Letzte Erkennungen</h2>
    <table class="vision__recognitions" *ngIf="recognitions.length; else noRecognitions">
      <tr>
        <th>Zeitpunkt</th><th>Person</th><th>Konfidenz</th><th>Unbekannte</th><th>Bild</th>
      </tr>
      <tr *ngFor="let r of recognitions">
        <td>{{ r.recognizedAt | date:'short' }}</td>
        <td>{{ r.personName ?? 'Unbekannt' }}</td>
        <td>{{ r.confidence !== null ? (r.confidence | percent:'1.0-1') : '—' }}</td>
        <td>{{ r.unknownFaces }}</td>
        <td><img *ngIf="r.thumbnailBase64" [src]="'data:image/jpeg;base64,' + r.thumbnailBase64" alt="Erkennungsbild"></td>
      </tr>
    </table>
    <ng-template #noRecognitions><p>Noch keine Erkennungen.</p></ng-template>
  </section>
</div>
```

`vision.component.scss` (kompakt, gekapselt — Feinschliff nach Geschmack der bestehenden Seiten):

```scss
.vision {
  padding: 1.5rem;

  &__section { margin-bottom: 2rem; }
  &__error { color: #c0392b; }
  &__status--ok { color: #27ae60; }
  &__status--error { color: #c0392b; }
  &__login input,
  &__new-person input { margin-right: 0.5rem; }
  &__person { border-bottom: 1px solid #eee; padding: 0.5rem 0; }
  &__person-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
  &__upload input[type="file"] { display: none; }
  &__upload { cursor: pointer; text-decoration: underline; }
  &__delete { color: #c0392b; }
  &__photos { display: flex; gap: 0.75rem; flex-wrap: wrap; margin-top: 0.5rem;
    img { max-width: 120px; border-radius: 4px; } }
  &__recognitions { border-collapse: collapse; width: 100%;
    th, td { text-align: left; padding: 0.35rem 0.75rem 0.35rem 0; }
    img { max-width: 100px; border-radius: 4px; } }
  &__hint { color: #888; font-size: 0.9rem; }
}
```

- [ ] **Step 4: Route ergänzen** (`app.routes.ts`, vor dem `**`-Eintrag)

```typescript
  {
    path: 'vision',
    loadComponent: () => import('./pages/vision/vision.component').then(m => m.VisionComponent),
    title: 'Gesichtserkennung - Household Manager'
  },
```

- [ ] **Step 5: Nav-Eintrag** (`header.component.ts`, in der Gruppe mit `/admin`-Children)

```typescript
        { path: '/vision', label: 'Gesichtserkennung' }
```

- [ ] **Step 6: Specs + Build grün**

```bash
cd frontend && npx ng test --watch=false --include="**/vision.component.spec.ts" && npx ng build
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/vision frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(vision): Frontend-Seite Gesichtserkennung mit Login, Personen und Historie"
```

---

### Task 14: Auto-Unlock-Flow (deaktiviert), Spec-Korrektur, Doku, Memory

**Files:**
- Modify: `docs/superpowers/specs/2026-07-22-blink-gesichtserkennung-design.md` (Entity-ID korrigieren)
- Modify: `CLAUDE.md` (neuer Abschnitt unter „Smart Device Integrations“)

- [ ] **Step 1: Flow per Flow-MCP anlegen** (Tools `flow_node_types` → `flow_create` → `flow_deploy`; NICHT enablen!)

Flow-Skizze (exakte Node-Typen/Felder vorher per `flow_node_types` prüfen; `smartlockId` per `flow_list_entities`/Nuki-API ermitteln — **als String!**):

```json
{
  "name": "Haustür-Auto-Unlock bei erkanntem Bewohner",
  "nodes": [
    { "id": "trigger", "type": "entity-event-trigger",
      "config": { "entityId": "event.vision_blink_door_person" } },
    { "id": "check-person", "type": "condition",
      "config": { "field": "state", "operator": "in", "values": ["Benedikt", "Partnerin"] } },
    { "id": "unlatch", "type": "nuki-lock-action",
      "config": { "smartlockId": "<SMARTLOCK_ID_ALS_STRING>", "action": "unlatch" } }
  ],
  "connections": [
    { "from": "trigger", "to": "check-person" },
    { "from": "check-person", "to": "unlatch" }
  ]
}
```

Optional (Spec): nach `unlatch` einen Alexa-Ansage-Node („Willkommen zuhause“) anhängen — Node-Typ und `deviceSerials` per `flow_node_types`/`flow_list_alexa_devices` ermitteln.

Die Personennamen in der Condition müssen exakt den in Task 13 angelegten `vision_person.name`-Werten entsprechen. Nach `flow_deploy` (ValidationResult prüfen) **deaktiviert lassen** — Scharfschaltung erst nach einigen Tagen zuverlässiger Erkennungshistorie, durch den Benutzer.

- [ ] **Step 2: Spec-Korrektur** — in der Spec `event.blink_door_person` durch `event.vision_blink_door_person` ersetzen (2 Vorkommen: Abschnitt Entity-State-Layer und Flow).

- [ ] **Step 3: CLAUDE.md ergänzen** (unter „Smart Device Integrations“, nach dem Nuki-Abschnitt)

```markdown
### Blink-Gesichtserkennung (blink-vision-Sidecar)
- Python-Sidecar `blink-vision/` (FastAPI + blinkpy + InsightFace buffalo_s auf CPU): pollt Local-Storage-Clips der Blink-Türkamera (Sync Module 2 + USB, Abruf läuft trotzdem über die Blink-Cloud, Latenz 15–45 s), erkennt Gesichter und meldet Ergebnisse per Webhook ans Backend
- Login in-app (E-Mail/Passwort + 2FA-PIN); persistiert wird nur das Session-Token im Volume, nie Zugangsdaten; blinkpy-Spezifika leben ausschließlich in `blink-vision/app/blink_client.py`
- Backend `vision/`: Personen + Referenzfotos führend in DB (`vision_person`, `vision_person_photo`, `vision_recognition`), Embeddings werden an den Sidecar gepusht (Start-Pull über `GET /v1/vision/embeddings`)
- Jede Erkennung feuert `EntityEventFired` auf `event.vision_blink_door_person` (state = Personenname oder `unknown`, Attribute personId/confidence/unknownFaces); ausbleibender Heartbeat → `unavailable`
- Auto-Unlock-Flow (entity-event-trigger → Personen-Condition → `nuki-lock-action` unlatch) ist bewusst **deaktiviert** angelegt; Scharfschaltung erst nach zuverlässiger Erkennungshistorie. Foto-Spoofing-Risiko ist dokumentiert und vom Nutzer akzeptiert
- Frontend-Seite „Gesichtserkennung“ (`pages/vision/`): Blink-Login, Personenverwaltung mit Foto-Upload, Erkennungshistorie
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-07-22-blink-gesichtserkennung-design.md
git commit -m "docs(vision): Blink-Gesichtserkennung dokumentiert; Entity-ID in Spec korrigiert"
```

- [ ] **Step 5: Memory aktualisieren** — neue Memory-Datei `blink-gesichtserkennung.md` (type: project): Stand der Integration, offene Realtests (Sidecar auf Server deployen, Referenzfotos hochladen, Flow erst nach Historie aktivieren), Foto-Spoofing bewusst akzeptiert, blinkpy-Spezifika nur in `blink_client.py`. Eintrag in `MEMORY.md` ergänzen.

---

## Abschluss-Verifikation (nach allen Tasks)

- [ ] Backend: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="Vision*"` — alle grün
- [ ] Sidecar: `cd blink-vision && .venv/Scripts/python -m pytest tests/ -v` — alle grün
- [ ] Frontend: `cd frontend && npx ng test --watch=false && npx ng build` — grün (bis auf bekannte, vorbestehende Fehlschläge)
- [ ] `docker compose config -q` — valide
- [ ] Flow existiert, ist deployt und **deaktiviert** (`flow_list` prüfen)
- [ ] Hinweis an den Benutzer: Deployment auf dem Server, Blink-Login im UI, 3–5 Fotos pro Person, einige Tage Historie prüfen, dann Flow bewusst aktivieren
