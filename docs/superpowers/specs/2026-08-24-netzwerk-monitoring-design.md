# Netzwerk-Monitoring + Tablet-Ansicht `/tablet/network` — Design

Datum: 2026-08-24
Status: vom Nutzer freigegeben

## Ziel

Eine weitere Tablet-Ansicht neben `/tablet/air-quality` etc., die den Zustand des Heimnetzwerks zeigt —
insbesondere Internet-Geschwindigkeit und Online-Status. Dahinter ein neues Backend-Modul, das die Daten
selbst misst (es gibt bisher keinerlei Netzwerk-Monitoring im System), plus Entitäten im Entity-State-Layer,
damit Flows darauf triggern können (z. B. Telegram nach einem Internet-Ausfall).

Geklärte Entscheidungen aus dem Brainstorming:

- Inhalte: Internet-Speed (Down/Up), Ping/Latenz + Online-Status, Router-Erreichbarkeit, LAN-Geräte-Status
- Router ist ein **TP-Link AX6000 VDSL2/G.fast** — kein offen dokumentiertes lokales API (kein TR-064 wie
  bei der FRITZ!Box, Weboberfläche proprietär/verschlüsselt). Router-Interna (Sync-Rate, Geräteliste) sind
  Reverse-Engineering mit hohem Bruchrisiko und **bewusst nicht Teil von v1**. „Router" heißt hier:
  Gateway-Erreichbarkeit.
- Speedtest: **Cloudflare, rein in Java** (kein Ookla-Binary, kein Sidecar), **stündlich**
- LAN-Geräteliste: **pflegbar in der DB über eine Admin-Seite** (Muster Tractive-Zuhause / Kalender-Kategorien)
- Integration: **Entitäten + Flow-fähig**; **nur Tablet-Ansicht**, keine Website-Seite (nachrüstbar)

## Backend-Modul `backend/src/main/java/com/household/manager/network/`

Drei unabhängige Messpfade; Fehler in einem Pfad dürfen die anderen nie stören. Alle Scheduled-Methoden
werfen nie (Muster der übrigen Poller).

### a) Internet-Status + Latenz — `NetworkConnectivityPollingService`, minütlich

- **Kein ICMP-Ping.** Im Docker-Bridge-Container fällt Javas `InetAddress.isReachable` ohne
  Raw-Socket-Rechte still auf TCP-Port 7 zurück und meldet dauerhaft „offline". Stattdessen
  **HTTP-Checks** gegen zwei unabhängige Ziele:
  - `https://1.1.1.1/cdn-cgi/trace` (Cloudflare, per IP — funktioniert auch bei kaputtem DNS)
  - `https://www.gstatic.com/generate_204` (Google)
  Die gemessene Antwortzeit des schnellsten erfolgreichen Checks ist die „Latenz" (ms).
- Internet gilt als **online, wenn mindestens ein Ziel antwortet** — ein einzelner CDN-Schluckauf ist
  kein Internetausfall.
- Zusätzlich **Gateway-Check**: TCP-Connect auf die Router-IP (Port 80, Fallback 443). Damit ist
  unterscheidbar „Router weg" (Gateway nicht erreichbar) vs. „Leitung weg" (Gateway ja, Internet nein).
  Die Gateway-IP steht in `application.properties` (`network.gateway-ip`, Default `192.168.1.1`) —
  sie ändert sich praktisch nie, anders als die Geräteliste.
- Ergebnis je Minute in Tabelle `network_connectivity_sample`: `sampled_at`, `online` (bool),
  `latency_ms` (nullable — offline hat keine Latenz), `gateway_reachable` (bool).
- **Aggregation/Retention** nach dem Muster `PowerHistoryAggregationJob`: Minuten-Samples nach 2 Tagen
  zu Stunden-Durchschnitten kompaktieren (Online-Anteil als Quote, Latenz als Durchschnitt), Löschung
  nach 30 Tagen. Nur **abgeschlossene** Buckets kompaktieren (`truncatedTo`-Regel von dort übernehmen).

### b) Speedtest — `NetworkSpeedtestService`, stündlich

- **Download:** mehrere Sekunden (Zeitbudget, kein festes Volumen) von
  `https://speed.cloudflare.com/__down?bytes=<groß>` lesen, verworfen, Bytes/Zeit → Mbit/s.
- **Upload:** Zufallsbytes gegen `https://speed.cloudflare.com/__up` schreiben, ebenfalls zeitbudgetiert.
- Reiner Java-`HttpClient`, **HTTP/1.1 erzwungen** (bekannte HTTP/2-Body-Falle, siehe Memory
  `java-httpclient-uvicorn-body`; hier zusätzlich: bei HTTP/2 wäre die Durchsatzmessung durch
  Flow-Control-Fenster verfälscht).
- Ergebnis in Tabelle `network_speedtest_result`: `tested_at`, `download_mbps`, `upload_mbps`
  (beide nullable), `success` (bool), `error_message` (nullable). Ein fehlgeschlagener Test wird als
  Fehlschlag **gespeichert** — sichtbare Lücke mit Grund — und wirft nie.
- **Kein Test, solange der Connectivity-Check „offline" meldet** (sinnlose Fehlversuche und
  Fehler-Rauschen vermeiden). Der Scheduler fragt dafür den letzten Connectivity-Zustand ab.
- Keine Aggregation nötig: 24 Zeilen/Tag, die Tabelle bleibt klein. Retention: Löschung nach 365 Tagen.
- **Manueller Test:** `POST /v1/network/speedtest` mit Mindestabstand **60 s** (Muster
  Tractive-`refreshNow`); bei Unterschreitung 429 mit Klartext. Der Endpunkt steht in der
  **KIOSK-POST-Whitelist** — er zieht nur Daten, schaltet nichts; sonst wäre der Knopf auf dem
  Wandtablet tot (dieselbe Begründung wie beim Tractive-Refresh).

### c) LAN-Geräte — `NetworkDevicePollingService`, minütlich

- Pflegbare Geräteliste in Tabelle `network_device`: `id`, `name`, `host` (IP oder Hostname),
  `tcp_port` (nullable), `sort_order`, `active` (bool).
- **Check:** TCP-Connect mit kurzem Timeout (~2 s) auf den angegebenen Port; ist keiner angegeben,
  werden gängige Ports der Reihe nach probiert (80, 443, 22, 1883, 8080, 8443) — der erste offene
  zählt als erreichbar. Kein ICMP (siehe oben).
- Ergebnis **nur im Speicher** (Muster `ZigbeeStreamMonitor`): aktueller Status je Gerät +
  `lastSeenAt`. Keine Historie je Gerät in v1 — bewusste Grenze.
- Nach einem Backend-Neustart ist `lastSeenAt` leer, bis der erste Poll gelaufen ist; die Kachel
  zeigt dann „–" statt eines geratenen Werts.
- **Admin-Seite „Netzwerk-Geräte"** (`admin/network-devices`, Muster Kalender-Kategorien): CRUD,
  ADMIN-only, Audit-Einträge (`network.device.create/update/delete`). Deaktivierte Geräte werden
  nicht gepollt und auf dem Tablet nicht gezeigt.

## Entitäten (`EntitySource.NETWORK`)

Gemeldet über den Entity-State-Layer (Hook-Muster mit try/catch um das Mapping):

- `binary_sensor.network_internet` — `on` = online; `deviceClass: connectivity`;
  Attribute `latencyMs`, `gatewayReachable`. Gemeldet vom Connectivity-Poller, minütlich.
- `sensor.network_latency_ms` — aktuelle Latenz; bei offline **kein Update** (kein erfundener Wert).
- `sensor.network_download_mbps` / `sensor.network_upload_mbps` — nach jedem **erfolgreichen**
  Speedtest; ein Fehlschlag meldet kein Update (letzter guter Wert bleibt stehen).

Festgehaltene Konsequenzen für Flows:

- Ein Flow „Telegram bei Internet-Ausfall" (`binary_sensor.network_internet` → `off`) kann die
  Nachricht naturgemäß erst **nach Rückkehr** des Internets zustellen — Telegram ist bei Ausfall
  selbst nicht erreichbar. Das ist kein Fehler, sondern Physik; die Erholungsflanke (`on`) feuert
  normal und eignet sich für „Internet war weg von–bis".
- Bekannte Falle gilt: **kein Trigger auf `value: "unavailable"`** (tote-Trigger-Falle).
- Diese Entitäten werden **nie `unavailable`** — die Messung ist lokal; „Internet weg" ist der
  Zustand `off`, kein Ausfall der Quelle (Muster Pet-Food-Sensor).

## API (`NetworkController`, `/api/v1/network`)

- `GET /v1/network/status` — alles für die Tablet-Seite in **einem** Abruf: `online`, `latencyMs`,
  `gatewayReachable`, `lastCheckedAt`, letzter Speedtest (Zeit, down/up, success), Geräteliste
  (nur aktive: Name, Status, `lastSeenAt`).
- `GET /v1/network/history?range=DAY|WEEK|MONTH` — Latenz-/Online-Serie (aus
  `network_connectivity_sample`, gedownsampled über `SeriesRange`/`SeriesDownsampler`) + die
  Speedtest-Punkte des Fensters. Offline-Fenster erscheinen in der Latenz-Serie als `null`-Punkte
  (Lücke im Graph, `connectNulls: false`).
- `POST /v1/network/speedtest` — manueller Test, KIOSK-Whitelist, 60-s-Sperre (429).
- `GET /v1/network/devices` + `POST`/`PUT /{id}`/`DELETE /{id}` — Verwaltung.

Security (Autorität: `SecurityConfig.filterChain`, Reihenfolge beachten):

- Lesen über die generische `GET /v1/**`-Regel → KIOSK (keine eigene Zeile).
- `POST /v1/network/speedtest` → explizite KIOSK-POST-Whitelist-Zeile.
- Schreibzugriffe auf `/v1/network/devices` → **ADMIN** (eigene Zeile **vor** den generischen Regeln,
  methodenspezifisch, damit das Lesen fürs Tablet frei bleibt — Muster Kalender-Kategorien).
- `SecurityRulesTest` hält alle Richtungen fest.

## Liquibase

Ein Changelog `20260824-00xx-network-monitoring.xml` mit getrennten Changesets je Tabelle
(MariaDB-DDL-Implizit-Commit-Regel aus `20260727-0044` beachten):
`network_connectivity_sample` (Index auf `sampled_at`), `network_speedtest_result` (Index auf
`tested_at`), `network_device`.

## Tablet-Ansicht `/tablet/network`

`pages/tablet-network/` in `<app-tablet-shell heading="Netzwerk">`, Eintrag in `TABLET_VIEWS`
(`shared/tablet-views.ts`) — damit erscheint sie automatisch in der Ansichtsleiste des Dashboards
und der Shell. Vier Kacheln (2×2):

1. **Status:** groß Online/Offline (Farbe: grün/rot), aktuelle Latenz, Gateway-Status,
   letzter Speedtest (Zeit, Down/Up) — plus Knopf „Jetzt testen" (ruft `POST /speedtest`,
   zeigt die 429-Meldung bei zu schnellem Doppelklick).
2. **Speed-Verlauf:** Down- und Up-Punkte der Speedtests über den gewählten Zeitraum (ECharts,
   zwei Serien, Einheit Mbit/s). Fehlgeschlagene Tests sind Lücken.
3. **Latenz-Verlauf:** Liniengraph; Offline-Fenster als Lücke (`connectNulls: false`).
4. **Geräte:** Liste der aktiven LAN-Geräte mit grünem/rotem Punkt und „zuletzt gesehen".

Verhaltensmuster von den Schwesteransichten übernommen:

- Zeitraumwahl (24 h / 7 Tage / 30 Tage, Default 7 Tage) im `[shellActions]`-Slot.
- Selbst-Refresh alle **60 s** (Status ist schnelllebiger als Temperaturen); nur der **Erstabruf**
  meldet einen Fehler, spätere Fehler behalten stumm die letzten Werte.
- **Zeitraumwechsel bestellt den laufenden Abruf ab** (`pendingRequest`-Muster aus
  `tablet-consumption`).
- **Höhenketten-Test** (eigener Test-Container statt `host.parentElement`, Karma-Falle!) — Messpunkte
  900/1200 px, weil die Kacheln festen Kopf-/Fußinhalt tragen (wie Toni/Verbrauch).
- Markup/Styles der Seite in der eigenen Komponente; die Shell liefert Kopfzeile und Leiste.

## Bewusste Grenzen (v1)

- Keine Router-Interna (TP-Link ohne offenes API) — Router = Gateway-Erreichbarkeit.
- Keine Historie/Ausfallstatistik je LAN-Gerät; Gerätestatus geht bei Neustart verloren.
- Speedtest misst die Anbindung des **Servers** (LAN-verkabelt), nicht die WLAN-Erfahrung eines
  Endgeräts — gedacht als „Leitung ok/lahm", kein Ookla-Ersatz. Genauigkeit hängt am Zeitbudget;
  bei sehr schnellen Leitungen (>500 Mbit/s) limitiert ggf. die Container-CPU.
- Stündliche Tests kosten Traffic (~5–10 GB/Tag je nach Budget) — bewusst akzeptiert.
- Keine Website-Seite; nachrüstbar (Service/API sind seitenneutral).

## Tests

- Backend: Unit-Tests für Speedtest-Berechnung (Bytes/Zeit → Mbit/s, Fehlschlag-Pfad),
  Connectivity-Bewertung (ein Ziel reicht; Gateway getrennt), Device-Check (Port-Fallback),
  Aggregations-Job (nur abgeschlossene Buckets), Controller-Tests, `SecurityRulesTest`-Erweiterung.
  HTTP-Aufrufe hinter Interfaces kapseln, damit Tests ohne Netz laufen (lokale DB-Tests schlagen
  by design fehl — bekannte Baseline).
- Frontend: Komponententest der Tablet-Seite inkl. Höhenketten-Test (900/1200 px, eigener Container),
  Abbruch-Test mit `Subject` (nicht `of(...)`, verdeckt den Aufruf), Fehlerverhalten Erst-/Folgeabruf.
