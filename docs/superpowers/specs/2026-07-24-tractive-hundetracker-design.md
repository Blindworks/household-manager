# Design: Tractive-Hundetracker-Integration

**Datum:** 2026-07-24
**Status:** Entwurf zur Umsetzung

## Ziel

Der GPS-Tracker von Tractive (Hund) wird in den Household-Manager integriert. Drei Nutzungsziele:

1. **Geofence-/Safe-Zone-Alarme** – Verlässt der Hund den sicheren Bereich, feuert ein Zustandswechsel in der Entity-State-Ebene, der als Flow-Trigger eine Benachrichtigung (Telegram/Alexa) auslösen kann.
2. **Position auf Karte** – Eigene Frontend-Seite mit Live-Position auf einer Leaflet/OSM-Karte.
3. **Batterie & Aktivität** – Akkustand des Trackers und (falls verfügbar) Aktivitätsdaten als Sensoren/Kacheln.

## Rahmenbedingungen

- **Keine offizielle API.** Genutzt wird die reverse-engineerte Tractive-REST-API (dieselbe wie `aiotractive`/Home-Assistant):
  - Base-URL `https://graph.tractive.com/4/`
  - Login `POST /auth/token` mit Body `{platform_email, platform_token, grant_type: "tractive"}` und Header `x-tractive-client` (öffentliche App-Client-ID).
  - Antwort: `access_token`, `user_id`, `expires_at`. **Kein Refresh-Token** – zum Erneuern muss E-Mail/Passwort erneut gesendet werden.
- **Reines Java, kein Sidecar.** Der Login ist einfach genug (REST + Token, kein OAuth/2FA-Tanz), daher analog zur Nuki-Integration direkt im Backend. Kein Node/Python-Sidecar.
- **In-App-Login, nur Token persistiert.** Zugangsdaten werden nie gespeichert. Persistiert wird ausschließlich das Token (in der DB, übersteht Neustarts). Läuft das Token ab (Tractive-Tokens sind langlebig – Tage bis Wochen), gehen die Entitäten auf `unavailable` und die Tracker-Seite fordert einen erneuten Login an. Kein automatischer Re-Login (bewusste Sicherheitsentscheidung gegen at-rest-Zugangsdaten).

## Architektur

Cloud-Polling → Entity-State-Ebene (Flow-Trigger) → dazu eine eigene Frontend-Kartenseite und ein gebündelter REST-Endpoint für die Seite. Neues Backend-Paket `com.household.manager.tractive`, neuer `EntitySource.TRACTIVE`.

Das Muster folgt Nuki (`NukiPollingService`/`NukiProperties`/`NukiEntityMapper`) und Blink (In-App-Login, nur Token persistiert).

### Mehrere Tracker

Es werden **alle** Trackable Objects des Kontos gepollt und je Tracker gespiegelt (heute ein Hund, das Design trägt N). Keine kuratierte Geräteliste.

## Backend `com.household.manager.tractive`

- **`TractiveProperties`** (`@ConfigurationProperties(prefix="tractive")`)
  - `enabled` (Default true), `baseUrl` (`https://graph.tractive.com/4`), `clientId` (öffentliche App-Client-ID), `pollIntervalMs` (Default 60000), `initialDelayMs`, `httpTimeoutMs`.
  - `isConfigured()` = enabled (ein Token entscheidet zur Laufzeit, nicht die Config).
- **`TractiveApiClient`** – HTTP gegen die Tractive-API. Methoden: `login(email, password)`, `listTrackableObjects(token, userId)`, `posReport(token, trackerId)`, `hwReport(token, trackerId)`, `geofences(token, trackerId)`, optional `healthOverview(token, trackableObjectId)`. Java-`HttpClient` mit `HTTP_1_1` (konsistent mit den übrigen HTTP-Clients). Token als `Authorization: Bearer <token>` bzw. laut API-Konvention; `x-tractive-client` immer mitsenden.
- **`TractiveAuth`-Entity + `TractiveAuthRepository`** – Ein-Zeilen-Tabelle (Liquibase-Changeset). Felder: `id` (fix 1), `accessToken`, `expiresAt` (Instant), `userId`, `email`, `updatedAt`. **Kein Passwortfeld.**
- **`TractiveAuthService`** – kapselt Login und Token-Zustand.
  - `login(email, password)`: ruft `apiClient.login`, persistiert Token/expiresAt/userId/email.
  - `getValidToken()`: liefert `Optional<String>`; leer, wenn kein Token oder Restlaufzeit < 1 h.
  - `status()`: eingeloggt ja/nein, email, expiresAt.
  - `logout()`: löscht die Zeile.
  - Passwort niemals loggen/persistieren (`@ToString.Exclude` an DTOs).
- **`TractiveAuthController`**
  - `POST /v1/tractive/login` `{email, password}` → 200 mit Status oder 401 bei falschen Daten.
  - `GET /v1/tractive/status` → `{authenticated, email, expiresAt}`.
  - `POST /v1/tractive/logout`.
- **`TractivePollingService`** (`@Scheduled(fixedDelayString=…, initialDelayString=…)`) – wie `NukiPollingService`:
  - Nicht eingeloggt (`getValidToken()` leer) → skip, ggf. bestehende Entitäten `unavailable`.
  - Pro Tracker: pos_report + hw_report (+ Geofences + Aktivität) holen, über den Mapper zu `EntityStateUpdate`s, `entityStateService.reportState`.
  - Cloud-Fehler → `lastUpdates` auf `unavailable` (Polling bricht nie ab).
  - Live-Tracking wird **nicht** aktiviert (Akku-Schonung); es wird nur der letzte reguläre Positions-Report gelesen.
- **`TractiveController`** – `GET /v1/tractive/pets`: bündelt für die Kartenseite je Hund `{trackerId, name, latitude, longitude, accuracy, batteryPercent, charging, zone, activityMinutes, activityGoal, lastSeen}`.

## Entitäten pro Tracker (`TractiveEntityMapper`)

Entity-IDs über `EntityIds.build(...)` mit `EntitySource.TRACTIVE` und `sourceRef = trackerId`.

- **`sensor.tractive_<trackerId>_location`**
  - **State** = Name der Safe-Zone, in der sich der Hund befindet; sonst `away`; ohne Positionsdaten `unknown`.
  - **Attribute**: `latitude`, `longitude`, `accuracy`, `sensorUsed`, `positionTime`.
  - Dient zugleich als Karten-Quelle **und** Geofence-Flow-Trigger (z. B. „State wechselt auf `away` → Benachrichtigung").
- **`sensor.tractive_<trackerId>_battery`** – State = Akku %, `deviceClass: battery`.
- **`binary_sensor.tractive_<trackerId>_charging`** – `on` = lädt (nur wenn der hw_report den Ladezustand liefert).
- **`sensor.tractive_<trackerId>_activity`** – State = aktive Minuten heute, Attribute `dailyGoal` (nur wenn `health_overview` Daten liefert; sonst weggelassen).

### Geofence-Berechnung

Empfohlen und Standard: Safe-Zones aus Tractive laden (Kreis = Mittelpunkt lat/lon + Radius; ggf. Polygon) und die aktuelle Position dagegen prüfen (Haversine-Distanz < Radius → innerhalb). Nutzt die Zonen, die der Nutzer in der Tractive-App schon gepflegt hat.

Fallback, falls das Geofence-JSON unbrauchbar ist: eine konfigurierte Home-Koordinate + Radius in `TractiveProperties`. Ist die Zonenzugehörigkeit nicht bestimmbar, wird der State auf Basis dieser Home-Zone berechnet; fehlt auch die, bleibt `unknown` (State wird nicht geraten).

## Frontend `pages/pets/` („Hundetracker")

- Route + Navigationseintrag analog zu bestehenden Seiten.
- **Nicht eingeloggt** → Login-Formular (E-Mail/Passwort). Die Daten gehen nur ans Backend, das ausschließlich das Token behält.
- **Eingeloggt** → **Leaflet-Karte** (OSM-Tiles) mit Marker je Hund; Kacheln für Akku-%, Safe-Zone-Status, Aktivität. Bei abgelaufenem Token: „Bitte neu einloggen"-Hinweis.
- `services/tractive.service.ts` (Login/Status/Logout, `getPets()`).
- **Neue Dependency**: `leaflet` + `@types/leaflet`. ECharts-Geo scheidet aus (bräuchte GeoJSON, schlechter Fit für einen einzelnen Marker auf Straßenkarte).

## Konfiguration

- `application.properties`: `tractive.enabled`, `tractive.client-id`, Poll-Intervalle, Timeouts.
- Keine Zugangsdaten in der Config (In-App-Login).

## Fehlerbehandlung & Sicherheit

- Passwort niemals loggen oder persistieren; nur das Token in der DB.
- Cloud-Ausfall oder Token-Ablauf → Entitäten `unavailable`; Polling läuft weiter.
- Mapping-Fehler je Tracker werden gefangen (try/catch pro Tracker), damit ein defekter Datensatz den Poll-Zyklus nicht abbricht (Entity-State-Hook-Muster).

## Tests

- `TractiveEntityMapper`-Unit-Tests: Position→Zone-Mapping (innerhalb/außerhalb/keine Position), fehlende Felder, Akku/Charging-Mapping.
- `TractiveAuthService`-Tests: Token gültig / < 1 h Restlaufzeit / kein Token.
- Geofence-Distanzberechnung (Haversine) als isolierte, testbare Funktion.
- Reale Cloud-Verifikation (echter Account/Tracker) bleibt offen – wie bei Nuki/Blink dokumentiert.

## Bewusst nicht enthalten (YAGNI)

- Kein Live-Tracking-Schalter, kein Buzzer/LED-Steuerung (Tractive kann das, aber nicht Teil der Ziele).
- Kein historischer Positionsverlauf/Track auf der Karte (nur aktuelle Position).
- Kein automatischer Re-Login / keine at-rest-Zugangsdaten.
