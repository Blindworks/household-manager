# Netzwerk-Monitoring + Tablet-Ansicht `/tablet/network` — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Neues Backend-Modul `network/` (Internet-Status/Latenz minütlich, Cloudflare-Speedtest stündlich, LAN-Geräte-Checks minütlich, Entitäten für Flows) plus Tablet-Ansicht `/tablet/network` und Admin-Seite für die Geräteliste.

**Architecture:** Drei unabhängige Messpfade im Modul `backend/src/main/java/com/household/manager/network/`; HTTP-/TCP-Zugriffe hinter Interfaces gekapselt (Tests ohne Netz). Historie in zwei Tabellen mit Lese-Downsampling über den bestehenden `SeriesDownsampler` (Muster Temperatur-Serien) statt Schreib-Kompaktierung — **bewusste Abweichung von der Spec**: bei max. 1440 Zeilen/Tag ist eine Schreib-Kompaktierung unnötige Komplexität; Retention (30 Tage Connectivity, 365 Tage Speedtests) bleibt. Gerätestatus nur im Speicher (Muster `ZigbeeStreamMonitor`).

**Tech Stack:** Spring Boot 3.4.1/Java 21, Liquibase, Lombok; Angular 19 standalone, ECharts, `<app-tablet-shell>`.

**Spec:** `docs/superpowers/specs/2026-08-24-netzwerk-monitoring-design.md` — bei Widerspruch gilt die Spec, außer beim oben begründeten Downsampling-Punkt.

**Wichtige Repo-Regeln (gelten für jede Task):**
- JPA-Repositories MÜSSEN in `com.household.manager.repository` liegen (`JpaConfig` scannt nur dort).
- Backend-Build: JAVA_HOME auf jdk-21.0.10 setzen; lokale DB-Tests schlagen by design fehl (Baseline beachten, `contextLoads` ist rot — das verdeckt Bean-Fehler, deshalb bei Startproblemen die Ursache im Log suchen).
- Scheduled-Methoden werfen nie. `EntityStateService.reportState` nie in einem Zustand aufrufen, den man rät.
- Frontend-Test-Baseline: 3 vorbestehende Fails (App/Hero) + SmartDeviceList-Flake.
- Commits auf einem Feature-Branch `feature/netzwerk-monitoring` (von `main`).

---

### Task 1: Liquibase-Migration, Entities, Repositories

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260824-0049-create-network-tables.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (Include ans Ende, Muster der Nachbarn)
- Create: `backend/src/main/java/com/household/manager/model/entity/NetworkConnectivitySample.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/NetworkSpeedtestResult.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/NetworkDevice.java`
- Create: `backend/src/main/java/com/household/manager/repository/NetworkConnectivitySampleRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/NetworkSpeedtestResultRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/NetworkDeviceRepository.java`

- [ ] **Step 1: Changelog schreiben** — DREI getrennte Changesets (eines je Tabelle; MariaDB committet DDL implizit, siehe Kommentar in `20260727-0044`):
  - `network_connectivity_sample`: `id` BIGINT autoincrement PK, `sampled_at` DATETIME not null (+ Index `idx_network_connectivity_sampled_at`), `online` BOOLEAN not null, `latency_ms` INT nullable, `gateway_reachable` BOOLEAN not null
  - `network_speedtest_result`: `id` BIGINT autoincrement PK, `tested_at` DATETIME not null (+ Index `idx_network_speedtest_tested_at`), `download_mbps` DECIMAL(9,2) nullable, `upload_mbps` DECIMAL(9,2) nullable, `success` BOOLEAN not null, `error_message` VARCHAR(500) nullable
  - `network_device`: `id` BIGINT autoincrement PK, `name` VARCHAR(100) not null, `host` VARCHAR(255) not null, `tcp_port` INT nullable, `sort_order` INT not null default 0, `active` BOOLEAN not null default true
- [ ] **Step 2: Entities mit Lombok** (`@Entity @Table @Data @Builder @NoArgsConstructor @AllArgsConstructor`, Muster `PetFoodTransaction`); Zeitfelder als `LocalDateTime` (Muster der übrigen Reading-Tabellen).
- [ ] **Step 3: Repositories** in `com.household.manager.repository`:

```java
public interface NetworkConnectivitySampleRepository extends JpaRepository<NetworkConnectivitySample, Long> {
    List<NetworkConnectivitySample> findBySampledAtAfterOrderBySampledAtAsc(LocalDateTime after);
    Optional<NetworkConnectivitySample> findTopByOrderBySampledAtDesc();
    long deleteBySampledAtBefore(LocalDateTime cutoff);
}
public interface NetworkSpeedtestResultRepository extends JpaRepository<NetworkSpeedtestResult, Long> {
    List<NetworkSpeedtestResult> findByTestedAtAfterOrderByTestedAtAsc(LocalDateTime after);
    Optional<NetworkSpeedtestResult> findTopBySuccessTrueOrderByTestedAtDesc();
    long deleteByTestedAtBefore(LocalDateTime cutoff);
}
public interface NetworkDeviceRepository extends JpaRepository<NetworkDevice, Long> {
    List<NetworkDevice> findByActiveTrueOrderBySortOrderAscIdAsc();
    List<NetworkDevice> findAllByOrderBySortOrderAscIdAsc();
}
```

- [ ] **Step 4: Kompilieren** — `mvn -q compile` in `backend/` (JAVA_HOME!). Erwartet: BUILD SUCCESS.
- [ ] **Step 5: Commit** `feat(network): Tabellen und Repositories fuer Netzwerk-Monitoring`

---

### Task 2: Connectivity-Poller + Entitäten (TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java` (Konstante `NETWORK` mit Javadoc ergänzen)
- Create: `backend/src/main/java/com/household/manager/network/ConnectivityProbe.java` (Interface)
- Create: `backend/src/main/java/com/household/manager/network/HttpConnectivityProbe.java`
- Create: `backend/src/main/java/com/household/manager/network/TcpPortProbe.java` (Interface + Default-Impl `SocketTcpPortProbe`)
- Create: `backend/src/main/java/com/household/manager/network/NetworkConnectivityPollingService.java`
- Test: `backend/src/test/java/com/household/manager/network/NetworkConnectivityPollingServiceTest.java`

- [ ] **Step 1: Interfaces definieren** — sie sind die Testnaht, KEIN echtes Netz in Unit-Tests:

```java
/** Ein HTTP-Erreichbarkeitsziel. Optional.empty() = nicht erreichbar. */
public interface ConnectivityProbe {
    Optional<Duration> probe(URI target, Duration timeout);
}
/** TCP-Connect-Check. true = Port offen. */
public interface TcpPortProbe {
    boolean isOpen(String host, int port, Duration timeout);
}
```

`HttpConnectivityProbe`: Java-`HttpClient` mit `HttpClient.Version.HTTP_1_1` (bekannte HTTP/2-Falle), GET, misst Wanduhrzeit bis zur Antwort (jeder 2xx/3xx/4xx zählt als erreichbar — es geht um Konnektivität, nicht um den Statuscode); `SocketTcpPortProbe`: `new Socket()` + `connect(new InetSocketAddress(host, port), timeoutMs)` im try-with-resources.

- [ ] **Step 2: Failing Tests für die Bewertungslogik schreiben** (Mocks für beide Probes und `EntityStateService`, `Clock.fixed`):
  - ein Ziel erreichbar, eines nicht ⇒ `online=true`, Latenz = die des erfolgreichen Ziels
  - beide erreichbar ⇒ Latenz = Minimum
  - beide nicht erreichbar ⇒ `online=false`, `latency_ms=null`
  - Gateway-Check unabhängig vom Internet-Urteil (Port 80, bei zu Port 443 probieren)
  - Sample wird gespeichert; `binary_sensor.network_internet` gemeldet (`deviceClass: connectivity` in den Attributen, `latencyMs`, `gatewayReachable`); `sensor.network_latency_ms` NUR bei online gemeldet (offline ⇒ kein Update — kein erfundener Wert)
  - eine werfende Probe kippt den Lauf nicht (Scheduled-Methode wirft nie; Wurf einer Probe = „nicht erreichbar")
- [ ] **Step 3: Tests laufen lassen** — `mvn -q test -Dtest=NetworkConnectivityPollingServiceTest`. Erwartet: FAIL (Klasse fehlt).
- [ ] **Step 4: Service implementieren:**

```java
@Service @RequiredArgsConstructor @Slf4j
public class NetworkConnectivityPollingService {
    private static final URI CLOUDFLARE = URI.create("https://1.1.1.1/cdn-cgi/trace");
    private static final URI GSTATIC = URI.create("https://www.gstatic.com/generate_204");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    // Felder: probes/repo/entityStateService/clock/@Value network.gateway-ip (Default 192.168.1.1)

    @Scheduled(fixedDelayString = "${network.connectivity.poll-interval-ms:60000}")
    public void poll() { /* try/catch um ALLES, log.warn im Fehlerfall */ }
}
```

Kern: beide Ziele nacheinander proben (Wurf je Ziel fangen ⇒ empty), `online = mind. eines erfolgreich`, `latencyMs = min`, Gateway via `tcpPortProbe.isOpen(gatewayIp, 80, 2s) || isOpen(..., 443, 2s)`; Sample speichern; Entitäten melden:

```java
entityStateService.reportState(EntityStateUpdate.builder()
    .entityId("binary_sensor.network_internet").domain(EntityDomain.BINARY_SENSOR)
    .source(EntitySource.NETWORK).sourceRef("internet")
    .friendlyName("Internetverbindung")
    .state(online ? "on" : "off")
    .attributes(Map.of("deviceClass", "connectivity", "latencyMs", ..., "gatewayReachable", ...))
    .build());
```

(`latencyMs` bei offline aus der Attribut-Map weglassen, nicht `null` hineinlegen.) `sensor.network_latency_ms` analog (`domain SENSOR`, `state = String.valueOf(latencyMs)`, Attribut `unit: "ms"`), nur bei online.

- [ ] **Step 5: Tests grün** — gleicher Befehl, Erwartung PASS.
- [ ] **Step 6: Commit** `feat(network): Internet-Status und Latenz minutengenau erfassen`

---

### Task 3: Speedtest-Service + Scheduler + manueller Trigger (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/network/SpeedtestClient.java` (Interface)
- Create: `backend/src/main/java/com/household/manager/network/CloudflareSpeedtestClient.java`
- Create: `backend/src/main/java/com/household/manager/network/NetworkSpeedtestService.java`
- Test: `backend/src/test/java/com/household/manager/network/NetworkSpeedtestServiceTest.java`

- [ ] **Step 1: Interface + Client:**

```java
/** Führt eine Roh-Messung aus. Wirft bei Netzfehlern — der Service übersetzt. */
public interface SpeedtestClient {
    /** @return Mbit/s über das Zeitbudget gemessen */
    BigDecimal measureDownloadMbps(Duration budget) throws IOException, InterruptedException;
    BigDecimal measureUploadMbps(Duration budget) throws IOException, InterruptedException;
}
```

`CloudflareSpeedtestClient`: `HttpClient` HTTP/1.1 erzwungen. Download: `GET https://speed.cloudflare.com/__down?bytes=250000000` als `BodyHandlers.ofInputStream()`, in 64-KB-Puffer lesen und verwerfen, bis das Zeitbudget (Default 10 s, `network.speedtest.budget-seconds`) erreicht ist, dann Stream schließen; Mbit/s = `bytes*8/1e6/sekunden` (BigDecimal, Scale 2, HALF_UP; gemessene Zeit ab dem ERSTEN gelesenen Byte, damit der Verbindungsaufbau die Rate nicht drückt). Upload: `POST https://speed.cloudflare.com/__up` mit `BodyPublishers.ofInputStream` aus einem begrenzten Zufalls-Stream über dasselbe Budget. Beide Messungen unabhängig; wirft eine, ist das Ergebnis der jeweiligen Richtung `null`, aber die andere zählt.

- [ ] **Step 2: Failing Tests für den Service** (Mock `SpeedtestClient`, Mock Repos, Mock `EntityStateService`, `Clock.fixed`):
  - Erfolg ⇒ Ergebnis-Zeile `success=true`, beide Entitäten (`sensor.network_download_mbps`/`_upload_mbps`, Attribut `unit: "Mbit/s"`) gemeldet
  - Client wirft bei Download UND Upload ⇒ Zeile `success=false` mit `error_message`, KEINE Entity-Meldung (letzter guter Wert bleibt)
  - letzter Connectivity-Sample offline ⇒ Scheduler-Lauf macht NICHTS (kein Test, keine Zeile)
  - `runManual()`: zweiter Aufruf < 60 s nach dem ersten ⇒ `TooManyRequestsException` (neue Exception im Modul; Handler-Mapping auf 429 im `GlobalExceptionHandler` ergänzen — Muster `TractiveRateLimitException`); die Sperre ist ein `AtomicReference<Instant>`
  - Scheduled-Methode wirft nie (Repo-Wurf wird gefangen und geloggt)
- [ ] **Step 3: Tests laufen lassen** — FAIL erwartet.
- [ ] **Step 4: Service implementieren** — `@Scheduled(fixedDelayString = "${network.speedtest.interval-ms:3600000}", initialDelayString = "${network.speedtest.initial-delay-ms:120000}")`; `runManual()` teilt sich die Messmethode mit dem Scheduler, prüft aber Cooldown und Offline-Zustand (offline ⇒ `IllegalStateException` mit Klartext „Kein Internet — Speedtest nicht möglich." ⇒ 400).
- [ ] **Step 5: Tests grün.**
- [ ] **Step 6: Commit** `feat(network): stündlicher Cloudflare-Speedtest mit manuellem Trigger`

---

### Task 4: LAN-Geräte-Poller + In-Memory-Status (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/network/NetworkDeviceStatusMonitor.java`
- Create: `backend/src/main/java/com/household/manager/network/NetworkDevicePollingService.java`
- Test: `backend/src/test/java/com/household/manager/network/NetworkDevicePollingServiceTest.java`

- [ ] **Step 1: Monitor** — reiner Speicher (Muster `ZigbeeStreamMonitor`), `ConcurrentHashMap<Long, DeviceStatus>` mit `record DeviceStatus(boolean reachable, Instant lastSeenAt, Instant lastCheckedAt)`; `lastSeenAt` bleibt beim Übergang auf unreachable stehen (das ist die Aussage „zuletzt gesehen"). Methoden `update(deviceId, reachable, now)`, `statusOf(deviceId)`, `remove(deviceId)` (für gelöschte Geräte).
- [ ] **Step 2: Failing Tests:**
  - Gerät mit `tcp_port` gesetzt ⇒ genau dieser Port geprüft
  - ohne Port ⇒ Fallback-Reihe 80, 443, 22, 1883, 8080, 8443 — erster offener beendet die Reihe
  - inaktive Geräte werden nicht geprüft
  - `lastSeenAt` friert beim Ausfall ein, `lastCheckedAt` läuft weiter
  - Wurf der Probe = unreachable, Lauf geht mit dem nächsten Gerät weiter
- [ ] **Step 3: FAIL verifizieren, Step 4: implementieren** (`@Scheduled(fixedDelayString = "${network.devices.poll-interval-ms:60000}")`, nutzt `TcpPortProbe` aus Task 2, Timeout 2 s), **Step 5: PASS.**
- [ ] **Step 6: Commit** `feat(network): LAN-Geraete-Erreichbarkeit pruefen`

---

### Task 5: Retention-Job (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/network/NetworkHistoryRetentionJob.java`
- Test: `backend/src/test/java/com/household/manager/network/NetworkHistoryRetentionJobTest.java`

- [ ] **Step 1: Failing Tests:** Cutoffs korrekt (Connectivity 30 Tage, Speedtests 365 Tage, aus `Clock`), Repo-Wurf wird gefangen.
- [ ] **Step 2: Implementieren** — `@Scheduled(cron = "0 20 3 * * *")` (nachts, nicht zur vollen Stunde — dort läuft der Speedtest), zwei `deleteBy…Before`-Aufrufe, `@Transactional`, try/catch, Log der gelöschten Zeilenzahlen.
- [ ] **Step 3: PASS, Step 4: Commit** `feat(network): Retention fuer Netzwerk-Historie`

---

### Task 6: Geräte-Verwaltung (CRUD + Audit, TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/network/NetworkDeviceService.java`
- Create: `backend/src/main/java/com/household/manager/network/NetworkDtos.java` (alle DTOs des Moduls als Records, Muster `PetFoodDtos`)
- Test: `backend/src/test/java/com/household/manager/network/NetworkDeviceServiceTest.java`

- [ ] **Step 1: Failing Tests:** create/update/delete mit Audit (`network.device.create/update/delete`, Detail enthält Name+Host); Validierung: `name`/`host` Pflicht (400 via `IllegalArgumentException`), `tcp_port` falls gesetzt 1..65535; delete räumt den Monitor-Eintrag ab (`monitor.remove(id)`).
- [ ] **Step 2: FAIL, Step 3: implementieren, Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(network): Geraeteliste pflegbar mit Audit`

---

### Task 7: Controller + History-Service (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/network/NetworkStatusService.java`
- Create: `backend/src/main/java/com/household/manager/network/NetworkHistoryService.java`
- Create: `backend/src/main/java/com/household/manager/network/NetworkController.java`
- Test: `backend/src/test/java/com/household/manager/network/NetworkControllerTest.java` (MockMvc-Standalone, Muster `PetFoodController`-Test)

- [ ] **Step 1: DTOs in `NetworkDtos`:**

```java
record DeviceStatusResponse(Long id, String name, String host, boolean reachable, Instant lastSeenAt) {}
record SpeedtestSummary(LocalDateTime testedAt, BigDecimal downloadMbps, BigDecimal uploadMbps, boolean success, String errorMessage) {}
record StatusResponse(boolean online, Integer latencyMs, boolean gatewayReachable,
        LocalDateTime lastCheckedAt, SpeedtestSummary lastSpeedtest, List<DeviceStatusResponse> devices) {}
record SpeedtestPoint(LocalDateTime time, BigDecimal downloadMbps, BigDecimal uploadMbps) {}
record HistoryResponse(List<TimeValue> latency, List<SpeedtestPoint> speedtests) {}
record DeviceRequest(String name, String host, Integer tcpPort, Integer sortOrder, Boolean active) {}
record DeviceAdminResponse(Long id, String name, String host, Integer tcpPort, int sortOrder, boolean active) {}
```

- [ ] **Step 2: Failing Controller-Tests:** `GET /api/v1/network/status` (Status aus jüngstem Sample + Monitor + letztem Speedtest; noch nie gepollt ⇒ `online=false`? NEIN — dann `online=true, latencyMs=null, lastCheckedAt=null` wäre geraten. Festgelegt: ohne ein einziges Sample liefert `status` `online=false` mit `lastCheckedAt=null`, das Frontend zeigt dafür „noch keine Messung" statt rot); `GET /history?range=WEEK` (Latenz nur aus Online-Samples, gedownsampled via `SeriesRange`/`SeriesDownsampler`; Speedtest-Punkte nur `success=true`); `POST /speedtest` (delegiert an `runManual`, 429-Pfad); Geräte-CRUD delegiert an Task-6-Service.
- [ ] **Step 3: FAIL, Step 4: implementieren** — `@RestController @RequestMapping("/v1/network")`; `NetworkHistoryService` lädt `findBySampledAtAfter…(now - range.getDays())`, mappt Online-Samples mit Latenz auf `TimeValue` und downsampelt. **Step 5: PASS.**
- [ ] **Step 6: Commit** `feat(network): Status-, Historien- und Speedtest-API`

---

### Task 8: Security + Konfiguration

**Files:**
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` (Pfad ggf. per Glob verifizieren)
- Modify: `backend/src/main/resources/application.properties`

- [ ] **Step 1: Failing SecurityRulesTest-Fälle:** KIOSK darf `GET /v1/network/status` (generische Regel) und `POST /v1/network/speedtest`; KIOSK darf NICHT `POST /v1/network/devices`; ADMIN darf Geräte-Schreibzugriffe; MEMBER darf sie NICHT.
- [ ] **Step 2: `SecurityConfig` ergänzen** — in der KIOSK-POST-Whitelist-Zeile `"/v1/network/speedtest"` aufnehmen; VOR den generischen Regeln methodenspezifische ADMIN-Matcher (Muster Kalender-Kategorien, Kommentar übernehmen):

```java
.requestMatchers(HttpMethod.POST, "/v1/network/devices").hasRole("ADMIN")
.requestMatchers(HttpMethod.PUT, "/v1/network/devices/*").hasRole("ADMIN")
.requestMatchers(HttpMethod.DELETE, "/v1/network/devices/*").hasRole("ADMIN")
```

- [ ] **Step 3: `application.properties`:** `network.gateway-ip=${NETWORK_GATEWAY_IP:192.168.1.1}` plus die drei Intervall-/Budget-Properties mit Defaults (Kommentarzeile je Property, Stil der Datei übernehmen).
- [ ] **Step 4: Tests grün, Step 5: Commit** `feat(network): Security-Regeln und Konfiguration`
- [ ] **Step 6: Backend-Gesamtlauf** `mvn -q test` — Erwartung: nur die bekannte DB-Baseline rot, sonst grün.

---

### Task 9: Frontend-Service + Modelle (TDD)

**Files:**
- Create: `frontend/src/app/models/network.model.ts` (Interfaces spiegeln die DTOs aus Task 7; `TimeRange` aus `models/temperature.model.ts` wiederverwenden)
- Create: `frontend/src/app/services/network.service.ts`
- Test: `frontend/src/app/services/network.service.spec.ts`

- [ ] **Step 1: Failing Spec** (HttpTestingController, Muster `system.service.spec.ts`): `getStatus()` ⇒ `GET /api/v1/network/status`; `getHistory('WEEK')` ⇒ `GET /api/v1/network/history?range=WEEK`; `runSpeedtest()` ⇒ `POST /api/v1/network/speedtest`; Admin-CRUD-Methoden.
- [ ] **Step 2: FAIL verifizieren** (`npm test`-Headless-Kommando aus der Frontend-Baseline-Memory), **Step 3: implementieren, Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(network): Frontend-Service und Modelle`

---

### Task 10: Tablet-Ansicht `/tablet/network`

**Files:**
- Create: `frontend/src/app/pages/tablet-network/tablet-network.component.{ts,html,scss,spec.ts}`
- Modify: `frontend/src/app/shared/tablet-views.ts` (Eintrag `{ route: '/tablet/network', icon: 'wifi', label: 'Netzwerk' }`)
- Modify: `frontend/src/app/app.routes.ts` (lazy Route nach dem Muster der Nachbarn)

Struktur nach dem Muster `tablet-air-quality.component.ts` / `tablet-consumption` (dort abschauen!). Vier Kacheln (2×2-Grid):

1. **Status:** Online/Offline groß (grün `#22c55e` / rot `#ef4444`), Latenz, Gateway-Status, letzter Speedtest (Zeit + Down/Up), Knopf „Jetzt testen" → `runSpeedtest()`; Erfolg lädt Status neu, Fehler zeigt die Server-Meldung (429-Text bei Doppelklick) als Inline-Hinweis in der Kachel. Ohne jede Messung (`lastCheckedAt === null`): neutraler Text „Noch keine Messung" statt rot.
2. **Speed-Verlauf:** ECharts, zwei Linien-Serien (Down/Up, Mbit/s, `showSymbol: true` — es sind nur ~24 Punkte/Tag), Zeit-Achse.
3. **Latenz-Verlauf:** eine Linie; **Lücken bei Offline-Fenstern:** vor dem Zeichnen `null`-Punkte einfügen, wo der Abstand zweier Nachbarpunkte > 3× der Bucket-Länge des Zeitraums ist (`DAY` 5 min, `WEEK` 30 min, `MONTH` 2 h — Konstante im Component-File, Kommentar: gespiegelt aus `SeriesRange`), `connectNulls: false`.
4. **Geräte:** Liste mit grünem/rotem Punkt, Name, „zuletzt gesehen: <relativ formatierte Zeit>" (bei `lastSeenAt === null`: „–").

- [ ] **Step 1: Failing Specs schreiben:**
  - Rendert vier Kacheln bei gefülltem Status/History-Stub
  - **Höhenketten-Test** bei 900 und 1200 px: Host in ein SELBST ERZEUGTES `div` hängen (nicht `host.parentElement` — Karma-Body-Falle, siehe Kommentar in `tablet-air-quality.component.spec.ts`), Chart-Element muss mit der Containerhöhe wachsen
  - **Abbruch-Test mit `Subject`** (nicht `of(...)`): Zeitraumwechsel während laufendem History-Abruf ⇒ die alte Antwort überschreibt die neue nicht (`pendingRequest.unsubscribe()`-Muster aus `tablet-consumption.component.ts`)
  - Fehler beim Erstabruf ⇒ Fehlermeldung; Fehler beim Hintergrund-Refresh ⇒ letzte Werte bleiben, keine Meldung
  - Lücken-Logik: zwei Punkte mit > 3× Bucket-Abstand erzeugen einen `null`-Zwischenpunkt in den Chart-Daten
- [ ] **Step 2: FAIL, Step 3: Komponente implementieren** — Selbst-Refresh alle 60 s (`REFRESH_INTERVAL_MS = 60_000`), Zeitraumwahl (24 h/7 Tage/30 Tage, Default `WEEK`) im `[shellActions]`-Slot der `<app-tablet-shell heading="Netzwerk">`; Status und History parallel laden, aber unabhängig fehlertolerant (fällt History aus, steht die Status-Kachel trotzdem).
- [ ] **Step 4: PASS** (Baseline-Fails beachten), **Step 5: Commit** `feat(tablet): Netzwerk-Ansicht mit Speed- und Latenzverlauf`

---

### Task 11: Admin-Seite „Netzwerk-Geräte"

**Files:**
- Create: `frontend/src/app/pages/admin/network-devices/network-devices.component.{ts,html,scss,spec.ts}` (exakte Ablage/Namensmuster an `admin/calendar-categories` ausrichten — vorher per Glob nachsehen)
- Modify: `frontend/src/app/app.routes.ts` (Route `admin/network-devices` mit `adminGuard`, Muster der bestehenden Admin-Routen)
- Modify: Header-Navigation (Datei der bestehenden Admin-Menüpunkte, per Grep nach `admin/calendar-categories` finden)

- [ ] **Step 1: Failing Specs:** Liste rendert, Anlegen/Ändern/Löschen ruft den Service, Validierungsfehler (leerer Name) blockt das Speichern clientseitig, Port-Feld optional.
- [ ] **Step 2: FAIL, Step 3: implementieren** — schlichte Tabelle + Inline-Formular (Muster Kalender-Kategorien-Seite: gleiche Interaktionsform übernehmen, inkl. Aktiv-Toggle und Sortierreihenfolge). **Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(admin): Netzwerk-Geraete pflegen`

---

### Task 12: Abschluss

- [ ] **Step 1:** Backend-Gesamtlauf `mvn -q test` (nur DB-Baseline rot) und Frontend-Gesamtlauf (nur bekannte Baseline-Fails).
- [ ] **Step 2:** `ng build --configuration production` — Achtung: nur das bekannte `dashboard.component.scss`-Budget darf meckern; neue Budget-ERRORs der neuen Dateien wären echte Regressionen.
- [ ] **Step 3:** CLAUDE.md um einen Abschnitt „Netzwerk-Monitoring" ergänzen (Kurzform: Modul, Messpfade, Entitäten inkl. Telegram-erst-nach-Rückkehr-Hinweis, Security-Zeilen, Tablet-Ansicht, bewusste Grenzen inkl. Downsampling-Abweichung).
- [ ] **Step 4:** Commit `docs(claude): Netzwerk-Monitoring festhalten`; Branch für Review/Merge melden (superpowers:finishing-a-development-branch).
