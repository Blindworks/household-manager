# Moderne TP-Link-Leuchtmittel + Fähigkeiten-Modell — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Moderne TP-Link-Leuchtmittel (Port 80, KLAP/AES) einbinden und mit Helligkeit, Farbe und Farbtemperatur steuerbar machen — über API, Dashboard und einen Flow-Node.

**Architecture:** Der Protokollstack existiert bereits (`TapoKlapDeviceConnection`, `TapoAesDeviceConnection`, `TapoDeviceFactory`, `TapoDiscoveryService`). Neu sind: ein Fähigkeiten-Modell aus der Selbstauskunft der Geräte, die Übernahme lokal gefundener Geräte ohne Cloud-Eintrag, Licht-Steuerung über `set_device_info`, UI-Bedienelemente und der Flow-Node `light-set`.

**Tech Stack:** Spring Boot 3.4.1 / Java 21, Angular 19, bestehende Tapo-Klassen.

**Spec:** `docs/superpowers/specs/2026-08-18-tplink-leuchtmittel-faehigkeiten-design.md`

---

## Umgebungs-Hinweise (für jede Aufgabe)

- Maven braucht JDK 21: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"`, Ausführung aus `backend/`. Kein `mvnw`.
- **Bekannte, zu ignorierende Fehlschläge:** `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` (2 Methoden) — keine lokale Test-DB. Jeder andere Fehlschlag ist eine Regression.
- Frontend-Build: `cd frontend && npx ng build --configuration production`. Nur die bekannte `dashboard.component.scss`-Budget-Warnung und die Leaflet-CommonJS-Warnung sind akzeptabel.
- Frontend-Tests: 3 vorbestehende Fails (AppComponent/HeroComponent) sind Baseline.
- Commit-Messages enden mit Leerzeile und `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Zugangsdaten stehen in `backend/src/main/resources/application.properties` als `tapo.email` / `tapo.password`. **Niemals in Logs, Tests, Commits oder Berichte schreiben.**

---

### Task 1: Diagnose — Handshake beweisen und `get_device_info` erfassen

**Zweck:** Die zwei offenen Risiken der Spec klären, bevor irgendetwas darauf aufbaut: Gelingt der Handshake mit den vorhandenen Zugangsdaten gegen 192.168.1.114? Und wie heißen die Felder wirklich?

**Abweichung von der Spec (bewusst):** Statt eines dauerhaften Diagnose-Endpunkts ein **manuell ausführbarer Test**. Er braucht weder laufende Datenbank noch Web-Schicht noch Security und hinterlässt keine Angriffsfläche im Produktionscode.

**Files:**
- Create: `backend/src/test/java/com/household/manager/tapo/TapoLocalProbeManualTest.java`

- [ ] **Step 1: Diagnose-Test schreiben**

Der Test läuft nur, wenn er ausdrücklich angefordert wird — `@EnabledIfSystemProperty(named = "probeEnabled", matches = "true")`, damit er im normalen `mvn test` nicht mitläuft. Er liest die IP aus der System-Property `probe.ip` und die Zugangsdaten aus `application.properties` (klassisches `Properties`-Load vom Klassenpfad, **kein** Spring-Context, damit weder Datenbank noch Web-Schicht nötig sind). Er baut über `TapoDeviceFactory` eine Verbindung — erst `KLAP`, bei Fehlschlag `AES` — ruft `get_device_info` und gibt die Antwort formatiert aus.

Bei einem Authentifizierungs-Fehlschlag die Ausnahme im Klartext ausgeben, aber **niemals** das Passwort. E-Mail-Adresse ebenfalls nicht ausgeben (nur „Konto 1 / Konto 2").

Orientierung vor dem Schreiben: `TapoDeviceFactory.create(...)` erwartet `httpClient`, `objectMapper`, `username`, `password`, `ipAddress` und den `TapoAuthProtocol`. Lies `TapoKlapDeviceConnection` und `TapoLocalDeviceConnection`, um die tatsächliche Methode für einen `get_device_info`-Aufruf zu finden — Methodennamen nicht raten.

- [ ] **Step 2: Test gegen das echte Gerät ausführen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=TapoLocalProbeManualTest -DfailIfNoTests=false -DprobeEnabled=true -Dprobe.ip=192.168.1.114
```

Erwartet: entweder die vollständige `get_device_info`-Antwort **oder** eine klare Authentifizierungs-Fehlermeldung. Beides ist ein verwertbares Ergebnis.

Zur Einordnung, falls die Verbindung gar nicht zustande kommt: gemessen am 2026-08-18 ist auf 192.168.1.114 Port 9999 geschlossen und Port 80 offen; `POST /app` antwortet mit `{"error_code":1003}`.

- [ ] **Step 3: Ergebnis festhalten**

Die rohe Antwort im Bericht an den Koordinator zurückgeben — Feldnamen und Wertebereiche vollständig, aber **ohne** Seriennummer, MAC, SSID oder Standortdaten (falls das Gerät so etwas mitliefert, durch `<entfernt>` ersetzen). Diese Antwort ist die Grundlage für Task 2 und Task 4.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/household/manager/tapo/TapoLocalProbeManualTest.java
git commit -m "test(tapo): manueller Lokal-Probe fuer moderne TP-Link-Geraete"
```

**STOPP nach dieser Aufgabe.** Der Koordinator zeigt dem Nutzer das Ergebnis, bevor Task 2 beginnt. Schlägt der Handshake fehl, ändert sich der weitere Plan (zweites Konto nötig).

---

### Task 2: Fähigkeiten aus der Selbstauskunft ableiten

**Files:**
- Create: `backend/src/main/java/com/household/manager/tapo/TapoCapabilityMapper.java`
- Create: `backend/src/test/java/com/household/manager/tapo/TapoCapabilityMapperTest.java`
- Modify: `backend/src/main/java/com/household/manager/service/SmartDeviceService.java` (Tapo-Upsert, heute `device.setCapabilities("SWITCH")`)

- [ ] **Step 1: Test zuerst.** Als Fixture die **echte** `get_device_info`-Antwort aus Task 1 verwenden, nicht eine erfundene. Erwartungen: Feld `brightness` ⇒ `BRIGHTNESS`; `hue` und `saturation` ⇒ `COLOR`; `color_temp` bzw. ein Farbtemperaturbereich ⇒ `COLOR_TEMP`; immer zusätzlich `SWITCH`. Eine Steckdosen-Antwort ergibt nur `SWITCH`. Die Reihenfolge muss stabil sein — sonst wechselt der DB-Wert bei jedem Scan und löst unnötige Änderungs-Events aus.
- [ ] **Step 2: Test laufen lassen — muss fehlschlagen** (Klasse existiert noch nicht).
- [ ] **Step 3: Mapper implementieren.** Rückgabe als kommaseparierter String, passend zur bestehenden `capabilities`-Spalte (String — **keine Migration nötig**).
- [ ] **Step 4: Im Tapo-Upsert anwenden.** Liegt keine `get_device_info`-Antwort vor (reines Cloud-Gerät, offline, Anmeldung fehlgeschlagen), bleibt der **bisherige** Wert erhalten statt auf `SWITCH` zurückzufallen — sonst verliert eine offline gegangene Lampe ihre Fähigkeiten und die Bedienelemente verschwinden aus der UI.
- [ ] **Step 5:** `mvn test` (nur die 3 bekannten Fehler), Commit `feat(tapo): Faehigkeiten aus der Geraete-Selbstauskunft ableiten`.

---

### Task 3: Ohne lokale Discovery steuerbar bleiben (+ lokal-only-Geräte übernehmen)

**Nachgezogen nach dem Ergebnis von Task 1.** Das gemeldete Gerät ist die Tapo-Birne `Flur`
(L530) und steht bereits in der Cloud-Liste — die Filterlogik war also **nicht** die Ursache
für dieses Gerät. Die reale Ursache ist eine andere: `upsertTapoDevice` setzt IP und
`authProtocol` **nur**, wenn die lokale Discovery das Gerät gefunden hat
(`device.setOnline(localDevice != null)`). Im Docker-Bridge-Netz von PROD findet sie nie
etwas, also bleibt die IP leer, der Live-Abruf scheitert und alle neun Tapo-Geräte stehen
dauerhaft auf „offline" — obwohl sie per Unicast erreichbar wären (gegen 192.168.1.114
nachgewiesen).

Diese Aufgabe deckt daher beides ab: die IP/Protokoll-Zuordnung ohne lokale Discovery
(manuell setzbar, analog zum Kasa-per-IP-Weg) **und** die Übernahme lokal gefundener
Geräte, die in keinem Cloud-Konto stehen.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/SmartDeviceService.java` (`scanTapoDevices`, `discoverLocalTapoDevices`, `upsertTapoDevice`)
- Modify: `backend/src/test/java/com/household/manager/service/SmartDeviceServiceTest.java`

- [ ] **Step 1: Tests zuerst.** (a) Ein nur lokal gefundenes Gerät wird angelegt, Name und Modell aus `get_device_info`. (b) Ein Gerät in beiden Quellen wird genau **einmal** angelegt (Schlüssel: Geräte-ID). (c) Ein reines Cloud-Gerät verhält sich unverändert. (d) Scheitert die Anmeldung an einem lokal gefundenen Gerät, wird es trotzdem angelegt, aber als nicht angemeldet markiert (`online=false` plus Klartext-Hinweis in den Metadaten) — und der Scan läuft für die übrigen Geräte weiter.
- [ ] **Step 2: Fehlschlag bestätigen.**
- [ ] **Step 3: `scanTapoDevices` umbauen** — Cloud-Liste und lokale Liste über die Geräte-ID **zusammenführen** statt die Cloud-Liste zu filtern.
- [ ] **Step 4:** `mvn test`, Commit `feat(tapo): lokal gefundene Geraete ohne Cloud-Eintrag uebernehmen`.

---

### Task 4: Licht-Steuerung — Service und API

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tapo/TapoDeviceService.java`
- Create: `backend/src/main/java/com/household/manager/dto/LightStateRequest.java`
- Modify: `backend/src/main/java/com/household/manager/service/SmartDeviceService.java`, `backend/src/main/java/com/household/manager/controller/SmartDeviceController.java`
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Tests zuerst.** Wertebereiche werden validiert (`brightness` 1–100, `hue` 0–360, `saturation` 0–100, `colorTemp` im vom Gerät gemeldeten Bereich). Eine Fähigkeit, die das Gerät **nicht** meldet, ergibt **400** — nicht stilles Ignorieren, sonst glaubt der Nutzer, die Farbe sei gesetzt. Ein Request ohne jedes Feld ergibt 400. SecurityRules: KIOSK darf nicht, MEMBER darf (Muster der bestehenden Futtervorrat-Fälle).
- [ ] **Step 2: Fehlschlag bestätigen.**
- [ ] **Step 3: Implementieren.** `TapoDeviceService.setLightState(deviceId, ip, protocol, LightState)` über `set_device_info`; die exakten Feldnamen stammen aus dem Ergebnis von Task 1. Endpunkt `PUT /devices/{id}/light`. Audit-Eintrag `device.light.set` mit Gerät und gesetzten Werten.
- [ ] **Step 4:** `mvn test`, Commit `feat(devices): Helligkeit, Farbe und Farbtemperatur setzen`.

---

### Task 5: Bedienelemente im Frontend

**Files:**
- Modify: `frontend/src/app/models/smart-device.model.ts` (Fähigkeiten + Lichtzustand)
- Modify: `frontend/src/app/services/smart-device.service.ts` (`setLightState`)
- Modify: `frontend/src/app/components/smart-device-list/smart-device-list.component.{ts,html,scss,spec.ts}`

- [ ] **Step 1:** Model und Service erweitern — typisiert, kein `any`.
- [ ] **Step 2:** Pro Gerät nur die Bedienelemente zeigen, die seine Fähigkeiten hergeben: Helligkeitsregler bei `BRIGHTNESS`, Farbwahl bei `COLOR`, Farbtemperatur-Regler bei `COLOR_TEMP`. Eine Steckdose bekommt nichts davon.
- [ ] **Step 3:** Senden erst beim **Loslassen** des Reglers, nicht bei jeder Bewegung — sonst überflutet eine Regler-Bewegung das Gerät mit Anfragen, und diese Geräte nehmen jeweils nur eine Verbindung gleichzeitig an.
- [ ] **Step 4:** Im Fehlerfall die Backend-Meldung inline zeigen; der zuletzt bekannte Wert bleibt stehen (kein Zurückspringen auf 0).
- [ ] **Step 5:** Produktions-Build und Tests (Suite muss **durchlaufen**, nicht abbrechen), Commit `feat(frontend): Helligkeit und Farbe in der Geraeteliste steuern`.

---

### Task 6: Flow-Node `light-set`

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/LightSetNodeHandler.java`
- Create: `backend/src/test/java/com/household/manager/flowengine/nodes/LightSetNodeHandlerTest.java`
- Modify: `frontend/src/app/pages/flows/node-catalog.ts` (Label, wie bei `push-send`)
- Modify: `docs/flows/flow-import-format.md`

- [ ] **Step 1: Tests zuerst** (Vorbild: `PushSendNodeHandlerTest`). Felder: `deviceId` (Pflicht, numerisch), `brightness`/`hue`/`saturation`/`colorTemp` optional, aber **mindestens eines** muss gesetzt sein — sonst Validierungsfehler beim Deploy. Gesetzte Werte werden unverändert durchgereicht. Ein Fehler des Geräts bricht den Flow **nicht** ab.
- [ ] **Step 2: Fehlschlag bestätigen.**
- [ ] **Step 3: Implementieren** — Muster exakt wie `PushSendNodeHandler`: ein Ausgangs-Port, Message unverändert weiter.
- [ ] **Step 4:** Katalog-Label und Format-Doku ergänzen.
- [ ] **Step 5:** `mvn test` + Frontend-Build, Commit `feat(flow): Aktions-Node light-set`.

---

### Task 7: Doku und Gesamtverifikation

- [ ] **Step 1:** `mvn test` vollständig (nur die 3 bekannten Fehler), Frontend-Build und -Tests.
- [ ] **Step 2:** `CLAUDE.md` ergänzen — eigener Abschnitt zu den modernen TP-Link-Geräten mit dem **Befund** (Port 9999 vs. 80, `error_code 1003`, gemessen am 2026-08-18), der Filterlogik-Falle in `scanTapoDevices`, dem Fähigkeiten-Modell und dem Flow-Node. Der Befund ist der wertvollste Teil: Er erklärt, warum ein als „Kasa" gekauftes Gerät kein Kasa-Protokoll spricht — genau die Fehlannahme, die den ersten Anlauf hat scheitern lassen.
- [ ] **Step 3:** Commit `docs: moderne TP-Link-Leuchtmittel in CLAUDE.md`.
