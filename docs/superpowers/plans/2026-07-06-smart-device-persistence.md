# Smart-Device-Persistenz mit Rescan — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapo-Plugs werden in der `smart_devices`-Tabelle persistiert (IP + Protokoll), Schalten nutzt die DB statt Neu-Discovery, IP-Wechsel heilen sich selbst, und Frontend (Devices-Seite + Admin-Tab) zeigt eine einheitliche DB-Geräteliste mit manuellem Rescan.

**Architecture:** `TapoDeviceService` liest/schreibt `SmartDeviceRepository` direkt (Service→Repository, kein Zirkel mit `SmartDeviceService`). Der nutzlose Tapo-Cloud-Steuerungs-Fallback (immer `-20571`) entfällt zugunsten einer einmaligen Re-Discovery mit DB-Update. Im Frontend wird die Geräteliste aus `pages/devices` in eine wiederverwendbare `SmartDeviceListComponent` extrahiert, die auch den Admin-Tab „Smart Plugs" ersetzt.

**Tech Stack:** Spring Boot 3.4 / Java 21 / JUnit 5 + Mockito; Angular 19 standalone / Jasmine+Karma.

**Spec:** `docs/superpowers/specs/2026-07-06-smart-device-persistence-design.md`

**Build-Hinweise:**
- Backend: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` vor jedem `mvn`; aus `backend/` ausführen.
- Bekannte, zu ignorierende Testfehler (lokale DB fehlt): `HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest` (2 Tests).
- Frontend: aus `frontend/`; Tests: `npx ng test --watch=false --browsers=ChromeHeadless --include='<spec-pfad>'`.

---

### Task 0: Feature-Branch

- [ ] **Step 0.1: Branch anlegen**

```bash
git checkout -b feature/smart-device-persistence
```

---

### Task 1: Backend — `resolveIpAddress` liest aus der DB

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tapo/TapoDeviceService.java`
- Test: `backend/src/test/java/com/household/manager/tapo/TapoDeviceServicePersistenceTest.java` (neu)

Kontext: `resolveIpAddress` (bisher `private`) prüft Cache → statische Config → UDP-Discovery. Neu: nach der statischen Config kommt die `smart_devices`-Tabelle. `TapoDeviceService` hat einen **manuellen Konstruktor** (mit Konfigurations-Logging) — dort zwei Parameter ergänzen. Für den Test wird `resolveIpAddress` package-private.

- [ ] **Step 1.1: Fehlschlagenden Test schreiben**

```java
package com.household.manager.tapo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Persistenz-Verhalten des TapoDeviceService: gespeicherte IPs kommen aus der
 * smart_devices-Tabelle statt aus einer erneuten Discovery.
 */
class TapoDeviceServicePersistenceTest {

    private final SmartDeviceRepository repository = mock(SmartDeviceRepository.class);
    private final TapoCloudService cloudService = mock(TapoCloudService.class);
    private final TapoDiscoveryService discoveryService = mock(TapoDiscoveryService.class);
    private final TapoDeviceFactory deviceFactory = mock(TapoDeviceFactory.class);

    private TapoDeviceService newService() {
        return new TapoDeviceService(cloudService, discoveryService, deviceFactory,
                new TapoProperties(), repository, new ObjectMapper());
    }

    @Test
    @DisplayName("IP und Protokoll kommen aus der smart_devices-Tabelle")
    void resolvesIpAddressFromDatabase() {
        SmartDevice stored = new SmartDevice();
        stored.setDeviceType(DeviceType.TAPO);
        stored.setExternalDeviceId("ABC123");
        stored.setDeviceName("Stehlampe");
        stored.setIpAddress("192.168.1.112");
        stored.setMetadata("{\"authProtocol\":\"KLAP\"}");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "ABC123"))
                .thenReturn(Optional.of(stored));

        assertEquals("192.168.1.112", newService().resolveIpAddress("ABC123"));
    }

    @Test
    @DisplayName("Ohne DB-Treffer faellt die Aufloesung auf die Discovery zurueck")
    void fallsBackToDiscoveryWhenDatabaseHasNoIp() {
        when(repository.findByDeviceTypeAndExternalDeviceId(any(), any()))
                .thenReturn(Optional.empty());
        // Discovery liefert nichts -> null
        assertNull(newService().resolveIpAddress("UNKNOWN"));
    }
}
```

- [ ] **Step 1.2: RED verifizieren**

Run: `mvn test -Dtest=TapoDeviceServicePersistenceTest -q`
Expected: COMPILATION ERROR — Konstruktor hat nur 4 Parameter, `resolveIpAddress` ist private.

- [ ] **Step 1.3: Implementieren**

In `TapoDeviceService.java`:

a) Imports ergänzen:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
```

b) Felder + Konstruktor erweitern (bestehender manueller Konstruktor):

```java
    private final SmartDeviceRepository smartDeviceRepository;
    private final ObjectMapper objectMapper;

    public TapoDeviceService(TapoCloudService tapoCloudService,
                             TapoDiscoveryService tapoDiscoveryService,
                             TapoDeviceFactory tapoDeviceFactory,
                             TapoProperties tapoProperties,
                             SmartDeviceRepository smartDeviceRepository,
                             ObjectMapper objectMapper) {
        this.tapoCloudService = tapoCloudService;
        this.tapoDiscoveryService = tapoDiscoveryService;
        this.tapoDeviceFactory = tapoDeviceFactory;
        this.tapoProperties = tapoProperties;
        this.smartDeviceRepository = smartDeviceRepository;
        this.objectMapper = objectMapper;
        // ... bestehendes Konfigurations-Logging unveraendert lassen ...
    }
```

c) `resolveIpAddress` von `private` auf package-private ändern und **nach** dem
Block „statische Konfiguration" / **vor** der Auto-Discovery einfügen:

```java
    String resolveIpAddress(String deviceId) {
        // ... bestehend: Cache-Check, statische Konfiguration ...

        SmartDevice stored = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, deviceId)
                .orElse(null);
        if (stored != null && stored.getIpAddress() != null && !stored.getIpAddress().isBlank()) {
            deviceIpCache.put(deviceId, stored.getIpAddress());
            TapoAuthProtocol storedProtocol = readAuthProtocol(stored.getMetadata());
            if (storedProtocol != null && storedProtocol != TapoAuthProtocol.UNKNOWN) {
                workingProtocolCache.put(deviceId, storedProtocol);
            }
            log.info("IP fuer {} aus Datenbank: {}", deviceId, stored.getIpAddress());
            return stored.getIpAddress();
        }

        // ... bestehend: Auto-Discovery ...
    }
```

d) Helfer ergänzen:

```java
    private TapoAuthProtocol readAuthProtocol(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            Object value = objectMapper.readValue(metadataJson, java.util.Map.class).get("authProtocol");
            return value instanceof String name ? TapoAuthProtocol.valueOf(name) : null;
        } catch (Exception ex) {
            return null;
        }
    }
```

- [ ] **Step 1.4: GREEN verifizieren**

Run: `mvn test -Dtest=TapoDeviceServicePersistenceTest -q`
Expected: PASS (2 Tests). Danach `mvn test` — nur die 3 bekannten DB-Fehler.

- [ ] **Step 1.5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tapo/TapoDeviceService.java backend/src/test/java/com/household/manager/tapo/TapoDeviceServicePersistenceTest.java
git commit -m "feat(tapo): resolve device IPs from smart_devices table"
```

---

### Task 2: Backend — Discovery persistiert Geräte in die DB

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tapo/TapoDeviceService.java`
- Test: `backend/src/test/java/com/household/manager/tapo/TapoDeviceServicePersistenceTest.java`

- [ ] **Step 2.1: Fehlschlagende Tests ergänzen** (in `TapoDeviceServicePersistenceTest`)

```java
    @Test
    @DisplayName("Discovery legt unbekannte Geraete in der DB an")
    void discoveryPersistsNewDevices() {
        when(repository.findByDeviceTypeAndExternalDeviceId(any(), any()))
                .thenReturn(Optional.empty());
        when(discoveryService.discoverLocalDevices(any(), any())).thenReturn(java.util.List.of(
                new TapoDiscoveryDevice("192.168.1.153", TapoAuthProtocol.KLAP,
                        "DEV1", "L900-5(EU)", "Lichtstreifen Buero", true)));
        when(deviceFactory.create(any(), any(), any(), any()))
                .thenReturn(mock(TapoLocalDeviceConnection.class));

        newService().discoverLocalDevices();

        org.mockito.ArgumentCaptor<SmartDevice> captor =
                org.mockito.ArgumentCaptor.forClass(SmartDevice.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals("192.168.1.153", saved.getIpAddress());
        assertEquals("DEV1", saved.getExternalDeviceId());
        assertEquals("Lichtstreifen Buero", saved.getDeviceName());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getMetadata().contains("\"authProtocol\":\"KLAP\""));
    }

    @Test
    @DisplayName("Discovery aktualisiert IP, ohne Namen oder fremde Metadata zu ueberschreiben")
    void discoveryUpdatesExistingDeviceWithoutClobbering() {
        SmartDevice existing = new SmartDevice();
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Mein Wunschname");
        existing.setIpAddress("192.168.1.99");
        existing.setMetadata("{\"deviceMac\":\"aa:bb\"}");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV1"))
                .thenReturn(Optional.of(existing));
        when(discoveryService.discoverLocalDevices(any(), any())).thenReturn(java.util.List.of(
                new TapoDiscoveryDevice("192.168.1.153", TapoAuthProtocol.KLAP,
                        "DEV1", "L900-5(EU)", "Lichtstreifen Buero", true)));
        when(deviceFactory.create(any(), any(), any(), any()))
                .thenReturn(mock(TapoLocalDeviceConnection.class));

        newService().discoverLocalDevices();

        org.mockito.ArgumentCaptor<SmartDevice> captor =
                org.mockito.ArgumentCaptor.forClass(SmartDevice.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals("192.168.1.153", saved.getIpAddress());
        assertEquals("Mein Wunschname", saved.getDeviceName());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getMetadata().contains("deviceMac"));
        org.junit.jupiter.api.Assertions.assertTrue(saved.getMetadata().contains("\"authProtocol\":\"KLAP\""));
    }
```

Hinweis: `TapoProperties` ohne E-Mail/Passwort lässt `discoverLocalDevices` die
UDP-Discovery per Validierung überspringen — daher im Test `TapoProperties` mit
Dummy-Credentials versehen: in `newService()` stattdessen

```java
    private TapoDeviceService newService() {
        TapoProperties properties = new TapoProperties();
        properties.setEmail("test@example.com");
        properties.setPassword("secret");
        return new TapoDeviceService(cloudService, discoveryService, deviceFactory,
                properties, repository, new ObjectMapper());
    }
```

- [ ] **Step 2.2: RED verifizieren**

Run: `mvn test -Dtest=TapoDeviceServicePersistenceTest -q`
Expected: FAIL — `repository.save` wird nie aufgerufen (`Wanted but not invoked`).

- [ ] **Step 2.3: Implementieren**

In `TapoDeviceService.discoverLocalDevices()`, im bestehenden Schleifenkörper
(`for (TapoDiscoveryDevice device : devices)`) nach `getOrCreateLocalConnection(...)`:

```java
                persistDiscoveredDevice(device);
```

Neue Methoden:

```java
    private void persistDiscoveredDevice(TapoDiscoveryDevice discovered) {
        try {
            SmartDevice device = smartDeviceRepository
                    .findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, discovered.deviceId())
                    .orElseGet(() -> {
                        SmartDevice created = new SmartDevice();
                        created.setDeviceType(DeviceType.TAPO);
                        created.setExternalDeviceId(discovered.deviceId());
                        created.setCapabilities("SWITCH");
                        return created;
                    });
            if (device.getDeviceName() == null || device.getDeviceName().isBlank()) {
                device.setDeviceName(firstNonBlank(discovered.nickname(), discovered.model(), discovered.deviceId()));
            }
            if (discovered.model() != null && !discovered.model().isBlank()) {
                device.setModel(discovered.model());
            }
            device.setIpAddress(discovered.ipAddress());
            device.setOnline(true);
            device.setPoweredOn(discovered.deviceOn());
            device.setMetadata(mergeAuthProtocol(device.getMetadata(), discovered.authProtocol()));
            smartDeviceRepository.save(device);
        } catch (Exception ex) {
            log.warn("Tapo-Geraet {} konnte nicht persistiert werden: {}",
                    discovered.deviceId(), ex.getMessage());
        }
    }

    private String mergeAuthProtocol(String metadataJson, TapoAuthProtocol protocol) {
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                metadata.putAll(objectMapper.readValue(metadataJson, java.util.Map.class));
            } catch (Exception ex) {
                log.debug("Metadata nicht lesbar, wird neu aufgebaut: {}", ex.getMessage());
            }
        }
        metadata.put("authProtocol", protocol.name());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            return metadataJson;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "Tapo Device";
    }
```

- [ ] **Step 2.4: GREEN verifizieren**

Run: `mvn test -Dtest=TapoDeviceServicePersistenceTest -q`
Expected: PASS (4 Tests).

- [ ] **Step 2.5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tapo/TapoDeviceService.java backend/src/test/java/com/household/manager/tapo/TapoDeviceServicePersistenceTest.java
git commit -m "feat(tapo): persist discovered devices to smart_devices table"
```

---

### Task 3: Backend — Selbstheilung bei IP-Wechsel, Cloud-Steuerungs-Fallback entfernen

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tapo/TapoDeviceService.java`
- Test: `backend/src/test/java/com/household/manager/tapo/TapoDeviceServicePersistenceTest.java`

Kontext: `turnOn`/`turnOff`/`getStatus`/`getEnergyUsage` fallen bisher auf den
V2-Cloud-Passthrough zurück, der für Tapo IMMER `-20571 Device is offline`
liefert. Neu: bei lokalem Fehlschlag genau EINE Re-Discovery, DB/Caches
aktualisieren, Befehl einmal wiederholen; sonst klare `TapoException`.
Vorbestehender Bug wird mitgefixt: `localConnectionCache`-Einträge haben den
Key `deviceId + ":" + protocol.name()`, aber `executeLocalWithFallback` und
`clearLocalConnection` entfernen mit dem Key `deviceId` (trifft nie).

- [ ] **Step 3.1: Fehlschlagende Tests ergänzen** (in `TapoDeviceServicePersistenceTest`)

```java
    @Test
    @DisplayName("Bei toter gespeicherter IP: Re-Discovery, DB-Update, ein Retry")
    void healsStaleIpViaRediscovery() throws Exception {
        TapoLocalDeviceConnection deadConnection = mock(TapoLocalDeviceConnection.class);
        org.mockito.Mockito.doThrow(new TapoException("connect timed out"))
                .when(deadConnection).setDevicePowered(true);
        TapoLocalDeviceConnection freshConnection = mock(TapoLocalDeviceConnection.class);

        when(deviceFactory.create(any(), org.mockito.ArgumentMatchers.eq("192.168.1.99"), any(), any()))
                .thenReturn(deadConnection);
        when(deviceFactory.create(any(), org.mockito.ArgumentMatchers.eq("192.168.1.153"), any(), any()))
                .thenReturn(freshConnection);
        when(repository.findByDeviceTypeAndExternalDeviceId(any(), any()))
                .thenReturn(Optional.empty());
        when(discoveryService.discoverLocalDevices(any(), any())).thenReturn(java.util.List.of(
                new TapoDiscoveryDevice("192.168.1.153", TapoAuthProtocol.KLAP,
                        "DEV1", "P110(EU)", "Stern", true)));

        newService().turnOn("DEV1", "192.168.1.99", TapoAuthProtocol.KLAP);

        org.mockito.Mockito.verify(freshConnection).setDevicePowered(true);
        org.mockito.Mockito.verify(repository).save(any(SmartDevice.class));
        org.mockito.Mockito.verify(cloudService, org.mockito.Mockito.never())
                .setDevicePowered(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("Findet die Re-Discovery nichts, gibt es einen klaren lokalen Fehler")
    void failsWithClearMessageWhenRediscoveryFindsNothing() throws Exception {
        TapoLocalDeviceConnection deadConnection = mock(TapoLocalDeviceConnection.class);
        org.mockito.Mockito.doThrow(new TapoException("connect timed out"))
                .when(deadConnection).setDevicePowered(true);
        when(deviceFactory.create(any(), any(), any(), any())).thenReturn(deadConnection);
        when(repository.findByDeviceTypeAndExternalDeviceId(any(), any()))
                .thenReturn(Optional.empty());
        when(discoveryService.discoverLocalDevices(any(), any()))
                .thenReturn(java.util.List.of());

        TapoException ex = org.junit.jupiter.api.Assertions.assertThrows(TapoException.class,
                () -> newService().turnOn("DEV1", "192.168.1.99", TapoAuthProtocol.KLAP));

        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("erneuter Suche"));
        org.mockito.Mockito.verify(cloudService, org.mockito.Mockito.never())
                .setDevicePowered(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
```

Hinweis: `TapoLocalDeviceConnection.setDevicePowered` deklariert keine checked
Exception — falls `doThrow(new TapoException(...))` nicht kompiliert, ist
`TapoException` eine RuntimeException (prüfen in
`backend/src/main/java/com/household/manager/tapo/TapoException.java`) — dann passt es.

- [ ] **Step 3.2: RED verifizieren**

Run: `mvn test -Dtest=TapoDeviceServicePersistenceTest -q`
Expected: FAIL — aktuell wird `cloudService.setDevicePowered` aufgerufen
(Test 1: `never()`-Verify schlägt fehl; Test 2: keine TapoException mit „erneuter Suche").

- [ ] **Step 3.3: Implementieren**

a) `turnOn`/`turnOff`/`getStatus`/`getEnergyUsage` umbauen — Cloud-Zweige entfernen:

```java
    public void turnOn(String deviceId) {
        turnOn(deviceId, resolveIpAddress(deviceId), null);
    }

    public void turnOn(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        executeLocalWithRediscovery(deviceId, ipAddress, protocol,
                conn -> { conn.setDevicePowered(true); return null; });
        log.info("Tapo device switched on locally (deviceId={})", deviceId);
    }

    public void turnOff(String deviceId) {
        turnOff(deviceId, resolveIpAddress(deviceId), null);
    }

    public void turnOff(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        executeLocalWithRediscovery(deviceId, ipAddress, protocol,
                conn -> { conn.setDevicePowered(false); return null; });
        log.info("Tapo device switched off locally (deviceId={})", deviceId);
    }

    public TapoDeviceState getStatus(String deviceId) {
        return getStatus(deviceId, resolveIpAddress(deviceId), null);
    }

    public TapoDeviceState getStatus(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        JsonNode deviceInfo = executeLocalWithRediscovery(deviceId, ipAddress, protocol,
                TapoLocalDeviceConnection::getDeviceInfo);
        return TapoDeviceState.fromLocal(deviceInfo, tapoCloudService);
    }

    public JsonNode getEnergyUsage(String deviceId) {
        return getEnergyUsage(deviceId, resolveIpAddress(deviceId), null);
    }

    public JsonNode getEnergyUsage(String deviceId, String ipAddress, TapoAuthProtocol protocol) {
        return executeLocalWithRediscovery(deviceId, ipAddress, protocol,
                TapoLocalDeviceConnection::getEnergyUsage);
    }
```

b) Neue Kernmethode + Re-Discovery-Helfer:

```java
    /**
     * Fuehrt eine lokale Aktion aus. Schlaegt sie fehl (oder fehlt die IP),
     * laeuft genau EINE Re-Discovery; liefert sie eine neue IP, wird die Aktion
     * einmal wiederholt. Der Tapo-Cloud-Passthrough kann Geraete nicht steuern
     * (immer -20571) und wird bewusst nicht mehr verwendet.
     */
    private JsonNode executeLocalWithRediscovery(String deviceId, String ipAddress,
                                                 TapoAuthProtocol protocol,
                                                 LocalDeviceAction action) {
        if (ipAddress == null || ipAddress.isBlank()) {
            String discovered = rediscoverIp(deviceId);
            if (discovered == null) {
                throw new TapoException("Keine IP fuer Tapo-Geraet " + deviceId
                        + " bekannt (auch nach erneuter Suche). Bitte Rescan ausfuehren.");
            }
            return executeLocalWithFallback(deviceId, discovered, resolveProtocol(deviceId, protocol), action);
        }
        try {
            return executeLocalWithFallback(deviceId, ipAddress, protocol, action);
        } catch (Exception ex) {
            log.info("Lokale Steuerung fuer {} ({}) fehlgeschlagen ({}), starte Re-Discovery",
                    deviceId, ipAddress, ex.getMessage());
            String freshIp = rediscoverIp(deviceId);
            if (freshIp == null || freshIp.equals(ipAddress)) {
                throw new TapoException("Tapo-Geraet " + deviceId
                        + " ist lokal nicht erreichbar (auch nach erneuter Suche): " + ex.getMessage(), ex);
            }
            log.info("Neue IP fuer {} gefunden: {} (vorher {})", deviceId, freshIp, ipAddress);
            return executeLocalWithFallback(deviceId, freshIp, resolveProtocol(deviceId, null), action);
        }
    }

    private String rediscoverIp(String deviceId) {
        deviceIpCache.remove(deviceId);
        removeLocalConnections(deviceId);
        try {
            discoverLocalDevices();
        } catch (Exception ex) {
            log.debug("Re-Discovery fehlgeschlagen: {}", ex.getMessage());
        }
        return deviceIpCache.get(deviceId);
    }

    private void removeLocalConnections(String deviceId) {
        for (TapoAuthProtocol protocol : TapoAuthProtocol.values()) {
            localConnectionCache.remove(deviceId + ":" + protocol.name());
        }
    }
```

c) Cache-Key-Bug fixen: in `executeLocalWithFallback` beide Vorkommen von
`localConnectionCache.remove(deviceId)` durch den korrekten Protokoll-Key ersetzen
(im ersten Catch: `localConnectionCache.remove(deviceId + ":" + preferred.name())`,
im zweiten: `... + alternative.name()`), und in `clearLocalConnection` den Aufruf
`localConnectionCache.remove(deviceId)` durch `removeLocalConnections(deviceId)` ersetzen.

d) `getCurrentPower` bleibt unverändert (verlangt explizit eine IP).

- [ ] **Step 3.4: GREEN verifizieren**

Run: `mvn test -Dtest=TapoDeviceServicePersistenceTest -q` → PASS (6 Tests).
Danach: `mvn test` → nur die 3 bekannten DB-Fehler; insbesondere
`TapoCloudServiceTest`, `TapoDeviceStateTest`, `TapoDiscoveryServiceTest`,
`TapoKlapSessionKeyTest` weiter grün.

- [ ] **Step 3.5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tapo/TapoDeviceService.java backend/src/test/java/com/household/manager/tapo/TapoDeviceServicePersistenceTest.java
git commit -m "feat(tapo): self-heal stale IPs via rediscovery, drop useless cloud control fallback"
```

---

### Task 4: Frontend — Devices-Seite lädt aus der DB statt zu scannen

**Files:**
- Modify: `frontend/src/app/pages/devices/devices.component.ts:28-30`
- Test: `frontend/src/app/pages/devices/devices.component.spec.ts` (neu)

- [ ] **Step 4.1: Fehlschlagenden Test schreiben**

```typescript
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { DevicesComponent } from './devices.component';
import { SmartDeviceService } from '../../services/smart-device.service';

describe('DevicesComponent', () => {
  let serviceSpy: jasmine.SpyObj<SmartDeviceService>;

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('SmartDeviceService', [
      'getAllDevices', 'scanDevices', 'refreshDeviceState', 'turnOn', 'turnOff'
    ]);
    serviceSpy.getAllDevices.and.returnValue(of([]));
    serviceSpy.scanDevices.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DevicesComponent],
      providers: [{ provide: SmartDeviceService, useValue: serviceSpy }]
    }).compileComponents();
  });

  it('laedt beim Start nur die Geraeteliste aus der DB, ohne Scan', () => {
    const fixture = TestBed.createComponent(DevicesComponent);
    fixture.detectChanges(); // ngOnInit

    expect(serviceSpy.getAllDevices).toHaveBeenCalled();
    expect(serviceSpy.scanDevices).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 4.2: RED verifizieren**

Run (aus `frontend/`):
`npx ng test --watch=false --browsers=ChromeHeadless --include='**/devices.component.spec.ts'`
Expected: FAIL — `scanDevices` wurde aufgerufen.

- [ ] **Step 4.3: Implementieren**

In `devices.component.ts`:

```typescript
  ngOnInit(): void {
    this.loadDevices();
  }
```

- [ ] **Step 4.4: GREEN verifizieren**

Gleicher Befehl wie 4.2. Expected: PASS.

- [ ] **Step 4.5: Commit**

```bash
git add frontend/src/app/pages/devices/devices.component.ts frontend/src/app/pages/devices/devices.component.spec.ts
git commit -m "fix(devices): load device list from DB on init instead of full rescan"
```

---

### Task 5: Frontend — `SmartDeviceListComponent` extrahieren

**Files:**
- Create: `frontend/src/app/components/smart-device-list/smart-device-list.component.ts`
- Create: `frontend/src/app/components/smart-device-list/smart-device-list.component.html`
- Create: `frontend/src/app/components/smart-device-list/smart-device-list.component.scss`
- Modify: `frontend/src/app/pages/devices/devices.component.ts` (+ `.html`, `.scss`)
- Test: `frontend/src/app/components/smart-device-list/smart-device-list.component.spec.ts` (neu)

Vorgehen: Die gesamte Listen-Logik der Devices-Seite (devices, isLoading,
isScanning, Fehlerbanner, Gruppierung, Toggle, Refresh, Scan) zieht in die
Komponente um; neu sind Rescan-Buttons je Typ. Die Devices-Seite behält nur
den Seiten-Header und bindet die Komponente ein. SCSS: kompletter Inhalt von
`devices.component.scss` wandert (unverändert, Klassennamen bleiben) in
`smart-device-list.component.scss`; in `devices.component.scss` verbleiben nur
die `dashboard__`-Header-Regeln (`.dashboard`, `.dashboard__header`,
`.dashboard__title`, `.dashboard__subtitle`).

- [ ] **Step 5.1: Fehlschlagenden Test schreiben**

```typescript
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { SmartDeviceListComponent } from './smart-device-list.component';
import { SmartDeviceService } from '../../services/smart-device.service';
import { SmartDevice } from '../../models/smart-device.model';

describe('SmartDeviceListComponent', () => {
  let serviceSpy: jasmine.SpyObj<SmartDeviceService>;

  const device: SmartDevice = {
    id: 1, deviceType: 'TAPO', externalDeviceId: 'DEV1', deviceName: 'Stehlampe',
    model: 'L530E(EU)', ipAddress: '192.168.1.112', isOnline: true, isPoweredOn: false,
    capabilities: ['SWITCH'], metadata: {}, createdAt: '', updatedAt: ''
  } as SmartDevice;

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('SmartDeviceService', [
      'getAllDevices', 'scanDevices', 'refreshDeviceState', 'turnOn', 'turnOff'
    ]);
    serviceSpy.getAllDevices.and.returnValue(of([device]));
    serviceSpy.scanDevices.and.returnValue(of([device]));

    await TestBed.configureTestingModule({
      imports: [SmartDeviceListComponent],
      providers: [{ provide: SmartDeviceService, useValue: serviceSpy }]
    }).compileComponents();
  });

  it('zeigt Geraete aus der DB an', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    expect(serviceSpy.getAllDevices).toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Stehlampe');
  });

  it('stoesst den Rescan fuer einen Typ an', () => {
    const fixture = TestBed.createComponent(SmartDeviceListComponent);
    fixture.detectChanges();

    fixture.componentInstance.scanType('TAPO');

    expect(serviceSpy.scanDevices).toHaveBeenCalledWith('TAPO');
  });
});
```

- [ ] **Step 5.2: RED verifizieren**

Run: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/smart-device-list.component.spec.ts'`
Expected: FAIL (Komponente existiert nicht — Compile-Fehler).

- [ ] **Step 5.3: Komponente implementieren**

`smart-device-list.component.ts` — Logik 1:1 aus `DevicesComponent`
(Methoden `loadDevices`, `scanAllDeviceTypes`, `refreshAllDevices`,
`refreshAllDevicesInBackground`, `toggleDevice`, `isDeviceToggling`,
`groupedDevices`, `getTypeLabel`, `getStatusText`, `getStatusClass`,
`trackByDeviceId`, `dismissError`, `updateDeviceInList` hierher verschieben,
`console.log`-Debug-Ausgaben dabei entfernen) plus:

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { catchError, forkJoin, of } from 'rxjs';
import { SmartDeviceService } from '../../services/smart-device.service';
import { SmartDevice } from '../../models/smart-device.model';

/**
 * Wiederverwendbare Geraeteliste aus der smart_devices-Datenbank
 * mit Schalten, Status-Refresh und manuellem Rescan (gesamt oder je Typ).
 */
@Component({
  selector: 'app-smart-device-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './smart-device-list.component.html',
  styleUrl: './smart-device-list.component.scss'
})
export class SmartDeviceListComponent implements OnInit {
  private readonly smartDeviceService = inject(SmartDeviceService);
  private readonly typeOrder: ReadonlyArray<SmartDevice['deviceType']> = ['KASA', 'TAPO', 'MEROSS'];
  readonly scanTypes: ReadonlyArray<SmartDevice['deviceType']> = ['KASA', 'TAPO', 'MEROSS'];

  devices: SmartDevice[] = [];
  isLoading = true;
  isScanning = false;
  scanningType: SmartDevice['deviceType'] | 'ALL' | null = null;
  errorMessage: string | null = null;
  togglingDevices = new Set<number>();

  ngOnInit(): void {
    this.loadDevices();
  }

  scanType(type: SmartDevice['deviceType']): void {
    this.isScanning = true;
    this.scanningType = type;
    this.errorMessage = null;
    this.smartDeviceService.scanDevices(type).subscribe({
      next: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      },
      error: (error: Error) => {
        this.errorMessage = `Rescan (${this.getTypeLabel(type)}) fehlgeschlagen: ${error.message}`;
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      }
    });
  }

  scanAllDeviceTypes(): void {
    this.isScanning = true;
    this.scanningType = 'ALL';
    this.errorMessage = null;
    const scanRequests = this.scanTypes.map(type =>
      this.smartDeviceService.scanDevices(type).pipe(
        catchError(() => of([] as SmartDevice[]))
      )
    );
    forkJoin(scanRequests).subscribe({
      next: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      },
      error: () => {
        this.isScanning = false;
        this.scanningType = null;
        this.loadDevices();
      }
    });
  }

  // ... uebrige Methoden unveraendert aus DevicesComponent uebernehmen ...
}
```

`smart-device-list.component.html` — Inhalt von `devices.component.html`
ab dem Fehlerbanner (Zeile 19) bis zum Ende des `ng-container` übernehmen
(ohne das umschließende `<div class="dashboard">` und ohne den
`dashboard__header`), davor eine Aktionszeile:

```html
<div class="list-actions">
  <button class="refresh-btn" (click)="scanAllDeviceTypes()" [disabled]="isScanning || isLoading">
    {{ scanningType === 'ALL' ? 'Suche laeuft...' : 'Alle scannen' }}
  </button>
  <button class="refresh-btn" *ngFor="let type of scanTypes"
          (click)="scanType(type)" [disabled]="isScanning || isLoading">
    {{ scanningType === type ? 'Suche laeuft...' : ('Rescan ' + getTypeLabel(type)) }}
  </button>
  <button class="refresh-btn" (click)="refreshAllDevices()" [disabled]="isLoading || devices.length === 0">
    Status aktualisieren
  </button>
</div>

<!-- hierunter: Fehlerbanner, Spinner, Empty-State und device-section-Markup
     unveraendert aus devices.component.html uebernehmen -->
```

`.list-actions` in `smart-device-list.component.scss` ergänzen:

```scss
.list-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
```

- [ ] **Step 5.4: Devices-Seite auf die Komponente umstellen**

`devices.component.ts` ersetzen durch:

```typescript
import { Component } from '@angular/core';
import { SmartDeviceListComponent } from '../../components/smart-device-list/smart-device-list.component';

/**
 * User-facing smart device overview page.
 */
@Component({
  selector: 'app-devices',
  standalone: true,
  imports: [SmartDeviceListComponent],
  templateUrl: './devices.component.html',
  styleUrl: './devices.component.scss'
})
export class DevicesComponent {
}
```

`devices.component.html` ersetzen durch:

```html
<div class="dashboard">
  <header class="dashboard__header">
    <h1 class="dashboard__title">Smart Home</h1>
    <p class="dashboard__subtitle">Verwalte deine Geräte</p>
  </header>

  <app-smart-device-list></app-smart-device-list>
</div>
```

`devices.component.spec.ts` anpassen: der Test aus Task 4 zieht inhaltlich in
`smart-device-list.component.spec.ts` um (dort deckt „zeigt Geraete aus der DB
an" ihn ab); die Devices-Spec reduziert sich auf einen Smoke-Test:

```typescript
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { DevicesComponent } from './devices.component';
import { SmartDeviceService } from '../../services/smart-device.service';

describe('DevicesComponent', () => {
  it('rendert die Geraeteliste', async () => {
    const serviceSpy = jasmine.createSpyObj('SmartDeviceService', ['getAllDevices', 'scanDevices']);
    serviceSpy.getAllDevices.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DevicesComponent],
      providers: [{ provide: SmartDeviceService, useValue: serviceSpy }]
    }).compileComponents();

    const fixture = TestBed.createComponent(DevicesComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-smart-device-list')).toBeTruthy();
    expect(serviceSpy.scanDevices).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 5.5: GREEN verifizieren**

Run: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/smart-device-list.component.spec.ts' --include='**/devices.component.spec.ts'`
Expected: PASS (3 Tests). Zusätzlich: `npx ng build` fehlerfrei.

- [ ] **Step 5.6: Commit**

```bash
git add frontend/src/app/components/smart-device-list/ frontend/src/app/pages/devices/
git commit -m "refactor(frontend): extract reusable SmartDeviceListComponent with per-type rescan"
```

---

### Task 6: Frontend — Admin-Tab „Smart Plugs" auf die einheitliche Liste umstellen

**Files:**
- Modify: `frontend/src/app/pages/admin/admin.component.html:125-368`
- Modify: `frontend/src/app/pages/admin/admin.component.ts`

- [ ] **Step 6.1: Template ersetzen**

In `admin.component.html` den kompletten Abschnitt
`<section class="admin__tab-content" *ngIf="activeTab === 'smart-plugs'">`
(Zeilen 125–368: die drei Karten „Kasa Smart Plug Admin Board",
„Tapo Smart Device Control", „Meross ...") ersetzen durch:

```html
  <section class="admin__tab-content" *ngIf="activeTab === 'smart-plugs'">
    <section class="admin__card">
      <h2>Smart Plugs</h2>
      <p class="muted">Geraete aus der Datenbank (Kasa, Tapo, Meross). Rescan aktualisiert IPs und legt neue Geraete an.</p>
      <app-smart-device-list></app-smart-device-list>
    </section>
  </section>
```

- [ ] **Step 6.2: Toten Admin-Code entfernen**

In `admin.component.ts`:
- `SmartDeviceListComponent` importieren und in das `imports`-Array der
  `@Component`-Deklaration aufnehmen.
- Entfernen (Felder, Methoden, Imports, Injections), jeweils per Suche im File
  verifizieren, dass sie nur vom Smart-Plugs-Tab genutzt wurden:
  - Kasa: `kasaService`, `kasaDevices`, `kasaStatus`, `selectedKasaIp`,
    `isDiscoveringKasa`, `isLoadingKasaStatus`, `isKasaActionRunning`,
    `kasaErrorMessage`, `kasaSuccessMessage`, `discoverKasa`, `loadKasaStatus`,
    `setSelectedKasaIp`, `turnKasaOn`, `turnKasaOff`, `runKasaAction` (falls vorhanden)
  - Tapo: `tapoService`, `tapoDevices`, `tapoInfoById`, `tapoEnergyById`,
    `selectedTapoDeviceId`, `isDiscoveringTapo`, `isLoadingTapoDetails`,
    `isTapoActionRunning`, `tapoErrorMessage`, `tapoSuccessMessage`,
    `discoverTapo`, `setSelectedTapoDeviceId`, `loadSingleTapoDetails`,
    `turnTapoOn`, `turnTapoOff`, `runTapoAction` (falls vorhanden),
    `getSelectedTapoDevice`, `isTapoPowerControlSupported`
  - Meross: `merossService`, `merossDevices`, `merossStatus`,
    `selectedMerossDeviceId`, `isDiscoveringMeross`, `isLoadingMerossStatus`,
    `isMerossActionRunning`, `merossErrorMessage`, `merossSuccessMessage`,
    `discoverMeross`, `loadMerossStatus`, `setSelectedMerossDeviceId`,
    `turnMerossOn`, `turnMerossOff`, `runMerossAction`
  - Zugehörige Imports (`KasaService`, `Kasa*`-Models, `TapoService`,
    `Tapo*`-Models, `MerossService`, `Meross*`-Models) entfernen, sofern nach
    dem Löschen unreferenziert. `FormsModule` nur entfernen, wenn kein anderes
    `[(ngModel)]` im Admin-Template verbleibt (per Suche prüfen!).

- [ ] **Step 6.3: Build + Tests verifizieren**

Run (aus `frontend/`): `npx ng build`
Expected: fehlerfrei — der Compiler findet jede vergessene Referenz.
Run: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/smart-device-list.component.spec.ts' --include='**/devices.component.spec.ts'`
Expected: PASS.

- [ ] **Step 6.4: Commit**

```bash
git add frontend/src/app/pages/admin/ 
git commit -m "refactor(admin): replace per-vendor smart plug panels with unified device list"
```

---

### Task 7: Gesamtverifikation und Merge

- [ ] **Step 7.1: Backend-Suite**

Run (aus `backend/`): `mvn test`
Expected: nur die 3 bekannten DB-Umgebungsfehler.

- [ ] **Step 7.2: Frontend-Build**

Run (aus `frontend/`): `npx ng build --configuration production`
Expected: fehlerfrei.

- [ ] **Step 7.3: Manuelle Verifikation (User oder lokal laufendes Backend)**

1. Backend neu starten, Admin-View → Smart Plugs: Liste kommt aus der DB (leer beim ersten Mal).
2. „Rescan Tapo" → Geräte erscheinen mit IPs.
3. Backend neu starten → Schalten funktioniert sofort ohne Suche (Log: „IP fuer ... aus Datenbank").

- [ ] **Step 7.4: Merge (nach User-Freigabe)**

```bash
git checkout main
git merge --no-ff feature/smart-device-persistence -m "Merge branch 'feature/smart-device-persistence': persist smart plugs in DB with manual rescan"
```
