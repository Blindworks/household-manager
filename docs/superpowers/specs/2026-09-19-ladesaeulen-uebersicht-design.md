# Ladesäulen-Übersicht (Tesla) — Tablet-Ansicht `/tablet/charging`, Website `/charging` — Design

Datum: 2026-09-19
Status: vom Nutzer freigegeben

## Ziel

Eine siebte Tablet-Ansicht, die öffentliche Ladesäulen rund ums Zuhause auf einer Karte zeigt: Leistung,
ob belegt, und **wie lange schon belegt** — damit sich abschätzen lässt, ob eine Säule bald frei wird.
Dazu eine Website-Seite mit derselben Karte, auf der Favoriten gepflegt werden, und Entitäten je Favorit,
damit Flows („Lidl ist frei geworden" per Telegram) ohne Redeploy möglich sind.

Geklärte Entscheidungen aus dem Brainstorming:

- Inhalt: **Umkreis ums Zuhause auf der Karte, Favoriten hervorgehoben mit Belegungsdauer** (Variante C)
- Datenquelle: **inoffizielles EnBW-mobility+-Backend** (Roaming, kennt Lidl/Kaufland/Aldi/Ionity usw.
  mit Live-Status je Ladepunkt). Keine offizielle Quelle mit Live-Belegung existiert; Tesla Fleet API
  (nur Supercharger, hoher Rollout-Aufwand), Vattenfall/Chargecloud (undokumentiert bzw. nur eigene
  Betreiber) wurden verworfen
- Radius, Mindestleistung, Zuhause und Favoriten **in der DB pflegbar** (Admin-Seite), nicht in Properties
- Favorisieren **auf der Website-Seite `/charging`** am Listeneintrag/Marker-Popup (MEMBER)
- Tablet-Layout **A**: Karte links, Liste rechts (Favoriten zuerst mit Ladepunkten und Dauer, dann die
  übrigen nach Entfernung)
- **Entitäten je Favorit** im Entity-State-Layer (Flow-fähig)

## Datenquelle: EnBW-Backend

**Verifiziert am 2026-09-19** gegen das echte Backend; die aufgezeichneten Antworten liegen als Fixtures
in `backend/src/test/resources/charging/` (`enbw-area.json`, `enbw-area-grouped.json`,
`enbw-station.json`), aufgenommen mit `scripts/probe-enbw-charging.sh`. Was der ursprüngliche Entwurf
aus den Home-Assistant-Integrationen annahm und was sich real zeigte:

- **Host:** `https://api.emp.emob-enbw.com/emobility-public-api/api/v1` — der Azure-Host der
  HA-Integrationen (`enbw-emp.azure-api.net`) antwortet 404
- **Key:** `Ocp-Apim-Subscription-Key` steht öffentlich im Quelltext der Kartenseite
  `https://www.enbw.com/elektromobilitaet/produkte/mobilityplus-app/ladestation-finden/map`
  (`initMap({ … apimSubscriptionKey })`), zusammen mit `Origin`/`Referer` `https://www.enbw.com`
- `GET /chargestations?fromLat&toLat&fromLon&toLon&grouping=false&groupingDivisor=15[&minPower=<kW>]`:
  **der Server gruppiert selbst** — in großen Kästen kommen Einträge mit `grouped: true`,
  `stationId: null` und einem `viewPort`; erst Kästen um 0,01° liefern zuverlässig Einzelstationen.
  Der Client bohrt gruppierte Einträge über ihren `viewPort` rekursiv nach (Tiefe ≤ 6, Hard-Cap 150
  Requests je Umkreis-Poll, Dedup über `stationId`). **`minPower` filtert serverseitig** (gemessen für
  einen dichten 10-km-Kasten: 23 Requests mit `minPower=50`, 131 ohne)
- Einzelstation: `stationId` (Integer), `operator` (`"*"` = unbekannt), `shortAddress` (String
  „Straße, PLZ Ort, DE"), `lat`, `lon`, `maxPowerInKw`, `numberOfChargePoints`,
  `availableChargePoints`. **Es gibt keinen Stationsnamen** — `name` wird aus dem Straßenteil von
  `shortAddress` gebildet, sonst Betreiber, sonst Id
- `GET /chargestations/{stationId}` — `chargePoints[]` mit `evseId`, `status` (`AVAILABLE`, `OCCUPIED`,
  …), **`state.updatedAt`** (Epoch-ms des letzten Statuswechsels — der echte Belegungsbeginn) und
  `connectors[] {plugTypeName, maxPowerInKw}`

### `ChargingStationSource` (Interface) / `EnbwChargingClient`

- Zwei Methoden: `searchArea(BoundingBox, minPowerKw)` → `List<ChargingStation>`;
  `stationDetails(stationId)` → `ChargingStationDetails` (Ladepunkte mit `ChargePointStatus`
  FREE/OCCUPIED/OUT_OF_SERVICE/UNKNOWN). Ein unbekannter Statustext wird **fail-safe** `UNKNOWN`, nie
  `FREE`
- Alle EnBW-Spezifika (URLs, Header, Feldnamen) leben ausschließlich im Client (Muster `WebPushClient`,
  `blink_client.py`) — die Quelle ist damit austauschbar
- Java-`HttpClient`, **HTTP/1.1 erzwungen**, Timeout 10 s, Jackson mit `ignoreUnknown`
- Konfiguration: `charging.enabled`, `charging.enbw.base-url`, `charging.enbw.api-key` (Default = der
  öffentliche Key, per Env `CHARGING_ENBW_API_KEY` überschreibbar), `charging.area-poll-seconds` (300),
  `charging.favorite-poll-seconds` (60)
- Fehler: `ChargingSourceException` (Unterklasse `ChargingRateLimitException` bei 429). **Nie eine
  Ausnahme, die zu 401 wird** — der Auth-Interceptor des Frontends würde den Nutzer sonst aus der
  Haushalts-Session werfen (Falle aus Tractive/Blink)

## Backend-Modul `backend/src/main/java/com/household/manager/charging/`

### Einstellungen — `ChargingSettingsService`

- `application_settings`, Kategorie `CHARGING`: `home_lat`, `home_lon`, `radius_km` (Default 10,
  erlaubt 1–50), `min_power_kw` (Default 50, erlaubt 0–400). Defensives Lesen wirft nie; unlesbare
  Werte fallen auf den Default zurück und werden geloggt (Muster `TractiveHomeSettingsService`)
- **Eigene Zuhause-Koordinaten**, nicht die Tractive-Home-Definition: sonst verschöbe ein Ändern des
  Hunde-Zuhauses still die Ladekarte. Die Admin-Seite bietet „Aus Hundetracker-Zuhause übernehmen" als
  einmalige Vorbelegung (liest `TractiveHomeSettingsService`)
- Validierung an der API-Grenze mit `Double.isFinite` (NaN-Falle aus Tractive)
- Ohne Koordinaten: kein Poll, `GET /stations` antwortet `configured: false`

### Polling — `ChargingPollingService`

Zwei getrennte `@Scheduled`-Pfade, beide werfen nie, Fehler in einem stört den anderen nicht:

- **Umkreis alle 300 s**: eine Bounding Box aus Zuhause + Radius, plus Drill-down der gruppierten
  Einträge (siehe Datenquelle; `minPower` serverseitig). Die API kennt nur
  Rechtecke — serverseitig wird per Haversine auf den Kreis geschnitten und unter der Mindestleistung
  aussortiert (`ChargingAreaFilter`, reine Funktion, getestet). Ergebnis nur im Speicher
  (`ChargingSnapshot` mit `lastPolledAt`), kein DB-Schreiben. Die Intervalle stehen in Sekunden und werden
  per SpEL in Millisekunden umgerechnet (`ChargingPollingScheduleTest`). Favoriten außerhalb des Kreises bleiben
  trotzdem in der Liste (sie werden über den Detailpfad versorgt)
- **Favoriten alle 60 s**: je Favorit ein Detail-Request; Fehler je Favorit isoliert (Muster
  `TractivePollingService.collectPet`). Bei `ChargingRateLimitException` bricht der Durchlauf sofort ab
- Bei einem Fehler bleibt der letzte Snapshot **erhalten** (Frontend zeigt „Stand von HH:MM"); die
  Favoriten-Entitäten werden `unavailable` **mit erhaltenen Attributen des letzten Erfolgs** (auch bei einem
  späteren Fehlschlag; ein Favorit, dessen erster Abruf je scheitert, entsteht direkt in `unavailable`) (`EntityStateWriter.upsert`
  überschreibt Attribute sonst komplett, Muster Zigbee-Watchdog/Blink)
- Manueller Abruf `refreshNow()`: beide Pfade synchron, Mindestabstand 15 s (429 sonst), Fehler
  werden an den Aufrufer durchgereicht (400 „nicht konfiguriert", 502 Quelle, 429 Rate-Limit)

### Belegungsdauer — `ChargingOccupancyTracker`

- Tabelle `charging_point_occupancy`: `chargepoint_id` (PK, String), `station_id`, `occupied_since`
  (DATETIME, nullbar), `first_seen_occupied_at` (DATETIME, NOT NULL)
- **Belegungsbeginn kommt primär aus `state.updatedAt` der Quelle** (`ChargePoint.statusSince`); eine Zeile ohne
  Beginn wird damit nachgefüllt. Die folgenden Regeln sind der Fallback für Quellen ohne Zeitstempel
- Übergang **frei → belegt**: Zeile mit `occupied_since = jetzt` (und `first_seen_occupied_at = jetzt`).
  **belegt → frei** (oder außer Betrieb): Zeile löschen
- Ladepunkt beim ersten Poll (oder nach Neustart ohne Zeile) **schon belegt**: Zeile mit
  `occupied_since = NULL`, nur `first_seen_occupied_at` — die Anzeige sagt dann „seit mind. X min".
  Ein geratener Beginn wäre genau die falsche Aussage für „der fährt bald weg"
- `UNKNOWN` ändert nichts an einer bestehenden Zeile (ein kurzer Statusaussetzer darf die Dauer nicht
  auf null setzen)
- Nur für Favoriten (nur dort gibt es Ladepunkte einzeln); Umkreis-Standorte haben ausschließlich Zähler.
  Kein Aufräumjob: die Tabelle hat höchstens so viele Zeilen wie Ladepunkte an Favoriten
- Alle Zeitstempel als `Instant` im Code, `LocalDateTime` (Haushaltszeit, `Clock`-Bean) im DTO

### Favoriten — `ChargingFavoriteService`

- Tabelle `charging_favorite`: `station_id` (UNIQUE, String), `display_name`, `operator`, `lat`,
  `lon`, `created_at`. Name/Betreiber/Koordinaten werden beim Favorisieren aus dem aktuellen Snapshot
  übernommen, damit die Liste auch bei Quellenausfall lesbar bleibt; ein Standort, der nicht im Snapshot
  ist, lässt sich nicht favorisieren (400)
- Entfernen löscht die Belegungszeilen der Station mit (kein FK zwischen den Tabellen, deshalb explizit)
  und markiert die Entität `unavailable` (sonst bliebe sie für immer auf dem letzten Wert stehen — Falle
  aus der Anwesenheitserkennung)
- Audit `charging.favorite.add`/`charging.favorite.remove` mit `stationId` und Name

### Entitäten — `ChargingEntityMapper`, `EntitySource.CHARGING`

- Je Favorit `sensor.charging_<stationId>_free`, Id über `EntityIds.build` (nie eigene
  Slug-Implementierung). State = Anzahl freier Ladepunkte als Zahl-String; Attribute `total`,
  `maxPowerKw`, `stationName`, `operator`, `occupiedSince` (ältester belegter Ladepunkt mit bekanntem
  Beginn, sonst fehlt der Schlüssel)
- **Bewusst kein `deviceClass`**: die beiden Auswertungen im Projekt (`door` im Modus-Check, `power` im
  Verbraucher-Service) passen beide nicht und würden falsche Kacheln/Warnungen erzeugen
- Kein Trigger auf `value: "unavailable"` (tote-Trigger-Falle); Flow-Beispiel: `changed` von `0` auf
  `≥ 1` → `telegram-send`, gern hinter `rate-limit`

## API `/api/v1/charging`

- `GET /stations` — ein Abruf für alles: `configured`, `home {lat, lon}`, `radiusKm`, `minPowerKw`,
  `lastPolledAt` (null ohne erfolgreichen Poll → „Noch keine Daten"), `stations[]` mit `stationId`,
  `name`, `operator`, `address`, `lat`, `lon`, `distanceMeters`, `maxPowerKw`, `total`, `free`,
  `favorite`; für Favoriten zusätzlich `chargePoints[]` mit `status`, `maxPowerKw`, `connector`,
  `occupiedSince` (LocalDateTime, nullbar), `minimumDuration` (true = nur „seit mind." belegbar).
  Sortierung: Favoriten zuerst, dann nach Entfernung
- `POST /refresh` — erzwingter Abruf, liefert den frischen Stand; 429 bei Mindestabstand
- `PUT /favorites/{stationId}`, `DELETE /favorites/{stationId}` — MEMBER
- `GET /settings`, `PUT /settings` — ADMIN, Audit `charging.settings.update`

### Sicherheit

- `/v1/charging/settings` als **methodenloser ADMIN-Matcher vor** der generischen `GET /v1/**`-Regel
  (sonst dürfte das Wandtablet die Einstellungen lesen)
- `POST /v1/charging/refresh` in die **KIOSK-POST-Whitelist** (zieht nur Daten; sonst wäre der Knopf am
  Tablet tot — Muster Tractive-Refresh, Speedtest)
- Favoriten-Schreibzugriffe über `anyRequest` → MEMBER, bewusst keine eigene Zeile
- `SecurityRulesTest`: je Regel Positiv- und Verbotstest (KIOSK liest `/stations`, KIOSK darf
  `/refresh`, KIOSK 403 auf Favoriten und Settings, MEMBER 403 auf Settings, ADMIN 200)
- `TooManyRequestsException` und `ChargingSourceException` → 429 / 502 im `GlobalExceptionHandler`

## Frontend

Geteilt werden nur `services/charging.service.ts`, `models/charging.model.ts` und
`shared/charging-status.util.ts` (Markerfarbe je Status: grün ≥ 1 frei, rot 0 frei, grau außer
Betrieb/unbekannt; Dauerformat „seit 38 min" / „seit mind. 38 min" / „seit 2 h 05 min"; Sortierung).
**Tablet- und Website-Komponente teilen bewusst keinen Komponenten-Code** (Muster Kameras,
Temperaturen — beide sollen sich unabhängig entwickeln können).

### Tablet `/tablet/charging` — `pages/tablet-charging/`

- Siebter Eintrag in `TABLET_VIEWS`: `{ route: '/tablet/charging', icon: 'ev_station', label: 'Laden' }`
  (die Leiste scrollt seitwärts, kein Umbruch — bereits so gebaut)
- In `app-tablet-shell`; Layout A: Karte links (~60 %), rechts Liste. Favoriten oben als Karten mit einer
  Zeile je Ladepunkt (Leistung, Status, Dauer), darunter die übrigen Standorte kompakt („2/4 frei ·
  150 kW · 1,2 km"). Kopfzeile rechts im `[shellActions]`-Slot: „Stand von HH:MM" und „Jetzt
  aktualisieren" (429-Meldung inline)
- Selbst-Refresh 60 s; nur der Erstabruf meldet einen Fehler, spätere behalten den letzten Stand.
  `pendingRequest`-Abbruch vor jedem neuen Abruf (Muster Verbrauch/Netzwerk)
- **Dauer läuft zwischen zwei Abrufen weiter**: minütlicher Timer zeichnet die Dauer aus `occupiedSince`
  gegen die Browser-Uhr neu
- **Karte:** Leaflet, OSM-Kacheln, Marker-Icons lokal (`shared/leaflet-icons.util.ts`). Marker als
  `L.divIcon` (farbiger Kreis mit Zahl der freien Ladepunkte, Stern bei Favoriten), Zuhause als blauer
  Punkt, Radius als `L.circle`. Der Kartencontainer steht **immer im DOM**, `ViewChild` +
  `ngAfterViewInit` (Lehre aus `/pets`). Ausschnitt einmalig auf Zuhause + Radius; ein Refresh setzt
  Zoom/Position **nicht** zurück, nur die Marker werden ausgetauscht. Antippen eines Markers hebt den
  Listeneintrag hervor und scrollt ihn ins Bild
- Höhenketten-Test (Karte und Liste füllen die Shell), Messpunkte 900/1200 px wie Toni/Verbrauch
- Nicht konfiguriert: Hinweis „Zuhause unter Admin → Ladesäulen festlegen" statt Karte

### Website `/charging` — `pages/charging/`

- Navi unter „Smart Home", Titel „Ladesäulen", `authGuard`. Gleiche Karte und Liste, zusätzlich ein
  Stern-Knopf am Listeneintrag und im Marker-Popup (`PUT`/`DELETE /favorites/{id}`); nach der Antwort wird
  der Stand frisch geladen (kein lokales Umschalten — der Favorit bringt neue Ladepunkt-Daten mit)
- Refresh 60 s wie am Tablet

### Admin `admin/charging` — `pages/admin-charging/`

- `adminGuard`, Überschrift „Ladesäulen". Zuhause per Leaflet-Klick (Muster `admin/tractive`), Radius,
  Mindestleistung, Knopf „Aus Hundetracker-Zuhause übernehmen", Favoritenliste mit Entfernen. Hinweisbanner,
  solange kein Zuhause gesetzt ist. **Wurzelklasse in `$admin-scopes` (`styles.scss`) eintragen**, sonst
  stehen die Knöpfe unstilisiert da
- Schranken 1–50 km / 0–400 kW stehen **zweimal** (Backend verbindlich, Frontend als Bedienhilfe am
  Feld) — wer eine ändert, zieht die andere nach

## Tests

- Client: Parser gegen die im Realtest aufgezeichnete Fixture; unbekannter Status → `UNKNOWN`; HTTP/1.1
  (Regressionstest wie bei den Sidecar-Clients)
- `ChargingAreaFilter`: Kreis-Schnitt, Mindestleistung, Favoriten außerhalb bleiben
- `ChargingOccupancyTracker`: frei→belegt setzt Beginn; belegt→frei löscht; Erstsichtung belegt setzt nur
  `first_seen`; `UNKNOWN` lässt die Zeile unberührt
- Polling: Fehler je Favorit isoliert; Rate-Limit bricht ab; Ausfall → `unavailable` mit Attributen;
  Snapshot bleibt erhalten
- Settings: Defaults bei unlesbaren Werten, `NaN` abgelehnt, Bereichsgrenzen
- `SecurityRulesTest` wie oben; Controller-Tests für 400 „nicht konfiguriert" und 429
- Frontend: Util (Farbe, Dauerformat inkl. „mind.", Sortierung), Tablet-Höhenkette, Marker-Austausch
  ohne Ausschnittsreset, Website-Favorisieren löst Neuladen aus

## Bewusste Grenzen v1

- **Inoffizielle Quelle:** ein Key- oder Formatwechsel bei EnBW legt die Ansicht lahm, sichtbar nur am
  alternden „Stand von" und an `unavailable`-Entitäten. Der Key ist per Env nachziehbar, ein
  Formatwechsel braucht Code; `scripts/probe-enbw-charging.sh` ist der erste Schritt der Diagnose
- Mindestleistung 0 in dichter Gegend läuft ins 150-Request-Cap und liefert eine unvollständige Liste
- Keine Preise, keine Ladehistorie, keine Belegungsstatistik, kein Routing, kein Supercharger-Sonderstatus
- Ein Zuhause; Belegungsdauer nur für Favoriten; Übergänge während eines Backend-Neustarts werden nicht
  gesehen (die Dauer selbst überlebt den Neustart über die DB)
- Die OSM-Kacheln kommen aus dem Internet — ohne Verbindung bleibt die Karte grau, die Liste läuft weiter
- Poll-Last: ein Umkreis-Request alle 5 min plus ein Detail-Request je Favorit pro Minute. Bei mehr als
  etwa zehn Favoriten sollte `charging.favorite-poll-seconds` hochgesetzt werden
