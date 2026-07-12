# Amazon Smart Air Quality Monitor Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zwei Amazon Smart Air Quality Monitore (IAQ, PM2.5, VOC, CO, Temperatur, Luftfeuchte) über die Alexa-Smart-Home-API in den Household-Manager integrieren: Sidecar-Endpoints, Backend-Polling mit DB-Historie und Entity-States, Frontend-Sektion „Innenraum" auf der Luftqualitäts-Seite.

**Architecture:** Der bestehende alexa-remote2-Sidecar wird um Smart-Home-Endpoints erweitert (Discovery über `getSmarthomeDevices()`, Zustände über `querySmarthomeDevices()`); das Amazon-spezifische Format wird vollständig im Sidecar normalisiert. Das Spring-Boot-Backend pollt den Sidecar alle 5 Minuten nach dem Airrohr-Muster, persistiert in `alexa_air_quality_readings` und meldet Entity-States (`EntitySource.ALEXA`). Das Angular-Frontend bekommt eine eigenständige Sektions-Komponente auf der Airrohr-Charts-Seite.

**Tech Stack:** Node 18+ (Express, alexa-remote2, node:test), Spring Boot 3.4 / Java 21 (Lombok, Liquibase, JUnit 5 + Mockito), Angular 19 standalone (ngx-echarts).

**Spec:** `docs/superpowers/specs/2026-07-12-alexa-air-quality-monitor-design.md`

---

## Wichtige Projekt-Randbedingungen

- **Backend-Build:** Vor jedem `mvn`-Aufruf `JAVA_HOME` auf JDK 21 setzen (PowerShell):
  `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'` (Default ist JDK 17 → Build schlägt fehl).
  Der volle `mvn test`-Lauf enthält DB-Integrationstests, die lokal **erwartungsgemäß fehlschlagen** — immer gezielt mit `-Dtest=<Klasse>` testen.
- **JPA-Repositories** müssen in `com.household.manager.repository` liegen (JpaConfig schränkt das Scanning ein).
- **Alle REST-Controller** liegen hinter dem Context-Path `/api` (`server.servlet.context-path=/api`); `@RequestMapping("/v1/...")` genügt.
- **Frontend-Befehle** in `frontend/`: `npm run build` (Prod-Build), Tests: `ng test --watch=false --browsers=ChromeHeadless`.
- **Sidecar** in `alexa-sidecar/`: kein Test-Framework installiert — wir nutzen das eingebaute `node:test` (Node ≥ 18), kein neues npm-Package nötig.

## Hintergrund: Alexa-Phoenix-Format (für alle Sidecar-Tasks)

Der AQM meldet seine Sensoren über die inoffizielle Phoenix-API:

- **Discovery** (`getSmarthomeDevices()` → `locationDetails`): tief verschachtelt
  `locationDetails.<Location>.amazonBridgeDetails.amazonBridgeDetails.<Bridge>.applianceDetails.applianceDetails.<applianceId>` →
  Appliance-Objekte mit `applianceId`, `entityId`, `friendlyName`, `manufacturerName`, `modelName`,
  `applianceTypes: ["AIR_QUALITY_MONITOR"]` und `capabilities[]`.
- **Sensor-Identifikation:** Die Messwerte sind generische `Alexa.RangeController`-Capabilities mit einer `instance`-Nummer.
  Welcher Sensor dahintersteckt, steht in `capability.resources.friendlyNames[].value.assetId` —
  Asset-IDs aus Amazons öffentlichem Katalog:
  `Alexa.AirQuality.IndoorAirQuality`, `Alexa.AirQuality.ParticulateMatter`,
  `Alexa.AirQuality.VolatileOrganicCompounds`, `Alexa.AirQuality.CarbonMonoxide`, `Alexa.AirQuality.Humidity`.
  Temperatur kommt als eigene `Alexa.TemperatureSensor`-Capability.
- **State** (`querySmarthomeDevices([applianceIds])` → `deviceStates[]`): pro Gerät `entity.entityId` (echot die angefragte ID)
  und `capabilityStates[]` — **Array von JSON-Strings**, z. B.
  `"{\"namespace\":\"Alexa.RangeController\",\"instance\":\"4\",\"name\":\"rangeValue\",\"value\":12.0}"` bzw.
  `"{\"namespace\":\"Alexa.TemperatureSensor\",\"name\":\"temperature\",\"value\":{\"value\":22.5,\"scale\":\"CELSIUS\"}}"`.

**Dieses Format ist aus alexa-remote2/Home-Assistant-Quellen rekonstruiert und wird in Task 3 gegen die echten Geräte verifiziert** (Spike laut Spec). Instanznummern sind pro Gerät dynamisch — niemals hartkodieren, immer über das Asset-ID-Mapping auflösen.

---

### Task 1: Sidecar — Mapping-Modul `smarthome.js` (TDD)

**Files:**
- Create: `alexa-sidecar/smarthome.js`
- Create: `alexa-sidecar/test/smarthome.test.js`
- Create: `alexa-sidecar/test/fixtures/phoenix-devices.json`
- Create: `alexa-sidecar/test/fixtures/phoenix-state.json`
- Modify: `alexa-sidecar/package.json` (test-Script)

- [ ] **Step 1: Fixtures anlegen** (dokumentiertes Format; Task 3 verifiziert gegen echte Geräte)

`alexa-sidecar/test/fixtures/phoenix-devices.json`:

```json
{
  "Default_Location": {
    "locationId": "Default_Location",
    "amazonBridgeDetails": {
      "amazonBridgeDetails": {
        "LambdaBridge_AAA/SonarCloudService": {
          "applianceDetails": {
            "applianceDetails": {
              "AAA_SonarCloudService_G0911W0793960XYZ": {
                "applianceId": "AAA_SonarCloudService_G0911W0793960XYZ",
                "entityId": "11111111-2222-3333-4444-555555555555",
                "friendlyName": "Luftsensor Wohnzimmer",
                "manufacturerName": "Amazon",
                "modelName": "Amazon Smart Air Quality Monitor",
                "applianceTypes": ["AIR_QUALITY_MONITOR"],
                "capabilities": [
                  {
                    "interfaceName": "Alexa.RangeController",
                    "instance": "2",
                    "resources": {
                      "friendlyNames": [
                        { "@type": "asset", "value": { "assetId": "Alexa.AirQuality.IndoorAirQuality" } }
                      ]
                    }
                  },
                  {
                    "interfaceName": "Alexa.RangeController",
                    "instance": "4",
                    "resources": {
                      "friendlyNames": [
                        { "@type": "asset", "value": { "assetId": "Alexa.AirQuality.ParticulateMatter" } }
                      ]
                    }
                  },
                  {
                    "interfaceName": "Alexa.RangeController",
                    "instance": "5",
                    "resources": {
                      "friendlyNames": [
                        { "@type": "asset", "value": { "assetId": "Alexa.AirQuality.VolatileOrganicCompounds" } }
                      ]
                    }
                  },
                  {
                    "interfaceName": "Alexa.RangeController",
                    "instance": "6",
                    "resources": {
                      "friendlyNames": [
                        { "@type": "asset", "value": { "assetId": "Alexa.AirQuality.CarbonMonoxide" } }
                      ]
                    }
                  },
                  {
                    "interfaceName": "Alexa.RangeController",
                    "instance": "3",
                    "resources": {
                      "friendlyNames": [
                        { "@type": "asset", "value": { "assetId": "Alexa.AirQuality.Humidity" } }
                      ]
                    }
                  },
                  { "interfaceName": "Alexa.TemperatureSensor" }
                ]
              },
              "AAA_SonarCloudService_ECHODOT123": {
                "applianceId": "AAA_SonarCloudService_ECHODOT123",
                "entityId": "99999999-8888-7777-6666-555555555555",
                "friendlyName": "Echo Dot Kueche",
                "manufacturerName": "Amazon",
                "modelName": "Echo Dot",
                "applianceTypes": ["ALEXA_VOICE_ENABLED"],
                "capabilities": [
                  { "interfaceName": "Alexa.TemperatureSensor" }
                ]
              }
            }
          }
        }
      }
    }
  }
}
```

`alexa-sidecar/test/fixtures/phoenix-state.json`:

```json
{
  "deviceStates": [
    {
      "entity": { "entityId": "AAA_SonarCloudService_G0911W0793960XYZ", "entityType": "APPLIANCE" },
      "capabilityStates": [
        "{\"namespace\":\"Alexa.RangeController\",\"instance\":\"2\",\"name\":\"rangeValue\",\"value\":52.0}",
        "{\"namespace\":\"Alexa.RangeController\",\"instance\":\"4\",\"name\":\"rangeValue\",\"value\":3.0}",
        "{\"namespace\":\"Alexa.RangeController\",\"instance\":\"5\",\"name\":\"rangeValue\",\"value\":128.5}",
        "{\"namespace\":\"Alexa.RangeController\",\"instance\":\"6\",\"name\":\"rangeValue\",\"value\":0.0}",
        "{\"namespace\":\"Alexa.RangeController\",\"instance\":\"3\",\"name\":\"rangeValue\",\"value\":48.2}",
        "{\"namespace\":\"Alexa.TemperatureSensor\",\"name\":\"temperature\",\"value\":{\"value\":22.5,\"scale\":\"CELSIUS\"}}"
      ]
    }
  ],
  "errors": [
    {
      "code": "ENDPOINT_UNREACHABLE",
      "entity": { "entityId": "AAA_SonarCloudService_OFFLINE999", "entityType": "APPLIANCE" }
    }
  ]
}
```

- [ ] **Step 2: Failing Tests schreiben**

`alexa-sidecar/test/smarthome.test.js`:

```js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const { extractAirQualityMonitors, mapDeviceStates } = require('../smarthome');

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', name), 'utf8'));
}

test('extractAirQualityMonitors finds AQM and resolves sensor instances', () => {
  const monitors = extractAirQualityMonitors(fixture('phoenix-devices.json'));

  assert.equal(monitors.length, 1, 'Echo Dot must be filtered out');
  const m = monitors[0];
  assert.equal(m.applianceId, 'AAA_SonarCloudService_G0911W0793960XYZ');
  assert.equal(m.friendlyName, 'Luftsensor Wohnzimmer');
  assert.deepEqual(m.sensors.iaq, { namespace: 'Alexa.RangeController', instance: '2' });
  assert.deepEqual(m.sensors.pm25, { namespace: 'Alexa.RangeController', instance: '4' });
  assert.deepEqual(m.sensors.voc, { namespace: 'Alexa.RangeController', instance: '5' });
  assert.deepEqual(m.sensors.co, { namespace: 'Alexa.RangeController', instance: '6' });
  assert.deepEqual(m.sensors.humidity, { namespace: 'Alexa.RangeController', instance: '3' });
  assert.deepEqual(m.sensors.temperature, { namespace: 'Alexa.TemperatureSensor' });
});

test('extractAirQualityMonitors returns [] for empty/odd input', () => {
  assert.deepEqual(extractAirQualityMonitors(null), []);
  assert.deepEqual(extractAirQualityMonitors({}), []);
});

test('mapDeviceStates maps capability states to flat values', () => {
  const monitors = extractAirQualityMonitors(fixture('phoenix-devices.json'));
  const states = mapDeviceStates(fixture('phoenix-state.json'), monitors);

  assert.equal(states.length, 1);
  const s = states[0];
  assert.equal(s.applianceId, 'AAA_SonarCloudService_G0911W0793960XYZ');
  assert.equal(s.friendlyName, 'Luftsensor Wohnzimmer');
  assert.equal(s.iaq, 52.0);
  assert.equal(s.pm25, 3.0);
  assert.equal(s.voc, 128.5);
  assert.equal(s.co, 0.0);
  assert.equal(s.humidity, 48.2);
  assert.equal(s.temperature, 22.5);
});

test('mapDeviceStates converts Fahrenheit to Celsius', () => {
  const monitors = extractAirQualityMonitors(fixture('phoenix-devices.json'));
  const state = fixture('phoenix-state.json');
  state.deviceStates[0].capabilityStates[5] =
    '{"namespace":"Alexa.TemperatureSensor","name":"temperature","value":{"value":77.0,"scale":"FAHRENHEIT"}}';

  const states = mapDeviceStates(state, monitors);
  assert.equal(states[0].temperature, 25);
});

test('mapDeviceStates tolerates missing sensors and unreachable devices', () => {
  const monitors = extractAirQualityMonitors(fixture('phoenix-devices.json'));
  const state = fixture('phoenix-state.json');
  // PM2.5-Eintrag entfernen (instance 4)
  state.deviceStates[0].capabilityStates =
    state.deviceStates[0].capabilityStates.filter((c) => !c.includes('"instance":"4"'));

  const states = mapDeviceStates(state, monitors);
  assert.equal(states[0].pm25, null);
  assert.equal(states[0].iaq, 52.0);
});
```

- [ ] **Step 3: Test-Script ergänzen und Fehlschlag verifizieren**

In `alexa-sidecar/package.json` den `scripts`-Block ersetzen durch:

```json
  "scripts": {
    "start": "node server.js",
    "test": "node --test test/"
  },
```

Run: `cd alexa-sidecar; npm test`
Expected: FAIL — `Cannot find module '../smarthome'`

- [ ] **Step 4: `smarthome.js` implementieren**

```js
'use strict';

/**
 * Mapping helpers for Amazon smart home (phoenix) responses.
 *
 * Isolates the Amazon-specific, brittle format inside the sidecar: input is
 * the raw getSmarthomeDevices() / querySmarthomeDevices() payload, output are
 * flat objects the Spring Boot backend consumes. AQM sensors are generic
 * Alexa.RangeController instances; the instance -> sensor mapping is resolved
 * via the asset ids in the capability resources.
 */

// Asset ids from Amazon's public asset catalog identifying AQM sensors.
const SENSOR_ASSET_IDS = {
  'Alexa.AirQuality.IndoorAirQuality': 'iaq',
  'Alexa.AirQuality.ParticulateMatter': 'pm25',
  'Alexa.AirQuality.VolatileOrganicCompounds': 'voc',
  'Alexa.AirQuality.CarbonMonoxide': 'co',
  'Alexa.AirQuality.Humidity': 'humidity'
};

function collectAppliances(locationDetails) {
  if (!locationDetails || typeof locationDetails !== 'object') return [];
  const appliances = [];
  for (const location of Object.values(locationDetails)) {
    const bridges =
      (location && location.amazonBridgeDetails && location.amazonBridgeDetails.amazonBridgeDetails) || {};
    for (const bridge of Object.values(bridges)) {
      const details = (bridge && bridge.applianceDetails && bridge.applianceDetails.applianceDetails) || {};
      for (const appliance of Object.values(details)) {
        appliances.push(appliance);
      }
    }
  }
  return appliances;
}

function isAirQualityMonitor(appliance) {
  const types = Array.isArray(appliance.applianceTypes) ? appliance.applianceTypes : [];
  return types.includes('AIR_QUALITY_MONITOR');
}

function extractSensorInstances(appliance) {
  const sensors = {};
  const caps = Array.isArray(appliance.capabilities) ? appliance.capabilities : [];
  for (const cap of caps) {
    if (cap.interfaceName === 'Alexa.TemperatureSensor') {
      sensors.temperature = { namespace: 'Alexa.TemperatureSensor' };
      continue;
    }
    if (cap.interfaceName !== 'Alexa.RangeController' || !cap.instance) continue;
    const friendlyNames =
      (cap.resources && Array.isArray(cap.resources.friendlyNames)) ? cap.resources.friendlyNames : [];
    for (const fn of friendlyNames) {
      const key = SENSOR_ASSET_IDS[fn && fn.value && fn.value.assetId];
      if (key) {
        sensors[key] = { namespace: 'Alexa.RangeController', instance: cap.instance };
        break;
      }
    }
  }
  return sensors;
}

/**
 * Filters the discovery payload down to Amazon Air Quality Monitors and
 * resolves which RangeController instance belongs to which sensor.
 */
function extractAirQualityMonitors(locationDetails) {
  return collectAppliances(locationDetails)
    .filter(isAirQualityMonitor)
    .map((a) => ({
      applianceId: a.applianceId,
      entityId: a.entityId,
      friendlyName: a.friendlyName,
      manufacturerName: a.manufacturerName,
      modelName: a.modelName,
      sensors: extractSensorInstances(a)
    }))
    .filter((m) => Object.keys(m.sensors).length > 0);
}

function parseCapabilityStates(deviceState) {
  const parsed = [];
  const states = Array.isArray(deviceState.capabilityStates) ? deviceState.capabilityStates : [];
  for (const raw of states) {
    try {
      parsed.push(typeof raw === 'string' ? JSON.parse(raw) : raw);
    } catch (err) {
      // skip broken entries; a single bad state must not lose the reading
    }
  }
  return parsed;
}

function toCelsius(value) {
  if (!value || typeof value.value !== 'number') return null;
  if (value.scale === 'FAHRENHEIT') return Math.round(((value.value - 32) * 5) / 9 * 100) / 100;
  return value.value;
}

/**
 * Maps a querySmarthomeDevices() response to flat per-device sensor values.
 * Unknown devices and unreachable endpoints (listed under `errors`) are
 * silently skipped; missing individual sensors yield null.
 */
function mapDeviceStates(stateResponse, monitors) {
  const byId = new Map();
  for (const m of monitors) {
    byId.set(m.applianceId, m);
    if (m.entityId) byId.set(m.entityId, m);
  }

  const results = [];
  const deviceStates =
    (stateResponse && Array.isArray(stateResponse.deviceStates)) ? stateResponse.deviceStates : [];
  for (const ds of deviceStates) {
    const monitor = byId.get(ds.entity && ds.entity.entityId);
    if (!monitor) continue;

    const caps = parseCapabilityStates(ds);
    const values = {
      applianceId: monitor.applianceId,
      friendlyName: monitor.friendlyName,
      iaq: null,
      pm25: null,
      voc: null,
      co: null,
      temperature: null,
      humidity: null
    };
    for (const [key, ref] of Object.entries(monitor.sensors)) {
      if (ref.namespace === 'Alexa.TemperatureSensor') {
        const cap = caps.find((c) => c.namespace === 'Alexa.TemperatureSensor');
        values.temperature = cap ? toCelsius(cap.value) : null;
      } else {
        const cap = caps.find(
          (c) => c.namespace === 'Alexa.RangeController' && c.instance === ref.instance
        );
        if (cap && typeof cap.value === 'number') values[key] = cap.value;
      }
    }
    results.push(values);
  }
  return results;
}

module.exports = { extractAirQualityMonitors, mapDeviceStates, SENSOR_ASSET_IDS };
```

- [ ] **Step 5: Tests laufen lassen**

Run: `cd alexa-sidecar; npm test`
Expected: PASS (5 Tests)

- [ ] **Step 6: Commit**

```bash
git add alexa-sidecar/smarthome.js alexa-sidecar/test/ alexa-sidecar/package.json
git commit -m "feat(alexa-sidecar): smart home mapping for air quality monitors"
```

---

### Task 2: Sidecar — HTTP-Endpoints

**Files:**
- Modify: `alexa-sidecar/server.js`

- [ ] **Step 1: Endpoints implementieren**

In `server.js` nach `const AlexaRemote = require('alexa-remote2');` (Zeile 23) ergänzen:

```js
const { extractAirQualityMonitors, mapDeviceStates } = require('./smarthome');
```

Nach dem `/announce`-Handler (vor dem Catch-all-Error-Handler) einfügen:

```js
// ---------------------------------------------------------------------------
// Smart home: Amazon Air Quality Monitors
// ---------------------------------------------------------------------------
// Discovery is cached because the backend polls every few minutes and the
// monitor list / instance mapping changes practically never.
const AQM_CACHE_TTL_MS = 10 * 60 * 1000;
let aqmCache = { monitors: null, fetchedAt: 0 };

function getAirQualityMonitors(callback) {
  if (aqmCache.monitors && Date.now() - aqmCache.fetchedAt < AQM_CACHE_TTL_MS) {
    return callback(null, aqmCache.monitors);
  }
  alexa.getSmarthomeDevices((err, locationDetails) => {
    if (err) return callback(err);
    try {
      const monitors = extractAirQualityMonitors(locationDetails);
      aqmCache = { monitors, fetchedAt: Date.now() };
      callback(null, monitors);
    } catch (mapErr) {
      callback(mapErr);
    }
  });
}

// Debug/spike endpoint: raw discovery payload to inspect the real format.
app.get('/smarthome/raw', (req, res) => {
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  alexa.getSmarthomeDevices((err, locationDetails) => {
    if (err) {
      log('smarthome/raw failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    res.status(200).json(locationDetails);
  });
});

app.get('/smarthome/air-quality-monitors', (req, res) => {
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  getAirQualityMonitors((err, monitors) => {
    if (err) {
      log('air-quality-monitors failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    res.status(200).json(monitors);
  });
});

app.get('/smarthome/air-quality-monitors/state', (req, res) => {
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  getAirQualityMonitors((err, monitors) => {
    if (err) {
      log('air-quality state (discovery) failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    if (monitors.length === 0) {
      return res.status(200).json([]);
    }
    const ids = monitors.map((m) => m.applianceId);
    alexa.querySmarthomeDevices(ids, 'APPLIANCE', (queryErr, stateResponse) => {
      if (queryErr) {
        log('air-quality state (query) failed:', queryErr.message);
        return res.status(500).json({ error: queryErr.message });
      }
      try {
        res.status(200).json(mapDeviceStates(stateResponse, monitors));
      } catch (mapErr) {
        log('air-quality state (mapping) failed:', mapErr.message);
        res.status(500).json({ error: mapErr.message });
      }
    });
  });
});
```

- [ ] **Step 2: Syntax-Check und bestehende Tests**

Run: `cd alexa-sidecar; node --check server.js; npm test`
Expected: kein Syntaxfehler, alle Tests PASS

- [ ] **Step 3: Commit**

```bash
git add alexa-sidecar/server.js
git commit -m "feat(alexa-sidecar): air quality monitor endpoints (discovery, state, raw debug)"
```

---

### Task 3: Spike — Verifikation gegen die echten Geräte

**Files:**
- Ggf. Modify: `alexa-sidecar/smarthome.js`, `alexa-sidecar/test/fixtures/*.json`

Dieser Task verifiziert das in Task 1 angenommene Format gegen die zwei echten Monitore (laut Spec der erste Risiko-Abbau). Er braucht einen **laufenden, eingeloggten Sidecar**.

- [ ] **Step 1: Sidecar starten (falls nicht aktiv)**

Run: `cd alexa-sidecar; npm start` (im Hintergrund) und Login-Status prüfen:
`curl http://localhost:3456/status`
Expected: `{"loggedIn":true,...}` — falls `false`: **STOP, Nutzer informieren** (Login über die Ansagen-Seite nötig), Task später wiederholen.

- [ ] **Step 2: Echte Discovery-Antwort inspizieren**

Run: `curl http://localhost:3456/smarthome/raw > scratch-raw.json` und die zwei AQM-Appliances darin suchen (`AIR_QUALITY_MONITOR`).
Prüfen: Pfadstruktur, `applianceTypes`, Asset-IDs der RangeController-`friendlyNames`, Vorhandensein von `Alexa.TemperatureSensor`.

- [ ] **Step 3: Endpoints live testen**

Run: `curl http://localhost:3456/smarthome/air-quality-monitors`
Expected: JSON-Array mit **2 Geräten**, jedes mit gefülltem `sensors`-Objekt (6 Einträge).

Run: `curl http://localhost:3456/smarthome/air-quality-monitors/state`
Expected: 2 Objekte mit plausiblen Zahlwerten (iaq 0–500, pm25 ≥ 0, temperature ~Raumtemperatur).

- [ ] **Step 4: Bei Abweichungen Mapping und Fixtures korrigieren**

Weicht das echte Format ab (andere Asset-IDs, andere Verschachtelung, `capabilityStates` als Objekte statt Strings):
`smarthome.js` anpassen, **Fixtures durch anonymisierte echte Antworten ersetzen** (Seriennummern/IDs verfremden), `npm test` erneut grün bekommen. `scratch-raw.json` löschen (enthält Kontodaten — nicht committen!).

- [ ] **Step 5: Commit (nur falls Änderungen)**

```bash
git add alexa-sidecar/smarthome.js alexa-sidecar/test/
git commit -m "fix(alexa-sidecar): align AQM mapping with real device responses"
```

---

### Task 4: Backend — Liquibase, Entity, Repository

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260712-0031-create-alexa-air-quality-readings-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/model/entity/AlexaAirQualityReading.java`
- Create: `backend/src/main/java/com/household/manager/repository/AlexaAirQualityReadingRepository.java`

- [ ] **Step 1: Changeset anlegen**

`20260712-0031-create-alexa-air-quality-readings-table.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260712-0031" author="household-manager">
        <comment>Create alexa_air_quality_readings table for Amazon Smart Air Quality Monitor data</comment>

        <createTable tableName="alexa_air_quality_readings">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="appliance_id" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="device_name" type="VARCHAR(255)"/>
            <column name="reading_time" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="iaq" type="INT"/>
            <column name="pm25" type="DECIMAL(10,2)"/>
            <column name="voc" type="DECIMAL(10,2)"/>
            <column name="co" type="DECIMAL(10,3)"/>
            <column name="temperature" type="DECIMAL(5,2)"/>
            <column name="humidity" type="DECIMAL(5,2)"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_alexa_aq_readings_device_time" tableName="alexa_air_quality_readings">
            <column name="appliance_id"/>
            <column name="reading_time"/>
        </createIndex>

        <rollback>
            <dropTable tableName="alexa_air_quality_readings"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

In `db.changelog-master.xml` vor `</databaseChangeLog>` ergänzen:

```xml
    <!-- Amazon Smart Air Quality Monitor Feature -->
    <include file="db/changelog/changes/20260712-0031-create-alexa-air-quality-readings-table.xml"/>
```

- [ ] **Step 2: Entity anlegen**

`AlexaAirQualityReading.java` (Muster `AirrohrReading`):

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Messwert eines Amazon Smart Air Quality Monitors (via Alexa-Sidecar).
 * Geraete-Identitaet ueber die stabile applianceId, nie ueber Namen.
 */
@Entity
@Table(name = "alexa_air_quality_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaAirQualityReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appliance_id", nullable = false)
    private String applianceId;

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "reading_time", nullable = false)
    private LocalDateTime readingTime;

    @Column(name = "iaq")
    private Integer iaq;

    @Column(name = "pm25", precision = 10, scale = 2)
    private BigDecimal pm25;

    @Column(name = "voc", precision = 10, scale = 2)
    private BigDecimal voc;

    @Column(name = "co", precision = 10, scale = 3)
    private BigDecimal co;

    @Column(name = "temperature", precision = 5, scale = 2)
    private BigDecimal temperature;

    @Column(name = "humidity", precision = 5, scale = 2)
    private BigDecimal humidity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Repository anlegen** (zwingend im Package `com.household.manager.repository`!)

`AlexaAirQualityReadingRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.AlexaAirQualityReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AlexaAirQualityReadingRepository extends JpaRepository<AlexaAirQualityReading, Long> {

    List<AlexaAirQualityReading> findAllByOrderByReadingTimeAsc();

    Optional<AlexaAirQualityReading> findTopByApplianceIdOrderByReadingTimeDesc(String applianceId);

    @Query("select distinct r.applianceId from AlexaAirQualityReading r")
    List<String> findDistinctApplianceIds();
}
```

- [ ] **Step 4: Kompilieren**

Run (PowerShell): `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; cd backend; mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog/ backend/src/main/java/com/household/manager/model/entity/AlexaAirQualityReading.java backend/src/main/java/com/household/manager/repository/AlexaAirQualityReadingRepository.java
git commit -m "feat(alexa-air-quality): reading entity, repository and liquibase changeset"
```

---

### Task 5: Backend — EntitySource.ALEXA + Sidecar-Client-Erweiterung

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`
- Modify: `backend/src/main/java/com/household/manager/alexa/AlexaSidecarClient.java`

- [ ] **Step 1: Enum-Wert ergänzen**

In `EntitySource.java` nach `AIRROHR,`:

```java
    ALEXA,
```

- [ ] **Step 2: Client-Methoden ergänzen**

In `AlexaSidecarClient.java` — Import ergänzen: `import java.math.BigDecimal;`
Nach den bestehenden Records (`SidecarStatus`) einfügen:

```java
    /** Ein Amazon Air Quality Monitor laut Sidecar-Discovery. */
    public record SidecarAirQualityMonitor(
            String applianceId, String entityId, String friendlyName, String modelName) {}

    /** Normalisierte Momentanwerte eines Air Quality Monitors (Sidecar-Format). */
    public record SidecarAirQualityState(
            String applianceId, String friendlyName, Integer iaq, BigDecimal pm25,
            BigDecimal voc, BigDecimal co, BigDecimal temperature, BigDecimal humidity) {}
```

Nach `getDevices()` einfügen:

```java
    public List<SidecarAirQualityMonitor> getAirQualityMonitors() {
        JsonNode root = get("/smarthome/air-quality-monitors");
        try {
            return mapper.convertValue(root, new TypeReference<>() {});
        } catch (IllegalArgumentException ex) {
            throw new AlexaException("Sidecar-AQM-Geraeteliste konnte nicht gelesen werden.", ex);
        }
    }

    public List<SidecarAirQualityState> getAirQualityStates() {
        JsonNode root = get("/smarthome/air-quality-monitors/state");
        try {
            return mapper.convertValue(root, new TypeReference<>() {});
        } catch (IllegalArgumentException ex) {
            throw new AlexaException("Sidecar-AQM-Zustaende konnten nicht gelesen werden.", ex);
        }
    }
```

Hinweis: Die Records enthalten weniger Felder als das Sidecar-JSON (`sensors`, `manufacturerName`).
`mapper.convertValue` nutzt den Spring-Boot-`ObjectMapper`, der unbekannte Felder standardmäßig **nicht** ignoriert →
beide Records mit `@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)` annotieren
(Lehre aus der Meross-Integration):

```java
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SidecarAirQualityMonitor(...) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SidecarAirQualityState(...) {}
```

- [ ] **Step 3: Kompilieren**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; cd backend; mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntitySource.java backend/src/main/java/com/household/manager/alexa/AlexaSidecarClient.java
git commit -m "feat(alexa-air-quality): sidecar client methods and ALEXA entity source"
```

---

### Task 6: Backend — Polling-Service (TDD)

**Files:**
- Create: `backend/src/test/java/com/household/manager/service/AlexaAirQualityPollingServiceTest.java`
- Create: `backend/src/main/java/com/household/manager/dto/AlexaAirQualityPollingStatusResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/AlexaAirQualityPollingService.java`
- Modify: `backend/src/main/resources/application.properties`

- [ ] **Step 1: Failing Test schreiben**

`AlexaAirQualityPollingServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.alexa.AlexaException;
import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaSidecarClient.SidecarAirQualityState;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlexaAirQualityPollingServiceTest {

    @Mock
    private AlexaSidecarClient sidecarClient;
    @Mock
    private AlexaAirQualityReadingRepository repository;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private EntityStateService entityStateService;

    private AlexaAirQualityPollingService service;

    @BeforeEach
    void setUp() {
        service = new AlexaAirQualityPollingService(
                sidecarClient, repository, taskScheduler, entityStateService);
    }

    private SidecarAirQualityState wohnzimmerState() {
        return new SidecarAirQualityState(
                "AAA_Sonar_1", "Luftsensor Wohnzimmer", 52,
                new BigDecimal("3.0"), new BigDecimal("128.5"), new BigDecimal("0.0"),
                new BigDecimal("22.5"), new BigDecimal("48.2"));
    }

    @Test
    void scheduledPollPersistsOneReadingPerDevice() {
        SidecarAirQualityState schlafzimmer = new SidecarAirQualityState(
                "AAA_Sonar_2", "Luftsensor Schlafzimmer", 30,
                new BigDecimal("1.0"), new BigDecimal("50.0"), new BigDecimal("0.0"),
                new BigDecimal("20.0"), new BigDecimal("55.0"));
        when(sidecarClient.getAirQualityStates()).thenReturn(List.of(wohnzimmerState(), schlafzimmer));

        service.scheduledPoll();

        ArgumentCaptor<AlexaAirQualityReading> captor = ArgumentCaptor.forClass(AlexaAirQualityReading.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        AlexaAirQualityReading first = captor.getAllValues().get(0);
        assertThat(first.getApplianceId()).isEqualTo("AAA_Sonar_1");
        assertThat(first.getDeviceName()).isEqualTo("Luftsensor Wohnzimmer");
        assertThat(first.getIaq()).isEqualTo(52);
        assertThat(first.getPm25()).isEqualByComparingTo("3.0");
        assertThat(first.getReadingTime()).isNotNull();
        assertThat(service.getStatus().getLastError()).isNull();
    }

    @Test
    void scheduledPollReportsEntityStatesForEachSensor() {
        when(sidecarClient.getAirQualityStates()).thenReturn(List.of(wohnzimmerState()));

        service.scheduledPoll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, org.mockito.Mockito.times(6)).reportState(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(EntityStateUpdate::entityId)
                .contains("sensor.alexa_aaa_sonar_1_pm25", "sensor.alexa_aaa_sonar_1_iaq");
    }

    @Test
    void scheduledPollSkipsNullSensorsInEntityStates() {
        SidecarAirQualityState partial = new SidecarAirQualityState(
                "AAA_Sonar_1", "Luftsensor Wohnzimmer", 52,
                null, null, null, new BigDecimal("22.5"), null);
        when(sidecarClient.getAirQualityStates()).thenReturn(List.of(partial));

        service.scheduledPoll();

        verify(entityStateService, org.mockito.Mockito.times(2)).reportState(any());
    }

    @Test
    void scheduledPollRecordsErrorWithoutThrowing() {
        when(sidecarClient.getAirQualityStates())
                .thenThrow(new AlexaException("Sidecar ist nicht erreichbar"));

        service.scheduledPoll();

        verify(repository, never()).save(any());
        assertThat(service.getStatus().getLastError()).contains("Sidecar ist nicht erreichbar");
        assertThat(service.getStatus().getLastPollTime()).isNotNull();
    }

    @Test
    void entityStateFailureDoesNotPreventPersistence() {
        when(sidecarClient.getAirQualityStates()).thenReturn(List.of(wohnzimmerState()));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(entityStateService).reportState(any());

        service.scheduledPoll();

        verify(repository, atLeastOnce()).save(any());
        assertThat(service.getStatus().getLastError()).isNull();
    }
}
```

Hinweis: `EntityStateUpdate` ist ein Java-Record — Accessoren heißen `entityId()`, nicht `getEntityId()`.

- [ ] **Step 2: Fehlschlag verifizieren**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; cd backend; mvn test -Dtest=AlexaAirQualityPollingServiceTest`
Expected: COMPILATION ERROR (Service und DTO existieren noch nicht)

- [ ] **Step 3: Status-DTO implementieren**

`AlexaAirQualityPollingStatusResponse.java` (gleicher Stil wie `AirrohrPollingStatusResponse`):

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Status response for Alexa air quality polling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaAirQualityPollingStatusResponse {

    private String schedule;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastPollTime;

    private String lastError;
}
```

- [ ] **Step 4: Polling-Service implementieren**

`AlexaAirQualityPollingService.java`:

```java
package com.household.manager.service;

import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaSidecarClient.SidecarAirQualityState;
import com.household.manager.dto.AlexaAirQualityPollingStatusResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pollt die Amazon Smart Air Quality Monitore ueber den Alexa-Sidecar,
 * persistiert die Messwerte und meldet sie an die Entity-State-Schicht.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlexaAirQualityPollingService {

    private static final String SCHEDULE = "Alle 5 Minuten";

    private final AlexaSidecarClient sidecarClient;
    private final AlexaAirQualityReadingRepository repository;
    private final TaskScheduler taskScheduler;
    private final EntityStateService entityStateService;

    private volatile LocalDateTime lastPollTime;
    private volatile String lastError;

    public AlexaAirQualityPollingStatusResponse getStatus() {
        return AlexaAirQualityPollingStatusResponse.builder()
                .schedule(SCHEDULE)
                .lastPollTime(lastPollTime)
                .lastError(lastError)
                .build();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::safePoll, Instant.now());
    }

    @Scheduled(
            fixedDelayString = "${alexa.air-quality.polling.interval-ms:300000}",
            initialDelayString = "${alexa.air-quality.polling.initial-delay-ms:20000}"
    )
    public void scheduledPoll() {
        safePoll();
    }

    private void safePoll() {
        lastPollTime = LocalDateTime.now();
        try {
            List<SidecarAirQualityState> states = sidecarClient.getAirQualityStates();
            LocalDateTime readingTime = LocalDateTime.now();
            for (SidecarAirQualityState state : states) {
                repository.save(toReading(state, readingTime));
                reportEntityStates(state);
            }
            lastError = null;
            log.debug("Saved {} Alexa air quality readings", states.size());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Failed to poll Alexa air quality monitors", ex);
        }
    }

    private AlexaAirQualityReading toReading(SidecarAirQualityState state, LocalDateTime readingTime) {
        return AlexaAirQualityReading.builder()
                .applianceId(state.applianceId())
                .deviceName(state.friendlyName())
                .readingTime(readingTime)
                .iaq(state.iaq())
                .pm25(state.pm25())
                .voc(state.voc())
                .co(state.co())
                .temperature(state.temperature())
                .humidity(state.humidity())
                .build();
    }

    private void reportEntityStates(SidecarAirQualityState state) {
        try {
            reportSensor(state, "iaq", "IAQ", state.iaq(), null, "aqi");
            reportSensor(state, "pm25", "PM2.5", state.pm25(), "µg/m³", "pm25");
            reportSensor(state, "voc", "VOC", state.voc(), "ppb", "volatile_organic_compounds_parts");
            reportSensor(state, "co", "CO", state.co(), "ppm", "carbon_monoxide");
            reportSensor(state, "temperature", "Temperatur", state.temperature(), "°C", "temperature");
            reportSensor(state, "humidity", "Luftfeuchte", state.humidity(), "%", "humidity");
        } catch (Exception ex) {
            log.warn("Failed to report alexa air quality entity states: {}", ex.getMessage());
        }
    }

    private void reportSensor(SidecarAirQualityState state, String suffix, String label,
                              Object value, String unit, String deviceClass) {
        if (value == null) {
            return;
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("deviceClass", deviceClass);
        if (unit != null) {
            attributes.put("unit", unit);
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.ALEXA, state.applianceId(), suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ALEXA)
                .sourceRef(state.applianceId())
                .friendlyName(state.friendlyName() + " " + label)
                .state(String.valueOf(value))
                .attributes(attributes)
                .build());
    }
}
```

Hinweis: `EntityStateUpdate.attributes` ist `Map<String, Object>` (Record in `com.household.manager.entitystate`).

- [ ] **Step 5: Tests laufen lassen**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; cd backend; mvn test -Dtest=AlexaAirQualityPollingServiceTest`
Expected: PASS (5 Tests)

- [ ] **Step 6: Properties dokumentieren**

In `backend/src/main/resources/application.properties` beim Alexa-Block ergänzen:

```properties
# Alexa Air Quality Monitor polling
alexa.air-quality.polling.interval-ms=300000
alexa.air-quality.polling.initial-delay-ms=20000
```

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/AlexaAirQualityPollingService.java backend/src/main/java/com/household/manager/dto/AlexaAirQualityPollingStatusResponse.java backend/src/test/java/com/household/manager/service/AlexaAirQualityPollingServiceTest.java backend/src/main/resources/application.properties
git commit -m "feat(alexa-air-quality): scheduled polling with persistence and entity states"
```

---

### Task 7: Backend — Reading-Service, DTO, Controller

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/AlexaAirQualityReadingResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/AlexaAirQualityReadingService.java`
- Create: `backend/src/main/java/com/household/manager/controller/AlexaAirQualityController.java`
- Create: `backend/src/main/java/com/household/manager/controller/AlexaAirQualityPollingAdminController.java`
- Create: `backend/src/test/java/com/household/manager/service/AlexaAirQualityReadingServiceTest.java`

- [ ] **Step 1: Failing Test für den Reading-Service**

`AlexaAirQualityReadingServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.AlexaAirQualityReadingResponse;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlexaAirQualityReadingServiceTest {

    @Mock
    private AlexaAirQualityReadingRepository repository;

    @InjectMocks
    private AlexaAirQualityReadingService service;

    private AlexaAirQualityReading reading(String applianceId, LocalDateTime time) {
        return AlexaAirQualityReading.builder()
                .id(1L)
                .applianceId(applianceId)
                .deviceName("Luftsensor Wohnzimmer")
                .readingTime(time)
                .iaq(52)
                .pm25(new BigDecimal("3.0"))
                .build();
    }

    @Test
    void latestReturnsNewestReadingPerDevice() {
        LocalDateTime now = LocalDateTime.now();
        when(repository.findDistinctApplianceIds()).thenReturn(List.of("A", "B"));
        when(repository.findTopByApplianceIdOrderByReadingTimeDesc("A"))
                .thenReturn(Optional.of(reading("A", now)));
        when(repository.findTopByApplianceIdOrderByReadingTimeDesc("B"))
                .thenReturn(Optional.of(reading("B", now.minusMinutes(5))));

        List<AlexaAirQualityReadingResponse> latest = service.getLatestPerDevice();

        assertThat(latest).hasSize(2);
        assertThat(latest.get(0).getApplianceId()).isEqualTo("A");
        assertThat(latest.get(0).getIaq()).isEqualTo(52);
    }

    @Test
    void getAllReadingsMapsEntities() {
        when(repository.findAllByOrderByReadingTimeAsc())
                .thenReturn(List.of(reading("A", LocalDateTime.now())));

        List<AlexaAirQualityReadingResponse> all = service.getAllReadings();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getDeviceName()).isEqualTo("Luftsensor Wohnzimmer");
        assertThat(all.get(0).getPm25()).isEqualByComparingTo("3.0");
    }
}
```

- [ ] **Step 2: Fehlschlag verifizieren**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; cd backend; mvn test -Dtest=AlexaAirQualityReadingServiceTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: DTO + Service implementieren**

`AlexaAirQualityReadingResponse.java`:

```java
package com.household.manager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Messwert eines Amazon Smart Air Quality Monitors fuer das Frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaAirQualityReadingResponse {

    private Long id;

    private String applianceId;

    private String deviceName;

    private LocalDateTime readingTime;

    private Integer iaq;

    private BigDecimal pm25;

    private BigDecimal voc;

    private BigDecimal co;

    private BigDecimal temperature;

    private BigDecimal humidity;
}
```

`AlexaAirQualityReadingService.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.AlexaAirQualityReadingResponse;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Liest persistierte Amazon-Air-Quality-Messwerte.
 */
@Service
@RequiredArgsConstructor
public class AlexaAirQualityReadingService {

    private final AlexaAirQualityReadingRepository repository;

    @Transactional(readOnly = true)
    public List<AlexaAirQualityReadingResponse> getAllReadings() {
        return repository.findAllByOrderByReadingTimeAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AlexaAirQualityReadingResponse> getLatestPerDevice() {
        return repository.findDistinctApplianceIds().stream()
                .map(repository::findTopByApplianceIdOrderByReadingTimeDesc)
                .flatMap(Optional::stream)
                .map(this::toResponse)
                .toList();
    }

    private AlexaAirQualityReadingResponse toResponse(AlexaAirQualityReading reading) {
        return AlexaAirQualityReadingResponse.builder()
                .id(reading.getId())
                .applianceId(reading.getApplianceId())
                .deviceName(reading.getDeviceName())
                .readingTime(reading.getReadingTime())
                .iaq(reading.getIaq())
                .pm25(reading.getPm25())
                .voc(reading.getVoc())
                .co(reading.getCo())
                .temperature(reading.getTemperature())
                .humidity(reading.getHumidity())
                .build();
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; cd backend; mvn test -Dtest=AlexaAirQualityReadingServiceTest`
Expected: PASS (2 Tests)

- [ ] **Step 5: Controller implementieren**

`AlexaAirQualityController.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.AlexaAirQualityReadingResponse;
import com.household.manager.service.AlexaAirQualityReadingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Messwerte der Amazon Smart Air Quality Monitore.
 * Base URL: /api/v1/alexa/air-quality
 */
@RestController
@RequestMapping("/v1/alexa/air-quality")
@RequiredArgsConstructor
public class AlexaAirQualityController {

    private final AlexaAirQualityReadingService readingService;

    @GetMapping("/latest")
    public List<AlexaAirQualityReadingResponse> getLatest() {
        return readingService.getLatestPerDevice();
    }

    @GetMapping("/readings")
    public List<AlexaAirQualityReadingResponse> getReadings() {
        return readingService.getAllReadings();
    }
}
```

`AlexaAirQualityPollingAdminController.java` (Muster `AirrohrPollingAdminController`):

```java
package com.household.manager.controller;

import com.household.manager.dto.AlexaAirQualityPollingStatusResponse;
import com.household.manager.service.AlexaAirQualityPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for controlling Alexa air quality polling.
 * Base URL: /api/v1/admin/alexa-air-quality-polling
 */
@RestController
@RequestMapping("/v1/admin/alexa-air-quality-polling")
@RequiredArgsConstructor
@Slf4j
public class AlexaAirQualityPollingAdminController {

    private final AlexaAirQualityPollingService pollingService;

    @GetMapping
    public ResponseEntity<AlexaAirQualityPollingStatusResponse> getStatus() {
        return ResponseEntity.ok(pollingService.getStatus());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Void> trigger() {
        log.info("Triggering Alexa air quality polling");
        pollingService.triggerOnce();
        return ResponseEntity.accepted().build();
    }
}
```

- [ ] **Step 6: Kompilieren + beide Testklassen**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; cd backend; mvn test -Dtest='AlexaAirQuality*Test'`
Expected: PASS (7 Tests)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/AlexaAirQualityReadingResponse.java backend/src/main/java/com/household/manager/service/AlexaAirQualityReadingService.java backend/src/main/java/com/household/manager/controller/AlexaAirQualityController.java backend/src/main/java/com/household/manager/controller/AlexaAirQualityPollingAdminController.java backend/src/test/java/com/household/manager/service/AlexaAirQualityReadingServiceTest.java
git commit -m "feat(alexa-air-quality): rest endpoints for readings and polling admin"
```

---

### Task 8: Frontend — Model + Service

**Files:**
- Create: `frontend/src/app/models/alexa-air-quality.model.ts`
- Create: `frontend/src/app/services/alexa-air-quality.service.ts`
- Create: `frontend/src/app/models/alexa-air-quality.model.spec.ts`

- [ ] **Step 1: Model mit Metrik-Katalog und IAQ-Bewertung**

`alexa-air-quality.model.ts`:

```typescript
export interface AlexaAirQualityReading {
  id: number;
  applianceId: string;
  deviceName: string;
  readingTime: Date;
  iaq: number | null;
  pm25: number | null;
  voc: number | null;
  co: number | null;
  temperature: number | null;
  humidity: number | null;
}

export type AlexaAirQualityMetricKey = 'iaq' | 'pm25' | 'voc' | 'co' | 'temperature' | 'humidity';

export interface AlexaAirQualityMetric {
  key: AlexaAirQualityMetricKey;
  label: string;
  unit: string;
}

export const ALEXA_AIR_QUALITY_METRICS: AlexaAirQualityMetric[] = [
  { key: 'iaq', label: 'Luftqualitaet (IAQ)', unit: '' },
  { key: 'pm25', label: 'Feinstaub PM2.5', unit: 'µg/m³' },
  { key: 'voc', label: 'VOC', unit: 'ppb' },
  { key: 'co', label: 'Kohlenmonoxid', unit: 'ppm' },
  { key: 'temperature', label: 'Temperatur', unit: '°C' },
  { key: 'humidity', label: 'Luftfeuchte', unit: '%' }
];

export type IaqLevel = 'good' | 'moderate' | 'bad' | 'unknown';

/** Amazons IAQ-Skala: 0-50 gut, 51-100 maessig, ab 101 schlecht. */
export function iaqLevel(iaq: number | null): IaqLevel {
  if (iaq === null || iaq === undefined || Number.isNaN(iaq)) {
    return 'unknown';
  }
  if (iaq <= 50) {
    return 'good';
  }
  if (iaq <= 100) {
    return 'moderate';
  }
  return 'bad';
}
```

- [ ] **Step 2: Model-Spec schreiben**

`alexa-air-quality.model.spec.ts`:

```typescript
import { iaqLevel } from './alexa-air-quality.model';

describe('iaqLevel', () => {
  it('maps scores to Amazon IAQ levels', () => {
    expect(iaqLevel(0)).toBe('good');
    expect(iaqLevel(50)).toBe('good');
    expect(iaqLevel(51)).toBe('moderate');
    expect(iaqLevel(100)).toBe('moderate');
    expect(iaqLevel(101)).toBe('bad');
  });

  it('returns unknown for missing values', () => {
    expect(iaqLevel(null)).toBe('unknown');
    expect(iaqLevel(Number.NaN)).toBe('unknown');
  });
});
```

- [ ] **Step 3: Service implementieren** (Muster `AirrohrService`)

`alexa-air-quality.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AlexaAirQualityReading } from '../models/alexa-air-quality.model';

/**
 * Service fuer die Amazon Smart Air Quality Monitore (via Alexa-Sidecar).
 */
@Injectable({
  providedIn: 'root'
})
export class AlexaAirQualityService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/alexa/air-quality';

  getLatest(): Observable<AlexaAirQualityReading[]> {
    return this.http.get<AlexaAirQualityReading[]>(`${this.baseUrl}/latest`).pipe(
      map(readings => readings.map(reading => this.convertDate(reading))),
      catchError(this.handleError)
    );
  }

  getReadings(): Observable<AlexaAirQualityReading[]> {
    return this.http.get<AlexaAirQualityReading[]>(`${this.baseUrl}/readings`).pipe(
      map(readings => readings.map(reading => this.convertDate(reading))),
      catchError(this.handleError)
    );
  }

  private convertDate(reading: AlexaAirQualityReading): AlexaAirQualityReading {
    return { ...reading, readingTime: new Date(reading.readingTime) };
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Alexa-Air-Quality-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Amazon-Luftsensor-Daten.'));
  }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `cd frontend; ng test --watch=false --browsers=ChromeHeadless --include='**/alexa-air-quality.model.spec.ts'`
Expected: PASS (2 Specs)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/models/alexa-air-quality.model.ts frontend/src/app/models/alexa-air-quality.model.spec.ts frontend/src/app/services/alexa-air-quality.service.ts
git commit -m "feat(frontend): alexa air quality model and api service"
```

---

### Task 9: Frontend — Sektions-Komponente „Innenraum"

**Files:**
- Create: `frontend/src/app/pages/airrohr-charts/alexa-air-quality-section.component.ts`
- Create: `frontend/src/app/pages/airrohr-charts/alexa-air-quality-section.component.html`
- Create: `frontend/src/app/pages/airrohr-charts/alexa-air-quality-section.component.scss`

Eigenständige, in sich geschlossene Komponente (Kacheln + ein Verlaufschart mit Metrik- und Jahr/Monat/Tag-Auswahl, beide Geräte als Serien). Bewusst **ohne** Vergleichsmodus (YAGNI).

- [ ] **Step 1: Komponente implementieren**

`alexa-air-quality-section.component.ts`:

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { AlexaAirQualityService } from '../../services/alexa-air-quality.service';
import {
  ALEXA_AIR_QUALITY_METRICS,
  AlexaAirQualityMetric,
  AlexaAirQualityMetricKey,
  AlexaAirQualityReading,
  IaqLevel,
  iaqLevel
} from '../../models/alexa-air-quality.model';

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

@Component({
  selector: 'app-alexa-air-quality-section',
  standalone: true,
  imports: [CommonModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './alexa-air-quality-section.component.html',
  styleUrl: './alexa-air-quality-section.component.scss'
})
export class AlexaAirQualitySectionComponent implements OnInit {
  private readonly alexaAirQualityService = inject(AlexaAirQualityService);

  readonly metrics = ALEXA_AIR_QUALITY_METRICS;

  latest: AlexaAirQualityReading[] = [];
  selectedMetric: AlexaAirQualityMetricKey = 'iaq';
  selectedYear: number | 'ALL' = 'ALL';
  selectedMonth: number | 'ALL' = 'ALL';
  selectedDay: number | 'ALL' = 'ALL';
  availableYears: number[] = [];
  availableMonths: number[] = [];
  availableDays: number[] = [];
  chartOptions: Record<string, unknown> | null = null;
  isLoading = true;
  errorMessage: string | null = null;

  private readings: AlexaAirQualityReading[] = [];

  ngOnInit(): void {
    this.alexaAirQualityService.getLatest().subscribe({
      next: latest => (this.latest = latest),
      error: () => (this.latest = [])
    });
    this.alexaAirQualityService.getReadings().subscribe({
      next: readings => {
        this.readings = readings;
        this.availableYears = [...new Set(readings.map(r => r.readingTime.getFullYear()))].sort();
        this.isLoading = false;
        this.refreshChart();
      },
      error: err => {
        this.errorMessage = err.message;
        this.isLoading = false;
      }
    });
  }

  get hasData(): boolean {
    return this.readings.length > 0 || this.latest.length > 0;
  }

  metricValue(reading: AlexaAirQualityReading, key: AlexaAirQualityMetricKey): number | null {
    return reading[key];
  }

  iaqLevelFor(reading: AlexaAirQualityReading): IaqLevel {
    return iaqLevel(reading.iaq);
  }

  setMetric(key: string): void {
    this.selectedMetric = key as AlexaAirQualityMetricKey;
    this.refreshChart();
  }

  setYear(value: string): void {
    this.selectedYear = value === 'ALL' ? 'ALL' : Number(value);
    this.selectedMonth = 'ALL';
    this.selectedDay = 'ALL';
    this.availableMonths = this.selectedYear === 'ALL' ? [] : this.monthsFor(this.selectedYear);
    this.availableDays = [];
    this.refreshChart();
  }

  setMonth(value: string): void {
    this.selectedMonth = value === 'ALL' ? 'ALL' : Number(value);
    this.selectedDay = 'ALL';
    this.availableDays =
      this.selectedYear === 'ALL' || this.selectedMonth === 'ALL'
        ? []
        : this.daysFor(this.selectedYear, this.selectedMonth);
    this.refreshChart();
  }

  setDay(value: string): void {
    this.selectedDay = value === 'ALL' ? 'ALL' : Number(value);
    this.refreshChart();
  }

  private monthsFor(year: number): number[] {
    return [...new Set(
      this.readings
        .filter(r => r.readingTime.getFullYear() === year)
        .map(r => r.readingTime.getMonth() + 1)
    )].sort((a, b) => a - b);
  }

  private daysFor(year: number, month: number): number[] {
    return [...new Set(
      this.readings
        .filter(r => r.readingTime.getFullYear() === year && r.readingTime.getMonth() + 1 === month)
        .map(r => r.readingTime.getDate())
    )].sort((a, b) => a - b);
  }

  private filteredReadings(): AlexaAirQualityReading[] {
    return this.readings.filter(r => {
      const time = r.readingTime;
      if (this.selectedYear !== 'ALL' && time.getFullYear() !== this.selectedYear) return false;
      if (this.selectedMonth !== 'ALL' && time.getMonth() + 1 !== this.selectedMonth) return false;
      if (this.selectedDay !== 'ALL' && time.getDate() !== this.selectedDay) return false;
      return true;
    });
  }

  private refreshChart(): void {
    const filtered = this.filteredReadings();
    if (!filtered.length) {
      this.chartOptions = null;
      return;
    }
    const metric = this.metrics.find(m => m.key === this.selectedMetric) as AlexaAirQualityMetric;
    const deviceNames = [...new Set(filtered.map(r => r.deviceName))];
    const series = deviceNames.map(name => ({
      name,
      type: 'line',
      showSymbol: false,
      connectNulls: true,
      data: filtered
        .filter(r => r.deviceName === name)
        .map(r => [r.readingTime.getTime(), this.metricValue(r, this.selectedMetric)])
    }));
    this.chartOptions = {
      tooltip: { trigger: 'axis' },
      legend: { data: deviceNames },
      grid: { left: 48, right: 16, top: 40, bottom: 32 },
      xAxis: { type: 'time' },
      yAxis: { type: 'value', name: metric.unit },
      series
    };
  }
}
```

- [ ] **Step 2: Template**

`alexa-air-quality-section.component.html`:

```html
<section class="alexa-aq">
  <header class="alexa-aq__header">
    <div>
      <h2 class="alexa-aq__title">Innenraum (Amazon Air Quality Monitor)</h2>
      <p class="alexa-aq__subtitle">Aktuelle Werte und Verlauf der Amazon Smart Air Quality Monitore.</p>
    </div>
  </header>

  @if (isLoading) {
    <div class="alexa-aq__state">Laedt Innenraum-Daten...</div>
  } @else if (errorMessage) {
    <div class="alexa-aq__state alexa-aq__state--error">{{ errorMessage }}</div>
  } @else if (!hasData) {
    <div class="alexa-aq__state">
      Noch keine Daten der Amazon-Luftsensoren. Ist der Alexa-Login aktiv (Seite „Ansagen")?
    </div>
  } @else {
    <div class="alexa-aq__tiles">
      @for (device of latest; track device.applianceId) {
        <div class="alexa-aq__tile" [attr.data-level]="iaqLevelFor(device)">
          <div class="alexa-aq__tile-header">
            <span class="alexa-aq__device">{{ device.deviceName }}</span>
            <span class="alexa-aq__iaq-badge">IAQ {{ device.iaq ?? '–' }}</span>
          </div>
          <dl class="alexa-aq__values">
            @for (metric of metrics; track metric.key) {
              @if (metric.key !== 'iaq') {
                <div class="alexa-aq__value">
                  <dt>{{ metric.label }}</dt>
                  <dd>
                    {{ metricValue(device, metric.key) ?? '–' }}
                    <span class="alexa-aq__unit">{{ metric.unit }}</span>
                  </dd>
                </div>
              }
            }
          </dl>
          <p class="alexa-aq__timestamp">Stand: {{ device.readingTime | date: 'dd.MM.yyyy HH:mm' }}</p>
        </div>
      }
    </div>

    <div class="alexa-aq__chart-card">
      <div class="alexa-aq__chart-controls">
        <label class="alexa-aq__select">
          <span>Kennzahl</span>
          <select (change)="setMetric($any($event.target).value)">
            @for (metric of metrics; track metric.key) {
              <option [value]="metric.key" [selected]="selectedMetric === metric.key">{{ metric.label }}</option>
            }
          </select>
        </label>
        <label class="alexa-aq__select">
          <span>Jahr</span>
          <select (change)="setYear($any($event.target).value)">
            <option value="ALL" [selected]="selectedYear === 'ALL'">Alle Jahre</option>
            @for (year of availableYears; track year) {
              <option [value]="year" [selected]="selectedYear === year">{{ year }}</option>
            }
          </select>
        </label>
        @if (selectedYear !== 'ALL') {
          <label class="alexa-aq__select">
            <span>Monat</span>
            <select (change)="setMonth($any($event.target).value)">
              <option value="ALL" [selected]="selectedMonth === 'ALL'">Alle Monate</option>
              @for (month of availableMonths; track month) {
                <option [value]="month" [selected]="selectedMonth === month">{{ month }}</option>
              }
            </select>
          </label>
        }
        @if (selectedMonth !== 'ALL') {
          <label class="alexa-aq__select">
            <span>Tag</span>
            <select (change)="setDay($any($event.target).value)">
              <option value="ALL" [selected]="selectedDay === 'ALL'">Alle Tage</option>
              @for (day of availableDays; track day) {
                <option [value]="day" [selected]="selectedDay === day">{{ day }}</option>
              }
            </select>
          </label>
        }
      </div>
      @if (chartOptions) {
        <div class="alexa-aq__chart">
          <div echarts class="alexa-aq__echart" [options]="chartOptions"></div>
        </div>
      } @else {
        <div class="alexa-aq__state">Keine Daten fuer die gewaehlte Auswahl.</div>
      }
    </div>
  }
</section>
```

- [ ] **Step 3: Styles** (an `airrohr-charts.component.scss`-Variablen/Muster anlehnen — vorher kurz reinschauen und Farbwerte/Card-Stile übernehmen)

`alexa-air-quality-section.component.scss`:

```scss
.alexa-aq {
  margin-bottom: 2rem;

  &__header {
    margin-bottom: 1rem;
  }

  &__title {
    margin: 0;
    font-size: 1.25rem;
  }

  &__subtitle {
    margin: 0.25rem 0 0;
    color: rgba(0, 0, 0, 0.6);
    font-size: 0.9rem;
  }

  &__state {
    padding: 1rem;
    border-radius: 0.75rem;
    background: rgba(0, 0, 0, 0.04);
    color: rgba(0, 0, 0, 0.6);

    &--error {
      background: rgba(220, 53, 69, 0.08);
      color: #b02a37;
    }
  }

  &__tiles {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 1rem;
    margin-bottom: 1rem;
  }

  &__tile {
    padding: 1rem;
    border-radius: 0.75rem;
    background: #fff;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
    border-left: 4px solid #9e9e9e;

    &[data-level='good'] {
      border-left-color: #2e7d32;
    }

    &[data-level='moderate'] {
      border-left-color: #f9a825;
    }

    &[data-level='bad'] {
      border-left-color: #c62828;
    }
  }

  &__tile-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 0.75rem;
  }

  &__device {
    font-weight: 600;
  }

  &__iaq-badge {
    font-weight: 700;
    font-size: 1.1rem;
  }

  &__values {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.5rem 1rem;
    margin: 0;
  }

  &__value {
    dt {
      font-size: 0.75rem;
      color: rgba(0, 0, 0, 0.55);
    }

    dd {
      margin: 0;
      font-weight: 600;
    }
  }

  &__unit {
    font-weight: 400;
    font-size: 0.8rem;
    color: rgba(0, 0, 0, 0.55);
  }

  &__timestamp {
    margin: 0.75rem 0 0;
    font-size: 0.75rem;
    color: rgba(0, 0, 0, 0.5);
  }

  &__chart-card {
    padding: 1rem;
    border-radius: 0.75rem;
    background: #fff;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  }

  &__chart-controls {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    margin-bottom: 0.75rem;
  }

  &__select {
    display: flex;
    flex-direction: column;
    font-size: 0.75rem;
    gap: 0.25rem;

    select {
      padding: 0.35rem 0.5rem;
      border-radius: 0.5rem;
      border: 1px solid rgba(0, 0, 0, 0.2);
    }
  }

  &__chart {
    height: 320px;
  }

  &__echart {
    width: 100%;
    height: 100%;
  }
}
```

- [ ] **Step 4: Build prüfen**

Run: `cd frontend; npm run build`
Expected: Build erfolgreich (Komponente ist noch nirgends eingebunden — das ist ok)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/airrohr-charts/alexa-air-quality-section.component.*
git commit -m "feat(frontend): indoor air quality section with tiles and history chart"
```

---

### Task 10: Frontend — Einbindung in die Luftqualitäts-Seite

**Files:**
- Modify: `frontend/src/app/pages/airrohr-charts/airrohr-charts.component.ts`
- Modify: `frontend/src/app/pages/airrohr-charts/airrohr-charts.component.html`

- [ ] **Step 1: Komponente einbinden**

In `airrohr-charts.component.ts`: Import ergänzen und in das `imports`-Array aufnehmen:

```typescript
import { AlexaAirQualitySectionComponent } from './alexa-air-quality-section.component';
```

```typescript
  imports: [CommonModule, IconComponent, NgxEchartsDirective, AlexaAirQualitySectionComponent],
```

In `airrohr-charts.component.html`: Header-Texte zur gemeinsamen Seite verallgemeinern und die Sektion direkt nach dem `</header>` (nach Zeile 15) einfügen:

```html
    <header class="airrohr-charts__header">
      <div>
        <p class="airrohr-charts__eyebrow">Luftqualitaet</p>
        <h1 class="airrohr-charts__title">Luftqualitaet: Innenraum &amp; Aussen</h1>
        <p class="airrohr-charts__subtitle">
          Innenraum-Werte der Amazon Smart Air Quality Monitore und Feinstaub-Verlauf des Airrohr-Sensors.
        </p>
      </div>
      <div class="airrohr-charts__legend">
        <app-icon name="wind" class="airrohr-charts__icon"></app-icon>
        <span>Einheit: ug/m3 (Airrohr)</span>
      </div>
    </header>

    <app-alexa-air-quality-section />

    <h2 class="airrohr-charts__section-title">Aussen (Airrohr Feinstaub)</h2>
```

Und in `airrohr-charts.component.scss` die kleine Überschriften-Klasse ergänzen:

```scss
.airrohr-charts__section-title {
  margin: 0 0 1rem;
  font-size: 1.25rem;
}
```

- [ ] **Step 2: Build + manuelle Sichtprüfung**

Run: `cd frontend; npm run build`
Expected: Build erfolgreich.

Optional (wenn Backend + Sidecar laufen): `npm start` und `http://localhost:4200` öffnen → Luftqualitäts-Seite zeigt die Innenraum-Sektion; ohne Daten erscheint der Hinweistext.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/pages/airrohr-charts/
git commit -m "feat(frontend): embed indoor air quality section on air quality page"
```

---

### Task 11: Doku + End-to-End-Verifikation

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Smart Device Integrations")

- [ ] **Step 1: CLAUDE.md ergänzen**

Im Abschnitt „Smart Device Integrations" nach dem Alexa-TTS-Block einfügen:

```markdown
### Amazon Smart Air Quality Monitor
- No official/local API; values are read via the inofficial Alexa smart home (phoenix) API through the alexa-remote2 sidecar
- Sidecar endpoints: `GET /smarthome/air-quality-monitors` (discovery incl. sensor instance mapping), `GET /smarthome/air-quality-monitors/state` (normalized flat values)
- Backend polls every 5 minutes (`AlexaAirQualityPollingService`), stores readings in `alexa_air_quality_readings`, reports entity states (`EntitySource.ALEXA`) usable as flow triggers
- Sensors: IAQ score, PM2.5, VOC, CO, temperature, humidity; device identity via stable `applianceId`
- Frontend: indoor section on the air quality page (`alexa-air-quality-section.component`)
```

- [ ] **Step 2: Alle neuen Tests gesammelt laufen lassen**

```powershell
cd alexa-sidecar; npm test
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; cd ..\backend; mvn test -Dtest='AlexaAirQuality*Test'
cd ..\frontend; ng test --watch=false --browsers=ChromeHeadless --include='**/alexa-air-quality.model.spec.ts'
```

Expected: alles PASS.

- [ ] **Step 3: End-to-End (nur mit laufendem Stack: MariaDB, Backend, Sidecar eingeloggt)**

```powershell
# Poll anstossen und Ergebnis pruefen
curl -X POST http://localhost:8080/api/v1/admin/alexa-air-quality-polling/trigger
curl http://localhost:8080/api/v1/admin/alexa-air-quality-polling
curl http://localhost:8080/api/v1/alexa/air-quality/latest
```

Expected: Status ohne `lastError`, `latest` liefert 2 Geräte mit Werten. Zusätzlich prüfen, dass die Entities auftauchen: `curl http://localhost:8080/api/v1/entities` → Einträge `sensor.alexa_..._pm25` usw.
Ist der Stack nicht verfügbar: diesen Step als offen an den Nutzer melden.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document Amazon Smart Air Quality Monitor integration"
```
