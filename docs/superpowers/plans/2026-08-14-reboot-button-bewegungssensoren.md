# Bewegungssensoren-Modus + Reboot-Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modus „Bewegungssensoren" in die Dashboard-Modus-Leiste aufnehmen; Modus „Ausschalten" durch einen Reboot-Aktions-Button ersetzen, der nach Bestätigung alle Compose-Container über einen Rebooter-Sidecar neu startet.

**Architecture:** Katalog-Änderung in `HouseModes` + idempotente Bereinigung im `HouseModeInitializer`. Neuer Backend-Endpunkt `POST /v1/system/reboot` (KIOSK-Whitelist, Audit), der einen neuen zustandslosen Rebooter-Sidecar (Python + docker-cli, Docker-Socket, nur `app_net`) mit Shared-Token aufruft. Frontend: fester Aktions-Button am Ende der Modus-Leiste mit Bestätigungsdialog und Reload-Polling.

**Tech Stack:** Spring Boot 3.4 / Java 21, Angular 19, Python 3 (stdlib) im Sidecar, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-14-reboot-button-bewegungssensoren-design.md`

**Build-Umgebung:** Backend: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` vor jedem `mvn`, aus `backend/`. DB-abhängige Tests (`contextLoads`, `HealthControllerTest`) schlagen lokal umgebungsbedingt fehl — ignorieren. Frontend: `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`; Baseline: 3 vorbestehende Fails (App/Hero) + gelegentliche SmartDeviceList-Flake.

---

### Task 1: HouseModes-Katalog + Initializer-Bereinigung (Backend)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/HouseModes.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/HouseModeInitializer.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/HouseModeInitializerTest.java` (existiert evtl. — sonst anlegen)

- [ ] **Step 1: Failing Tests schreiben** — im Initializer-Test (bestehende Testklasse erweitern oder neu, Mockito-Style wie übrige Unit-Tests):
  - `loeschtAltmodusAusschaltenMitMarker`: `entityStateService.getByEntityId("input_boolean.manual_ausschalten")` liefert eine Entity mit Attributen `{"mode": true}` → `seedHouseModes()` ruft `entityStateService.deleteByEntityId("input_boolean.manual_ausschalten")`.
  - `loeschtHelferOhneMarkerNicht`: gleiche Entity ohne Marker → kein `deleteByEntityId`-Aufruf.
  - `bereinigt­NichtsWennEntityFehlt`: `Optional.empty()` → kein `deleteByEntityId`-Aufruf.
- [ ] **Step 2: Test läuft rot** — `mvn test -Dtest=HouseModeInitializerTest`
- [ ] **Step 3: Implementierung**
  - `HouseModes.CATALOG`: Eintrag `new HouseModeDefinition("Ausschalten", "power_settings_new")` entfernen, `new HouseModeDefinition("Bewegungssensoren", "sensors")` ans Ende.
  - Neue Konstante in `HouseModes`: `public static final String RETIRED_SHUTDOWN_ENTITY_ID = "input_boolean.manual_ausschalten";` (mit Javadoc: ersetzt durch den Reboot-Button, Bereinigung im Initializer).
  - `HouseModeInitializer.seedHouseModes()`: nach der Seed-Schleife `cleanUpRetiredShutdownMode()` aufrufen (eigener try/catch, Fehler nur loggen): Entity laden; wenn vorhanden **und** `HouseModes.isMode(parseAttributes(...))` → `entityStateService.deleteByEntityId(...)` + `log.info`.
- [ ] **Step 4: Tests grün** — `mvn test -Dtest=HouseModeInitializerTest`
- [ ] **Step 5: Commit** — `feat(backend): Bewegungssensoren-Modus, Ausschalten-Modus bereinigt`

### Task 2: Rebooter-Sidecar

**Files:**
- Create: `rebooter/server.py`
- Create: `rebooter/Dockerfile`

- [ ] **Step 1: `server.py`** — Python-stdlib `http.server`; Verhalten:
  - Start: `REBOOTER_TOKEN` aus Env lesen; leer → Log + `sys.exit(1)` (fail-closed).
  - `POST /reboot` mit Header `X-Rebooter-Token` == Token → sofort `202`, danach in einem Thread nach 1 s Verzögerung: Compose-Projekt per `docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' $(hostname)` ermitteln, `docker ps -q --filter label=com.docker.compose.project=<projekt>` listen, eigene Container-ID (Hostname-Präfix) ausnehmen, alle übrigen `docker restart`.
  - Falscher/fehlender Token → `403`; andere Pfade/Methoden → `404`/`405`. Alles loggen (stdout).
- [ ] **Step 2: `Dockerfile`** — `FROM alpine:3.20`, `RUN apk add --no-cache docker-cli python3`, `COPY server.py /server.py`, `EXPOSE 8095`, `CMD ["python3", "/server.py"]`.
- [ ] **Step 3: Syntax-Check** — `python -m py_compile rebooter/server.py` (lokal vorhandenes Python reicht).
- [ ] **Step 4: Commit** — `feat(rebooter): Sidecar fuer Container-Neustart`

### Task 3: Compose-Verdrahtung

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Service `rebooter`** ergänzen (nur `app_net`, kein Port-Mapping, Socket-Mount, Kommentar warum kein LAN-Port — Muster blink-vision):
  ```yaml
  rebooter:
    build:
      context: ./rebooter
    restart: unless-stopped
    environment:
      REBOOTER_TOKEN: ${REBOOTER_TOKEN:-}
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    networks:
      - app_net
  ```
- [ ] **Step 2: Backend-Envs** im `backend`-Service: `REBOOTER_URL: http://rebooter:8095` und `REBOOTER_TOKEN: ${REBOOTER_TOKEN:-}`.
- [ ] **Step 3: Commit** — `feat(deploy): Rebooter-Sidecar im Compose`

### Task 4: Backend-Endpunkt `POST /v1/system/reboot`

**Files:**
- Create: `backend/src/main/java/com/household/manager/system/RebooterProperties.java`
- Create: `backend/src/main/java/com/household/manager/system/RebooterException.java`
- Create: `backend/src/main/java/com/household/manager/system/RebooterClient.java`
- Create: `backend/src/main/java/com/household/manager/system/SystemRebootService.java`
- Create: `backend/src/main/java/com/household/manager/system/SystemController.java`
- Modify: `backend/src/main/resources/application.properties` (Abschnitt Rebooter)
- Modify: `backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java` (502-Handler für `RebooterException`, Muster `handleVisionException`)
- Test: `backend/src/test/java/com/household/manager/system/SystemRebootServiceTest.java`

- [ ] **Step 1: Failing Tests** (`SystemRebootServiceTest`, Mockito):
  - `wirftOhneKonfiguration`: Properties ohne URL/Token → `IllegalStateException` mit Klartext („Reboot ist nicht konfiguriert…"), kein Client-Aufruf, kein Audit.
  - `auditVorSidecarAufruf`: konfiguriert → `auditService.record("system.reboot", …)` wird vor `rebooterClient.triggerReboot()` aufgerufen (InOrder).
  - `reichtSidecarFehlerDurch`: Client wirft `RebooterException` → propagiert (Audit trotzdem geschrieben, da vorher).
- [ ] **Step 2: rot** — `mvn test -Dtest=SystemRebootServiceTest`
- [ ] **Step 3: Implementierung**
  - `RebooterProperties`: `@ConfigurationProperties(prefix = "rebooter")`, Felder `baseUrl`, `token`; `application.properties`: `rebooter.base-url=${REBOOTER_URL:}` und `rebooter.token=${REBOOTER_TOKEN:}`.
  - `RebooterClient` (Muster `AlexaSidecarClient`, `java.net.http.HttpClient`, kurze Timeouts): `triggerReboot()` → `POST {baseUrl}/reboot`, Header `X-Rebooter-Token`; Status != 202 oder IO-Fehler → `RebooterException`.
  - `SystemRebootService.reboot()`: Konfig-Check (URL **und** Token nicht blank, sonst `IllegalStateException` → 400) → `auditService.record("system.reboot", "alle Container")` → `rebooterClient.triggerReboot()`.
  - `SystemController`: `@PostMapping("/v1/system/reboot")` → Service, `202 Accepted` ohne Body.
  - `GlobalExceptionHandler`: `@ExceptionHandler(RebooterException.class)` → 502 (Muster Vision).
- [ ] **Step 4: grün** — `mvn test -Dtest=SystemRebootServiceTest`
- [ ] **Step 5: Commit** — `feat(backend): Reboot-Endpunkt mit Rebooter-Client`

### Task 5: Security-Regel + SecurityRulesTest

**Files:**
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java` (KIOSK-POST-Whitelist um `"/v1/system/reboot"` ergänzen)
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Failing Test** — `kioskDarfRebootAusloesen`: `@WithMockUser(roles = "KIOSK")`, `post("/v1/system/reboot").with(csrf())` → nicht 403 (Slice ohne SystemController: 404 belegt Durchlass; alternativ SystemController in den Slice aufnehmen und Service mocken → 202).
- [ ] **Step 2: rot** — `mvn test -Dtest=SecurityRulesTest` (Test erwartet 403 → schlägt fehl)
- [ ] **Step 3: Regel ergänzen** — `"/v1/system/reboot"` in die bestehende KIOSK-POST-Matcher-Liste (`SecurityConfig.filterChain`).
- [ ] **Step 4: grün** — `mvn test -Dtest=SecurityRulesTest`
- [ ] **Step 5: Commit** — `feat(backend): Reboot in der KIOSK-Whitelist`

### Task 6: Frontend — SystemService + Reboot-Button + Dialog + Reload-Polling

**Files:**
- Create: `frontend/src/app/services/system.service.ts` (+ `system.service.spec.ts`)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts`

- [ ] **Step 1: `SystemService`** — Servicemuster wie `ModeService`: `reboot(): Observable<void>` → `POST /api/v1/system/reboot`, `handleError` mit Klartext.
- [ ] **Step 2: Failing Tests** (dashboard.component.spec.ts):
  - Reboot-Button rendert am Ende der Modus-Leiste (`.lumina__mode--reboot`).
  - Klick öffnet den Dialog (`rebootConfirm === true`), ruft den Service **nicht**.
  - `confirmReboot()` ruft `SystemService.reboot`, schließt den Dialog, setzt `rebootInProgress`.
  - `cancelReboot()` schließt ohne Service-Aufruf.
- [ ] **Step 3: rot laufen lassen**
- [ ] **Step 4: Implementierung**
  - `MODE_TONES` prüfen/anpassen: bisher endete die Liste mit `error` für „Ausschalten" — Bewegungssensoren (jetzt Index 3) bekommt `neutral`; der Ton `error` wandert fest an den Reboot-Button.
  - Neuer Zustand: `rebootConfirm = false`, `rebootInProgress = false`; Methoden `openRebootDialog()`, `cancelReboot()`, `confirmReboot()`.
  - `confirmReboot()`: Dialog zu, `rebootInProgress = true`, `SystemService.reboot()`; Fehler → `modeError` + `rebootInProgress = false`. Erfolg → nach 15 s alle 5 s `GET /api/v1/health` (HttpClient direkt oder via Service); erste erfolgreiche Antwort → `location.reload()` (in Methode `reloadPage()` kapseln, damit der Spec sie stubben kann). Timer in `ngOnDestroy` aufräumen; Escape-Handler um `rebootConfirm` ergänzen.
  - HTML: nach der `*ngFor`-Modus-Schleife ein fester Button (gleiche `lumina-card lumina__mode`-Optik, Klasse `lumina__mode--reboot lumina__mode--error`, Icon `restart_alt`, Label „Reboot", `[class.lumina__mode--pending]="rebootInProgress"`). Bestätigungsdialog nach Nuki-Muster (Backdrop, `lumina__dialog--confirm`, Hinweistext „System neu starten? Das Dashboard ist danach kurz nicht erreichbar.", Buttons „Neu starten" / „Abbrechen"). Während `rebootInProgress` ein Overlay/Hinweis „Neustart läuft — die Seite lädt gleich automatisch neu."
  - SCSS: `.lumina__mode--reboot` nur falls nötig (der bestehende `--error`-Ton trägt die Optik); Overlay-Stil minimal halten (`lumina`-Kapselung beachten: alles in `dashboard.component.scss`). Achtung `anyComponentStyle`-Budget: Datei ist am Limit — sparsam stylen.
- [ ] **Step 5: grün** — `npm test -- --watch=false --browsers=ChromeHeadless` (Baseline 3 Fails beachten)
- [ ] **Step 6: Commit** — `feat(frontend): Reboot-Button mit Bestaetigungsdialog`

### Task 7: Verifikation gesamt

- [ ] **Step 1:** Backend komplett: `mvn test` (DB-bedingte Fails ignorieren, keine neuen Fails)
- [ ] **Step 2:** Frontend komplett: Baseline-Vergleich
- [ ] **Step 3:** `ng build --configuration production` (Budget-Check der SCSS)
- [ ] **Step 4:** Commit übriger Änderungen, Memory-Update (Rollout: `REBOOTER_TOKEN` setzen, Sidecar bauen)
