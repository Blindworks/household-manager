# Smart-Device-Persistenz mit Rescan — Design

**Datum:** 2026-07-06
**Status:** Vom User freigegeben

## Problem

Tapo-Plugs werden bei jedem Backend-Neustart und teils bei jedem Seitenaufruf neu
per UDP/Cloud gesucht (In-Memory-Cache, `scanAllDeviceTypes()` im `ngOnInit` der
Devices-Seite). Die vorhandene `smart_devices`-Infrastruktur (Tabelle, Scan-Endpoint
`POST /api/devices/scan`, DB-gestütztes Schalten) ist funktionsfähig, wird aber
nirgends als Quelle der Wahrheit genutzt. Der Cloud-Fallback für Tapo-Steuerung
liefert grundsätzlich `-20571 "Device is offline"` und ist damit nutzlos und
irreführend.

## Entscheidungen (User)

1. Backend-DB-Anbindung **und** Umstellung des Admin-Views („Beides").
2. Bei nicht erreichbarer gespeicherter IP: **automatische Re-Discovery** mit
   DB-Update und Wiederholung des Befehls.
3. Admin-Tab „Smart Plugs": **eine einheitliche Geräteliste** (gemeinsame
   Komponente mit der Devices-Seite) statt drei typspezifischer Bereiche.

## Backend

### IP-Auflösung (`TapoDeviceService.resolveIpAddress`)

Neue Reihenfolge:

1. In-Memory-Cache (`deviceIpCache`)
2. Statische Konfiguration (`tapo.devices[n]`)
3. **`smart_devices`-Tabelle**: `findByDeviceTypeAndExternalDeviceId(TAPO, deviceId)`
   → `ipAddress` und `authProtocol` (aus Metadata-JSON)
4. UDP-Auto-Discovery (letzter Ausweg)

`TapoDeviceService` injiziert direkt `SmartDeviceRepository` (Service→Repository
ist schichtenkonform; vermeidet Zirkularität mit `SmartDeviceService`, der bereits
von `TapoDeviceService` abhängt).

### Discovery lernt in die DB

Jede erfolgreiche Auto-Discovery (`discoverLocalDevices`) upsertet pro gefundenem
Gerät: deviceId → IP, `authProtocol` (Metadata), Name (KLAP-Nickname, Fallback
Modell), Modell. Bestehende Datensätze werden aktualisiert (IP/Protokoll/Name),
fehlende minimal angelegt. Damit sind IPs nach der ersten Suche über Neustarts
hinweg bekannt.

### Selbstheilung bei IP-Wechsel

Schlägt die lokale Steuerung (KLAP und AES) mit einer aus DB/Config/Cache
stammenden IP fehl:

1. Einmalige Re-Discovery (UDP)
2. Liefert sie eine neue IP für die deviceId: DB und Caches aktualisieren,
   Befehl **einmal** wiederholen
3. Sonst (oder bei erneutem Fehlschlag): `TapoException` mit klarer Meldung
   („Gerät lokal nicht erreichbar, auch nach erneuter Suche")

Keine Endlos-Retries; genau ein Re-Discovery-Zyklus pro Befehl.

### Cloud-Fallback für Tapo-Steuerung entfernen

`turnOn`/`turnOff`/`getStatus`/`getEnergyUsage` in `TapoDeviceService` fallen
nicht mehr auf den V2-Cloud-Passthrough zurück (liefert immer `-20571`). Die
Cloud bleibt ausschließlich für die Geräteliste (`discoverCloudDevices`) im
Einsatz. Fehlermeldungen nennen den lokalen Grund.

## Frontend

### Devices-Seite

`ngOnInit` lädt nur noch `GET /api/devices` (DB-Liste). Kein automatischer Scan
mehr. Rescan über Buttons: je Gerätetyp und „Alle scannen"
(`POST /api/devices/scan`).

### Gemeinsame Listen-Komponente

Neue wiederverwendbare Komponente `SmartDeviceListComponent` in
`frontend/src/app/components/`:

- Anzeige: Name, Typ, Modell, IP, online, an/aus
- Aktionen: Schalter (an/aus), Refresh pro Gerät, Rescan-Buttons
- Genutzt von: Devices-Seite und Admin-Tab „Smart Plugs"

Der Admin-Tab ersetzt seine drei Bereiche (Kasa/Tapo/Meross inkl. zugehörigem
Component-Code) durch diese Komponente.

### Bewusst weggelassen (YAGNI)

- Tapo-Energieanzeige in der einheitlichen Liste (bei Bedarf später als
  aufklappbares Detail)
- Kein Schema-Change: `ip_address`-Spalte und `metadata.authProtocol` genügen
- Kein Discovery-Warmup beim Start (DB ersetzt ihn)

## Tests

- **Backend** (Mocks für Repository/Discovery):
  - `resolveIpAddress` liest IP+Protokoll aus der DB, wenn Cache/Config leer
  - Auto-Discovery upsertet Geräte in die DB
  - Selbstheilung: fehlgeschlagene lokale Steuerung → Re-Discovery → DB-Update
    → genau ein Retry; danach Fehler
  - Kein Cloud-Passthrough-Aufruf mehr in Steuerpfaden
- **Frontend**: Devices-Seite ruft beim Laden keinen Scan auf; Rescan-Button
  löst `POST /api/devices/scan` aus

## Nicht-Ziele

- Keine Änderungen an Kasa-/Meross-Steuerlogik (nur Anzeige über die
  einheitliche Liste)
- Keine Entfernung der bestehenden typspezifischen REST-Endpoints
  (`/api/tapo/...`, `/api/meross/...`, `/api/kasa/...`)
