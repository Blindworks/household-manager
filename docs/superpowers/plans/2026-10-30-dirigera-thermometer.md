# IKEA-Dirigera-Thermometer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TIMMERFLOTTE-Sensoren am IKEA-Dirigera-Hub werden minütlich gelesen und landen als Entitäten, Historie und Quelle der Temperaturansicht im Household Manager. Der Hub wird in der App gekoppelt.

**Architecture:** Neues Backend-Paket `dirigera/` nach dem Muster `blesensor/` (Zeile je Sensor mit Letztwerten, Recorder, Entity-Mapper) und `thermostat/` (Hub mit manueller IP, Identitätsprüfung). Ein Java-`HttpClient` mit Fingerabdruck-Pinning spricht `https://<IP>:8443/v1`. Die PKCE-Kopplung läuft als Hintergrund-Task mit Status-Polling durch das Frontend. Eine neue Quelle `dirigera:<id>` in `TemperatureSeriesService` und eine Admin-Seite `admin/dirigera` kommen dazu.

**Tech Stack:** Spring Boot 3.4 / Java 21, Liquibase (MariaDB), Jackson, `java.net.http.HttpClient`, Angular 19 standalone.

**Spec:** `docs/superpowers/specs/2026-10-30-dirigera-thermometer-design.md`. Bitte vor dem Start lesen, dort stehen die verifizierten API-Fakten.

## Global Constraints

- **Backend-Build:** `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` vor jedem `mvn`, ausgeführt aus `backend/`. `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` sind lokal immer rot (keine DB). Das ist die Baseline.
- **Frontend-Tests:** `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`. Die Baseline sind 3 vorbestehende Fails (App/Hero).
- **JPA-Repositories** liegen ausschließlich in `com.household.manager.repository`, Entities in `com.household.manager.model.entity`.
- **Changesets:** je DDL ein eigenes Changeset, Präfix `20261030-`, Autor `claude`. Jedes wird in `db.changelog-master.xml` eingetragen.
- Settings-Kategorie `DIRIGERA`, Schlüssel `hub_host`, `access_token`, `cert_sha256`, `hub_id`. Leerer String bedeutet „nicht gesetzt“ (Spalte NOT NULL).
- Entity-ID: `EntityIds.build(EntityDomain.SENSOR, EntitySource.DIRIGERA, <relationId ohne '-' , lowercase>, "temperature"|"humidity")`. **Kein `deviceClass`.**
- Werte werden beim Parsen auf 2 Nachkommastellen gerundet (`RoundingMode.HALF_UP`).
- Properties: `dirigera.enabled=true`, `dirigera.poll-interval-ms=60000`, `dirigera.stale-after-minutes=60`.
- **Nie 401 nach außen:** Hub-Fehler ⇒ `DirigeraException` → 502, „nicht gekoppelt“ / „Token widerrufen“ ⇒ `IllegalStateException` → 400.
- Audit-Aktionen: `dirigera.hub.update`, `dirigera.pair`, `dirigera.unpair`, `dirigera.sensor.delete`. Im Detail stehen Host bzw. Sensorname, keine Ids, deshalb braucht es keine `AuditEntryDescriber`-Regel.
- Commit-Messages enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commits per `git commit -F <datei>` (PowerShell-Quoting-Falle).

## Review Focus

1. **Unter der Hub-IP antwortet ein anderes Gerät** (DHCP-Wechsel) ⇒ Der TLS-Handshake scheitert am Pinning, **bevor** der Bearer-Token gesendet wird. Alle Sensoren gehen auf `unavailable`, die Job-Tafel zeigt FAILING. Test in Task 3 (TrustManager lehnt ab) und Task 6 (Mismatch ⇒ unavailable).
2. **Der Hub widerruft den Token** (antwortet 401) ⇒ Kein 401 zum Browser. Der Poller meldet FAILING mit „neu koppeln“, die Entitäten werden `unavailable`. Test in Task 6.
3. **Ein Sensor hat leere Batterie oder ist abgemeldet** (`lastSeen` alt oder `isReachable:false`) ⇒ genau ein `unavailable` mit erhaltenen Attributen, nicht jede Minute neu. Test in Task 6.
4. **Doppelklick auf „Koppeln“ oder Backend-Neustart mitten im Koppeln** ⇒ Der zweite Start gibt 409, nach dem Neustart steht der Status auf `IDLE`, der Token wird nie halb gespeichert. Test in Task 7.
5. **`customName` ist leer oder ein Sensor liefert nur ein Teilgerät** ⇒ Die Anzeige fällt auf das Modell zurück, der fehlende Wert bleibt `null` (nie 0) und es entsteht nur die Entität des vorhandenen Werts. Test in Task 1 und Task 5.

---

### Task 1: Parser für `GET /v1/devices`

**Files:**
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraDeviceParser.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraSensorSnapshot.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraDevices.java`
- Test: `backend/src/test/java/com/household/manager/dirigera/DirigeraDeviceParserTest.java`
- Fixture (existiert schon): `backend/src/test/resources/dirigera/devices.json`

**Interfaces:**
- Produces:
  - `record DirigeraSensorSnapshot(String relationId, String name, String room, String model, BigDecimal temperature, BigDecimal humidity, Integer batteryPercent, boolean reachable, Instant lastSeen)`
  - `record DirigeraDevices(String gatewayId, List<DirigeraSensorSnapshot> sensors)`
  - `@Component DirigeraDeviceParser { DirigeraDevices parse(String json) }` wirft `DirigeraException` bei unlesbarem JSON oder wenn das Wurzelelement kein Array ist.
  - `class DirigeraException extends RuntimeException` (in diesem Task anlegen, Datei `dirigera/DirigeraException.java`).

- [ ] **Step 1: Failing tests schreiben**

```java
@Test void fixtureErgibtVierSensorenMitZusammengefuehrtenWerten() {
    DirigeraDevices d = parser.parse(fixture());
    assertThat(d.gatewayId()).isEqualTo("05f1aba0-b770-479e-994a-f167e187ce32_1");
    assertThat(d.sensors()).hasSize(4);
    DirigeraSensorSnapshot ess = bySensor(d, "dcc8d385-781d-4071-adf8-5f403b0cb997");
    assertThat(ess.name()).isEqualTo("Temperatur Esszimmer");
    assertThat(ess.room()).isEqualTo("Esszimmer");
    assertThat(ess.temperature()).isEqualByComparingTo("18.79");
    assertThat(ess.temperature().scale()).isEqualTo(2);
    assertThat(ess.humidity()).isEqualByComparingTo("52");
    assertThat(ess.batteryPercent()).isEqualTo(100);
    assertThat(ess.reachable()).isTrue();
    assertThat(ess.lastSeen()).isEqualTo(Instant.parse("2026-10-30T08:53:36Z"));
}
@Test void teilgeraetZuordnungNachFeldNichtNachSuffix()   // _1 trägt RH, _2 trägt Temperatur ⇒ trotzdem richtig
@Test void nurEinTeilgeraetLiefertFehlendenWertAlsNull()  // humidity == null, nicht 0
@Test void fremderDeviceTypeWirdIgnoriert()               // "light" taucht nicht auf
@Test void unreachableWennEinTeilgeraetUnreachable()       // isReachable false am _2 ⇒ reachable false
@Test void batterieIstMinimumDerTeilgeraete()              // 100 und 40 ⇒ 40
@Test void lastSeenIstMaximumDerTeilgeraete()
@Test void leererCustomNameFaelltAufModellZurueck()        // name == "TIMMERFLOTTE temp/hmd sensor"
@Test void keinArrayWirftDirigeraException()               // "{}" ⇒ DirigeraException
```

Synthetische Fälle als kleine JSON-Strings direkt im Test.

- [ ] **Step 2: Rot prüfen**

Run: `mvn test -Dtest=DirigeraDeviceParserTest`
Expected: Kompilierfehler, die Klassen fehlen.

- [ ] **Step 3: Implementieren**

Jackson `ObjectMapper.readTree`. Nur `deviceType == "environmentSensor"` gruppieren, nach `relationId` in einer `LinkedHashMap` (Reihenfolge der Antwort). Der Gateway ist der erste Eintrag mit `deviceType == "gateway"`, sonst `gatewayId = null`. Werte über `new BigDecimal(node.asText()).setScale(2, HALF_UP)`. Fehlende Felder ergeben `null`.

- [ ] **Step 4: Grün prüfen** (gleicher Befehl, PASS)

- [ ] **Step 5: Commit** `feat(dirigera): Parser fuer die Geraeteliste des Hubs`

---

### Task 2: Einstellungen des Hubs

**Files:**
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraSettingsService.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraHubSettings.java`
- Test: `backend/src/test/java/com/household/manager/dirigera/DirigeraSettingsServiceTest.java`

**Interfaces:**
- Consumes: `ApplicationSettingsService.getSettingsByCategory(String)`, `saveSettings(String, Map<String,String>)`
- Produces:
  - `record DirigeraHubSettings(String host, String accessToken, String certSha256, String hubId) { boolean isPaired() }`. `isPaired()` ist wahr, wenn host, token, cert und hubId alle nicht leer sind.
  - `DirigeraHubSettings get()` wirft nie. Ein Fehler wird geloggt und ergibt eine leere Instanz, leere Strings werden zu `null`.
  - `void saveHost(String host)`: behält Token, Zertifikat und hubId (Spec: „PUT /hub behält hub_id und cert_sha256“).
  - `void savePairing(String accessToken, String certSha256, String hubId)`
  - `void clearPairing()`: löscht token, cert und hubId, behält den Host.

- [ ] **Step 1: Failing tests:** `getWirftNieUndLiefertLeer` (Mock wirft), `leereStringsSindNull`, `isPairedBrauchtAlleVier`, `saveHostBehaeltPairing` (verify `saveSettings` mit Map, die nur `hub_host` enthält), `clearPairingSchreibtLeereStringsAberNichtHost`.
- [ ] **Step 2: Rot prüfen** `mvn test -Dtest=DirigeraSettingsServiceTest`
- [ ] **Step 3: Implementieren.** `saveSettings` mit der Teil-Map, die `ApplicationSettingsService` je Schlüssel upsertet. Vorher prüfen, dass `saveSettings` keine fehlenden Schlüssel löscht. Tut sie das, je Schlüssel `saveSetting` nutzen.
- [ ] **Step 4: Grün prüfen**
- [ ] **Step 5: Commit** `feat(dirigera): Hub-Einstellungen in application_settings`

---

### Task 3: HTTP-Client mit Fingerabdruck-Pinning und PKCE

**Files:**
- Create: `backend/src/main/java/com/household/manager/dirigera/client/PinnedTrustManager.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/client/Pkce.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/client/DirigeraHttpClient.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/client/DirigeraCertificateMismatchException.java` (extends `DirigeraException`)
- Create: `backend/src/main/java/com/household/manager/dirigera/client/DirigeraUnauthorizedException.java` (extends `DirigeraException`)
- Test: `backend/src/test/java/com/household/manager/dirigera/client/PinnedTrustManagerTest.java`, `PkceTest.java`
- Test-Ressource: `backend/src/test/resources/dirigera/test-cert.pem` (selbstsigniert, siehe Step 1)

**Interfaces:**
- Produces:
  - `record Pkce(String verifier, String challenge) { static Pkce generate(); static String challengeOf(String verifier) }`. Der Verifier hat 128 Zeichen aus `[A-Za-z0-9-._~]` (SecureRandom), `challenge = base64url(sha256(verifier))` ohne Padding.
  - `final class PinnedTrustManager implements X509TrustManager`, Konstruktor `(String expectedSha256)` mit 64 Hex-Zeichen, Groß/Klein und `:` egal. `checkServerTrusted` vergleicht den SHA-256 von `chain[0].getEncoded()`, bei Abweichung `CertificateException`. `static String fingerprint(X509Certificate)` liefert Hex in Großbuchstaben mit `:`, also dasselbe Format wie `openssl x509 -fingerprint -sha256`.
  - `@Component DirigeraHttpClient` (Port 8443, HTTP/1.1, Connect 5 s, Request 10 s):
    - `String fetchFingerprint(String host)`: Handshake mit einem TrustManager, der alles akzeptiert und das Leaf-Zertifikat merkt. Nur hier wird blind vertraut (TOFU beim Koppeln).
    - `String authorize(String host, String certSha256, String challenge)` liefert `code`.
    - `Optional<String> requestToken(String host, String certSha256, String code, String verifier)`: ohne `access_token` in der Antwort `Optional.empty()`, auch bei Status ≠ 200. Der letzte Status/Body bleibt über `lastTokenResponse()` für die Fehlermeldung lesbar.
    - `String getDevices(String host, String certSha256, String token)`
  - Fehlerabbildung: SSL-Handshake-Fehler (Ursache `CertificateException`) ⇒ `DirigeraCertificateMismatchException`. HTTP 401 ⇒ `DirigeraUnauthorizedException`. Sonstige IO-Fehler oder Status ≥ 400 ⇒ `DirigeraException`.
  - **Hostname-Verifikation** wird nur für die Clients dieser Klasse abgeschaltet, über `SSLParameters.setEndpointIdentificationAlgorithm(null)` am `HttpClient.Builder.sslParameters`. Kein globales `jdk.internal.httpclient.disableHostnameVerification`. Der Pinning-Client wird je Fingerabdruck gecacht (`ConcurrentHashMap`), damit nicht jeder Poll einen neuen `HttpClient` baut.

- [ ] **Step 1: Test-Zertifikat erzeugen**

Run (aus `backend/`): `openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout /dev/null -out src/test/resources/dirigera/test-cert.pem -days 3650 -subj "/CN=dirigera-test"`, dann `openssl x509 -in src/test/resources/dirigera/test-cert.pem -noout -fingerprint -sha256`. Den ausgegebenen Wert als Konstante `EXPECTED` in den Test schreiben.

- [ ] **Step 2: Failing tests**

```java
// PkceTest — RFC 7636 Anhang B
@Test void challengeNachRfc7636() {
    assertThat(Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
}
@Test void generateLiefert128ZeichenAusErlaubtemAlphabet()   // matches("[A-Za-z0-9._~-]{128}")

// PinnedTrustManagerTest
@Test void akzeptiertGemerktenFingerabdruck()                // new PinnedTrustManager(EXPECTED).checkServerTrusted(chain,"ECDHE_ECDSA") wirft nicht
@Test void akzeptiertFingerabdruckOhneDoppelpunkteKleingeschrieben()
@Test void lehntAnderesZertifikatAb()                         // erwartet anderer Hash ⇒ CertificateException
@Test void fingerprintImOpensslFormat()                       // fingerprint(cert) == EXPECTED
```

- [ ] **Step 3: Rot prüfen** `mvn test -Dtest=PkceTest,PinnedTrustManagerTest`
- [ ] **Step 4: Implementieren** (alle Dateien dieses Tasks). `DirigeraHttpClient` bekommt keinen Unit-Test gegen ein echtes Netz, den Realtest deckt Task 11 ab.
- [ ] **Step 5: Grün prüfen**
- [ ] **Step 6: Commit** `feat(dirigera): HTTP-Client mit Zertifikats-Pinning und PKCE`

---

### Task 4: Tabellen, Entities, Repositories

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20261030-0075-create-dirigera-sensor.xml`
- Create: `…/20261030-0076-create-dirigera-sensor-reading.xml`
- Create: `…/20261030-0076b-create-dirigera-sensor-reading-fk.xml`
- Create: `…/20261030-0077-create-dirigera-sensor-reading-index.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (vier Includes am Ende)
- Create: `backend/src/main/java/com/household/manager/model/entity/DirigeraSensor.java`, `DirigeraSensorReading.java`
- Create: `backend/src/main/java/com/household/manager/repository/DirigeraSensorRepository.java`, `DirigeraSensorReadingRepository.java`

**Interfaces:**
- Produces:
  - `dirigera_sensor`: `id` BIGINT PK autoinc, `relation_id` VARCHAR(64) NOT NULL UNIQUE (`uk_dirigera_sensor_relation_id`), `name` VARCHAR(100), `room` VARCHAR(100), `model` VARCHAR(100), `last_temperature` DECIMAL(5,2), `last_humidity` DECIMAL(5,2), `battery_percent` INT, `last_seen_at` DATETIME, `reachable` BOOLEAN NOT NULL default false, `known_to_hub` BOOLEAN NOT NULL default true, `created_at` DATETIME NOT NULL, `updated_at` DATETIME NOT NULL.
  - `dirigera_sensor_reading`: `id` BIGINT PK autoinc, `sensor_id` BIGINT NOT NULL, `measured_at` DATETIME NOT NULL, `temperature` DECIMAL(5,2), `humidity` DECIMAL(5,2). FK `fk_dirigera_reading_sensor` mit `ON DELETE CASCADE`, Index `idx_dirigera_reading_sensor_time (sensor_id, measured_at)`.
  - Entities mit Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, Feldnamen camelCase passend zu den Spalten. `DirigeraSensor`: `@PrePersist`/`@PreUpdate` setzen `createdAt`/`updatedAt`.
  - `DirigeraSensorRepository`: `Optional<DirigeraSensor> findByRelationId(String)`, `List<DirigeraSensor> findByKnownToHubTrue()`
  - `DirigeraSensorReadingRepository`: `Optional<DirigeraSensorReading> findTopBySensorIdOrderByMeasuredAtDesc(Long)`, `List<DirigeraSensorReading> findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(Long, LocalDateTime, LocalDateTime)`

Vorlage für die XML-Form: `20261013-0066…0068` (BLE).

- [ ] **Step 1: Dateien anlegen**
- [ ] **Step 2: Kompilieren** `mvn -q compile`, erwartet ohne Fehler. Liquibase läuft lokal nicht (keine DB), deshalb die XML zusätzlich per Augenschein gegen die BLE-Changesets prüfen: kein `incrementBy`, ein DDL je Changeset, `rollback` vorhanden.
- [ ] **Step 3: Commit** `feat(dirigera): Tabellen fuer Sensoren und Historie`

---

### Task 5: Entity-Mapper und Historien-Recorder

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java` (neuer Wert `DIRIGERA` mit Javadoc)
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraEntityMapper.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraReadingRecorder.java`
- Test: `DirigeraEntityMapperTest.java`, `DirigeraReadingRecorderTest.java` unter `backend/src/test/java/com/household/manager/dirigera/`

**Interfaces:**
- Consumes: `DirigeraSensor`, `DirigeraSensorReadingRepository` (Task 4)
- Produces:
  - `DirigeraEntityMapper`, Aufbau wie `BleSensorEntityMapper`:
    - `static final String STATE_UNAVAILABLE = "unavailable"`, `enum Measurement { TEMPERATURE("temperature","°C","Temperatur"), HUMIDITY("humidity","%","Luftfeuchte") }`
    - `static String entityRef(String relationId)`: ohne `-`, kleingeschrieben.
    - `static String entityId(String relationId, Measurement m)`
    - `List<EntityStateUpdate> updates(DirigeraSensor s)`: nur für Nicht-null-Werte.
    - `List<EntityStateUpdate> unavailable(DirigeraSensor s)`: immer beide, mit Attributen.
    - Attribute: `unit`, `batteryPercent` (wenn gesetzt), `room` (wenn gesetzt), `model` (wenn gesetzt), `lastSeenAt` (`LocalDateTime.toString()`, wenn gesetzt).
    - friendlyName: `<name> <Label>`. Ist `name` leer, gilt `model`, dann `relationId`.
  - `DirigeraReadingRecorder.record(DirigeraSensor s, LocalDateTime measuredAt)`: Logik wie `BleSensorReadingRecorder` (Änderung per `compareTo` oder ≥ 5 min seit letzter Zeile), wirft nie.

- [ ] **Step 1: Failing tests**
  - Mapper:
    - `entityIdHaengtAnRelationId` (`entityId("dcc8d385-781d-4071-adf8-5f403b0cb997", TEMPERATURE)` == `"sensor.dirigera_dcc8d385781d4071adf85f403b0cb997_temperature"`)
    - `updatesNurFuerVorhandeneWerte` (humidity null ⇒ 1 Update)
    - `stateOhneNachkommaNullen` (`20.50` ⇒ `"20.5"`)
    - `keinDeviceClass` (`deviceClass()` null)
    - `unavailableBehaeltAttribute`
    - `friendlyNameFallbackAufModell`
  - Recorder:
    - `ersteMessungWirdGespeichert`
    - `gleicherWertInnerhalbFuenfMinutenNicht` (`18.79` vs `18.790`)
    - `heartbeatNachFuenfMinuten`
    - `aenderungSofort`
    - `repositoryFehlerWirdGeschluckt`
- [ ] **Step 2: Rot prüfen** `mvn test -Dtest='Dirigera*MapperTest,DirigeraReadingRecorderTest'`
- [ ] **Step 3: Implementieren.** Vorher `EntityStateUpdate` öffnen und den Builder-Namen für `deviceClass` prüfen.
- [ ] **Step 4: Grün prüfen**
- [ ] **Step 5: Commit** `feat(dirigera): Entitaeten und Historie je Sensor`

---

### Task 6: Polling-Service

**Files:**
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraPollingService.java`
- Modify: `backend/src/main/resources/application.properties` (drei `dirigera.*`-Properties aus den Global Constraints, mit Kommentar)
- Test: `backend/src/test/java/com/household/manager/dirigera/DirigeraPollingServiceTest.java`

**Interfaces:**
- Consumes: `DirigeraSettingsService.get()`, `DirigeraHttpClient.getDevices`, `DirigeraDeviceParser.parse`, Repositories (Task 4), `DirigeraEntityMapper`, `DirigeraReadingRecorder`, `EntityStateService.reportState/getByEntityId`, `SystemJobMonitor.reportFailure/reportInactive`, `Clock`
- Produces:
  - `@Scheduled(fixedDelayString="${dirigera.poll-interval-ms:60000}", initialDelayString="30000") void poll()`: wirft nie.
  - `PollStatus lastStatus()` mit `record PollStatus(Instant lastSuccessAt, String lastError)`, nur im Speicher, für die Admin-Seite (Task 8).
  - `void markAllUnavailable()`: von Unpair (Task 7) genutzt.

**Ablauf je Zyklus:**

1. `dirigera.enabled=false` oder `!settings.isPaired()` ⇒ `reportInactive("nicht gekoppelt")`, Ende.
2. `getDevices`, dann `parse`. Fehler werden so behandelt:
   - `DirigeraCertificateMismatchException` ⇒ Grund „Zertifikat geändert – neu koppeln“
   - `DirigeraUnauthorizedException` ⇒ „Hub lehnt Token ab – neu koppeln“
   - sonst `DirigeraException`/`RuntimeException` ⇒ Meldung.

   In allen Fällen: `reportFailure(grund)`, `lastError` setzen, `markAllUnavailable()`, Ende.
3. `gatewayId` ≠ `settings.hubId()` ⇒ wie oben mit Grund „Anderes Gerät unter der Hub-Adresse“.
4. Je Snapshot, einzeln in try/catch:
   - Zeile per `findByRelationId` holen oder neu anlegen (`knownToHub=true`).
   - Felder übernehmen: name, room, model, Werte, Batterie, reachable, `lastSeenAt = LocalDateTime.ofInstant(lastSeen, clock.getZone())`.
   - `save`.
   - Ist der Sensor frisch (`reachable` und `lastSeenAt` jünger als `stale-after-minutes`): `recorder.record(row, row.getLastSeenAt())`, danach `mapper.updates(row)` ⇒ `reportState`.
   - Sonst `reportUnavailableOnce(row)`.
5. Zeilen mit `knownToHub=true`, die nicht in der Antwort waren ⇒ `knownToHub=false`, speichern, `reportUnavailableOnce`.
6. `lastSuccessAt` setzen, `lastError=null`.

`reportUnavailableOnce`: wie `BleSensorAvailabilityWatchdog.markUnavailable` (nur melden, wenn die Entität nicht schon `unavailable` ist). `markAllUnavailable` wendet das auf alle Zeilen mit `knownToHub=true` an.

- [ ] **Step 1: Failing tests** (Mockito, `Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("Europe/Berlin"))`, Parser echt gegen die Fixture):
  - `nichtGekoppeltMeldetInactiveUndRuftHubNicht`
  - `fixtureLegtVierZeilenAnUndMeldetAchtEntitaeten`
  - `lastSeenWirdInClockZoneUmgerechnet` (`08:53:36Z` ⇒ `09:53:36` lokal)
  - `zertifikatsfehlerMeldetFailureUndUnavailable` (Review Focus 1)
  - `tokenWiderrufenMeldetFailureMitNeuKoppeln` (Review Focus 2, Grund enthält „neu koppeln“)
  - `fremdeGatewayIdSchreibtNichts` (keine `save`-Aufrufe für Sensoren)
  - `alterLastSeenMeldetUnavailableGenauEinmal` (Entity schon `unavailable` ⇒ kein `reportState`, Review Focus 3)
  - `unreachableMeldetUnavailable`
  - `verschwundenerSensorWirdKnownToHubFalse`
  - `fehlerBeiEinemSensorStopptDieAnderenNicht`
  - `pollWirftNie` (Repository wirft ⇒ keine Exception)
- [ ] **Step 2: Rot prüfen** `mvn test -Dtest=DirigeraPollingServiceTest`
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: Grün prüfen**
- [ ] **Step 5: Commit** `feat(dirigera): minuetliches Polling der Sensoren`

---

### Task 7: Kopplung (PKCE + Aktionsknopf)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraPairingService.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraPairingStatus.java`
- Test: `backend/src/test/java/com/household/manager/dirigera/DirigeraPairingServiceTest.java`

**Interfaces:**
- Consumes: `DirigeraHttpClient` (Task 3), `DirigeraSettingsService` (Task 2), `DirigeraDeviceParser` (Task 1), `DirigeraPollingService.markAllUnavailable()` (Task 6), `AuditService.record(String, String)`, `Clock`
- Produces:
  - `record DirigeraPairingStatus(State state, Instant expiresAt, String error)` mit `enum State { IDLE, PRESS_BUTTON, PAIRED, FAILED }`
  - `DirigeraPairingStatus start()`: kein Host ⇒ `IllegalStateException` („Erst die Hub-Adresse eintragen“). Läuft schon ein Vorgang ⇒ `PairingInProgressException` → 409 (neue Exception, Handler in Task 8). Ablauf: `fetchFingerprint`, dann `Pkce.generate()`, dann `authorize`, Status `PRESS_BUTTON` mit `expiresAt = now + 90 s`. Danach wird der Poll-Task eingeplant und der Status zurückgegeben. Fehler vor dem Einplanen ⇒ `DirigeraException` (502), der Status bleibt `IDLE`.
  - Poll-Task: eigener `ScheduledExecutorService` (1 Thread, Daemon, Name `dirigera-pairing`), alle 2 s `requestToken`. Bei Token: `getDevices` (mit dem neuen Fingerabdruck), `parse`, gatewayId fehlt ⇒ FAILED. Sonst `savePairing(token, fp, gatewayId)`, Audit `dirigera.pair` mit Detail Host, Status `PAIRED`. Nach 90 s ohne Token: `FAILED` mit „Kein Knopfdruck innerhalb von 90 s (letzte Antwort: <status> <body gekürzt auf 200>)“. Verbindungsfehler zählen als „wartet“ (Spec).
  - `DirigeraPairingStatus status()`
  - `void unpair()`: `clearPairing()`, `markAllUnavailable()`, Audit `dirigera.unpair`.
  - `void updateHost(String host)`: Validierung nicht leer, höchstens 253 Zeichen, ohne `/` und ohne Leerzeichen, sonst `IllegalArgumentException` → 400. Danach `saveHost`, Audit `dirigera.hub.update` mit dem Host.
  - Der Zustand liegt nur im Speicher. Nach einem Neustart ist er `IDLE`. Der Executor wird in `@PreDestroy` mit `shutdownNow` beendet.
  - Für Tests: Konstruktor-Parameter `ScheduledExecutorService`. Ein package-private Konstruktor nimmt einen Executor entgegen, den der Test manuell ausführt (z. B. eigener `DeterministicScheduler` im Test oder ein Mock, dessen eingeplante `Runnable` der Test per `ArgumentCaptor` selbst aufruft).

- [ ] **Step 1: Failing tests**
  - `startOhneHostWirftIllegalState`
  - `startLiefertPressButtonMit90Sekunden`
  - `zweiterStartWaehrendPressButtonWirft409` (Review Focus 4)
  - `tokenNachDrittemVersuchSpeichertPairingUndAudit` (`requestToken` liefert 2× empty, dann Token; `savePairing("tok", fp, "05f1…_1")`)
  - `ohneTokenNach90SekundenFailedMitLetzterAntwort`
  - `verbindungsfehlerZaehltAlsWarten`
  - `neuKoppelnUeberschreibtHubIdOhne409` (Spec-Entscheidung)
  - `unpairLoeschtUndMarkiertUnavailable`
  - `updateHostLehntSchraegstrichAb`
  - `nachFailedIstNeuerStartMoeglich`
- [ ] **Step 2: Rot prüfen** `mvn test -Dtest=DirigeraPairingServiceTest`
- [ ] **Step 3: Implementieren.** Zustandswechsel unter `synchronized`, damit der Doppelklick-Schutz atomar ist.
- [ ] **Step 4: Grün prüfen**
- [ ] **Step 5: Commit** `feat(dirigera): Kopplung per Aktionsknopf`

---

### Task 8: Admin-API, Security, Löschen

**Files:**
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraController.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraAdminService.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/DirigeraDtos.java`
- Create: `backend/src/main/java/com/household/manager/dirigera/PairingInProgressException.java`
- Modify: `backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java` (Handler: `DirigeraException` → 502, `PairingInProgressException` → 409. `IllegalStateException`/`IllegalArgumentException` → 400 existieren schon, bitte prüfen.)
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`
- Test: `backend/src/test/java/com/household/manager/dirigera/DirigeraAdminServiceTest.java`

**Interfaces:**
- Consumes: Tasks 2, 6, 7. `EntityStateService.deleteByEntityId`, Tile-Visibility-Löschung wie in `ZigbeeDevicePurgeService` (gleiches Repository, gleiche Methode).
- Produces, REST unter `/v1/dirigera` (Kontextpfad `/api`):
  - `GET /hub` ⇒ `HubView(String host, boolean paired, String hubId, Instant lastSuccessAt, String lastError)`. **Ohne Token und ohne Fingerabdruck.**
  - `PUT /hub` Body `{host}` ⇒ `HubView`
  - `POST /pairing` ⇒ `DirigeraPairingStatus`. `GET /pairing` ⇒ `DirigeraPairingStatus`. `DELETE /pairing` ⇒ 204.
  - `GET /sensors` ⇒ `List<SensorView(Long id, String name, String room, String model, BigDecimal temperature, BigDecimal humidity, Integer batteryPercent, boolean reachable, boolean knownToHub, LocalDateTime lastSeenAt)>`, sortiert nach Name
  - `DELETE /sensors/{id}` ⇒ 204. Ist `knownToHub=true` ⇒ 409 (`ConflictException` o. ä., wie beim Zigbee-Purge). Unbekannte Id ⇒ 404. Löscht beide Entitäten samt Tile-Regeln und die Zeile (die Historie fällt per Kaskade), Audit `dirigera.sensor.delete` mit dem Namen.
- Security: in `SecurityConfig` **im ADMIN-Block vor** der generischen `GET /v1/**`-Regel ein methodenloses `.requestMatchers("/v1/dirigera", "/v1/dirigera/**").hasRole("ADMIN")`. Die Platzierung erfolgt wie bei `"/v1/ble-sensors", "/v1/ble-sensors/**"` (Zeile ~178).

- [ ] **Step 1: Failing tests**
  - `SecurityRulesTest`: `dirigeraHubGetIstFuerKioskVerboten`, `dirigeraHubGetIstFuerMemberVerboten`, `dirigeraPairingPostIstFuerMemberVerboten`, `dirigeraSensorsGetIstFuerAdminErlaubt`. Die Testmethoden folgen dem Muster der vorhandenen BLE-Tests in derselben Datei.
  - `DirigeraAdminServiceTest`:
    - `loeschenEinesBekanntenSensorsWirft409`
    - `loeschenEntferntEntitaetenTileRegelnUndZeile`
    - `hubViewEnthaeltKeinenToken` (Reflection über die Record-Komponenten: keine Komponente `accessToken`/`certSha256`)
- [ ] **Step 2: Rot prüfen** `mvn test -Dtest='SecurityRulesTest,DirigeraAdminServiceTest'`
- [ ] **Step 3: Implementieren.** Der Controller bleibt dünn, alles Weitere liegt im Service.
- [ ] **Step 4: Grün prüfen**
- [ ] **Step 5: Commit** `feat(dirigera): Admin-API und Sicherheitsregeln`

---

### Task 9: Quelle in der Temperaturansicht

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java`
- Modify: `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java`

**Interfaces:**
- Consumes: `DirigeraSensorRepository.findByKnownToHubTrue`, `DirigeraSensorReadingRepository.findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc`, `DirigeraEntityMapper.entityId/entityRef`
- Produces: `sensorId` `"dirigera:<id>"`, `source` `"DIRIGERA"`. Name über `temperatureName(EntitySource.DIRIGERA, entityRef(relationId), displayName)`, Anzeigename wie im Mapper-Fallback.
  - In `getSeries` kommt `safe("dirigera", …)` dazu, ebenso in `getCurrent`. `getSensorSeries` erkennt das Präfix `dirigera:`. Eine unbekannte oder nicht-numerische Id oder `knownToHub=false` ⇒ `ResourceNotFoundException`.
  - **`/current` lässt Sensoren mit `reachable=false` weg** (zusätzlich zu `knownToHub=false`), ebenso solche ohne `lastTemperature` oder mit `lastSeenAt` vor `since` (Spec-Entscheidung „Freshness“).

- [ ] **Step 1: Failing tests** (Mock-Repository wie die vorhandenen BLE-Tests in derselben Datei):
  - `dirigeraSerieErscheintInGetSeries`
  - `dirigeraCurrentTraegtEntityIdDesMappers` (`entityId` == `DirigeraEntityMapper.entityId(relationId, TEMPERATURE)`)
  - `unerreichbarerDirigeraSensorFehltInCurrent`
  - `dirigeraEinzelserieUnbekannteIdWirft404`
  - `dirigeraQuellenfehlerKipptGetSeriesNicht`
- [ ] **Step 2: Rot prüfen** `mvn test -Dtest=TemperatureSeriesServiceTest`
- [ ] **Step 3: Implementieren.** Die beiden neuen Repositories kommen in den Konstruktor. Bestehende Tests, die den Service per Konstruktor bauen, bekommen Mocks nachgetragen.
- [ ] **Step 4: Grün prüfen**, danach das ganze Backend: `mvn test`. Erwartet: nur die zwei Baseline-Fails.
- [ ] **Step 5: Commit** `feat(dirigera): TIMMERFLOTTE in der Temperaturansicht`

---

### Task 10: Admin-Seite im Frontend

**Files:**
- Create: `frontend/src/app/models/dirigera.model.ts`
- Create: `frontend/src/app/services/dirigera.service.ts`
- Create: `frontend/src/app/pages/admin-dirigera/admin-dirigera.component.{ts,html,scss,spec.ts}`
- Modify: `frontend/src/app/app.routes.ts` (Route `admin/dirigera`, `adminGuard`, Titel `IKEA Dirigera - Household Manager`, neben `admin/ble-sensors`)
- Modify: `frontend/src/app/components/header/header.component.ts` (Eintrag `{ path: '/admin/dirigera', label: 'IKEA Dirigera', minRole: 'ADMIN' }` unter „Bluetooth-Thermometer“)
- Modify: `frontend/src/styles.scss` (`.admin-dirigera` in `$admin-scopes`)

**Interfaces:**
- Consumes: REST aus Task 8
- Produces:
  - Modelle `DirigeraHub`, `DirigeraPairingStatus` (`state: 'IDLE'|'PRESS_BUTTON'|'PAIRED'|'FAILED'`), `DirigeraSensor`, Felder wie in den DTOs von Task 8
  - `DirigeraService`: `getHub()`, `updateHost(host)`, `startPairing()`, `getPairing()`, `unpair()`, `getSensors()`, `deleteSensor(id)`, alle Pfade relativ zu `/v1/dirigera`, wie in `ble-sensor.service.ts`

**Seite** (Wurzelklasse `admin-dirigera`, Aufbau wie `admin-ble-sensors`):

1. **Hub-Karte**
   - IP-Feld mit Knopf „Speichern“, Status „Gekoppelt“/„Nicht gekoppelt“, „Letzte Abfrage: …“, `lastError` als roter Hinweis.
   - Knopf „Koppeln“. Während `PRESS_BUTTON`: Text „Jetzt den Aktionsknopf an der Unterseite des Hubs drücken“ und Countdown in Sekunden aus `expiresAt` (Uhr des Servers). Polling von `getPairing()` alle 2 s, bis `PAIRED`/`FAILED`, danach `getHub()` und `getSensors()` neu laden. Das Intervall wird in `ngOnDestroy` beendet.
   - Bei `FAILED` steht `error` sichtbar da.
   - „Entkoppeln“ mit `confirm()`-Rückfrage.
2. **Sensoren-Tabelle:** Name, Raum, Temperatur (°C), Feuchte (%), Batterie (%), „zuletzt gesehen“, Status-Badge (aktiv / nicht erreichbar / nicht mehr am Hub). „Löschen“ nur bei `knownToHub=false`.

- [ ] **Step 1: Failing tests** (`admin-dirigera.component.spec.ts`, `HttpTestingController`):
  - `zeigtKoppelHinweisWaehrendPressButton`
  - `pollingEndetBeiPaired` (nach `PAIRED` keine weitere `GET /pairing`-Anfrage, `fakeAsync` + `tick(2000)`)
  - `zeigtFehlertextBeiFailed`
  - `loeschenNurFuerSensorenOhneHub`
  - `pollingEndetBeiDestroy`
- [ ] **Step 2: Rot prüfen** `npm test -- --watch=false --browsers=ChromeHeadless --include='**/admin-dirigera/**'`
- [ ] **Step 3: Implementieren**
- [ ] **Step 4: Grün prüfen**, danach die ganze Suite. Erwartet: nur die 3 Baseline-Fails.
- [ ] **Step 5: Build prüfen** `npx ng build --configuration production`. Ein Budget-ERROR für `dashboard.component.scss` ist vorbestehend und keine Regression.
- [ ] **Step 6: Commit** `feat(dirigera): Admin-Seite IKEA Dirigera`

---

### Task 11: Realtest, Doku

**Files:**
- Modify: `CLAUDE.md`: neuer Abschnitt „IKEA Dirigera (TIMMERFLOTTE)“ unter „Smart Device Integrations“ (nach „Bluetooth-Thermometer“), dazu ein Spiegelstrich bei „Current Features“. Inhalt: Kopplung und TOFU-Pinning, zwei Teilgeräte je Sensor, Rundung, Entity-ID an `relationId`, Neukoppeln übernimmt Identität, Freshness-Regel in `/current`, Security-Matcher, unverifizierte Stale-Schwelle, Rollout mit DHCP-Reservierung.

- [ ] **Step 1: Lokaler Realtest gegen den echten Hub.** Das Backend mit `ZIGBEE_MQTT_ENABLED=false` und `TELEGRAM_ENABLED=false` starten. Geht das lokal mangels DB nicht, den Schritt als offen vermerken und nicht vortäuschen. Dann:
  1. Admin-Seite öffnen, IP `192.168.1.159` speichern, „Koppeln“, den Nutzer den Knopf drücken lassen.
  2. Prüfen, dass `GET /api/v1/dirigera/sensors` vier Sensoren liefert.
  3. Prüfen, dass `GET /api/v1/temperatures/current` vier `dirigera:`-Einträge enthält.
- [ ] **Step 2: CLAUDE.md ergänzen**
- [ ] **Step 3: Commit** `docs: Dirigera-Anbindung in CLAUDE.md`
