# Blink-Kamera-Dashboard — Design (2026-08-27)

## Ziel

Eine Dashboard-Seite (Website + Wandtablet) für **alle** Blink-Kameras des Accounts mit
Anzeige (Standbild, Batterie, Scharf-Status, Clips) und Steuerung (Scharf/Unscharf,
Schnappschuss). Die Scharf-Zustände werden als Entitäten in den Entity-State-Layer
gespiegelt, damit Flows darauf triggern und Bedingungen prüfen können.

Die bestehende Gesichtserkennung (blink-vision-Sidecar, Türkamera) bleibt unberührt.

## Gewählter Ansatz

**Sidecar erweitern, Backend als Proxy + Poller.** Der blink-vision-Sidecar hält die
einzige Blink-Session (Login/2FA/Session-Persistenz sind dort gelöst); alle
blinkpy-Spezifika bleiben in `blink_client.py`. Ein eigener Java-Blink-Client wurde
verworfen (zweiter Login, Nachbau des brüchigen inoffiziellen Auth-Flows), ebenso
Sidecar-Push per Webhook (Blink pusht keine Scharf-Zustände — Backend-Polling gegen
den Sidecar ist einfacher und folgt dem Muster Nuki/Tractive).

## Sidecar-Erweiterung (blink-vision)

Neue FastAPI-Endpunkte (in `main.py`, bei Bedarf ausgelagert in ein `cameras.py`;
die blinkpy-Zugriffe selbst in `blink_client.py`):

- `GET /cameras` — alle Kameras: stabile `cameraId` (blinkpys `camera_id`, **nicht**
  der Name — Namen sind umbenennbar), Name, Typ (`doorbell`/…), `armed`
  (Bewegungserkennung), Batteriestatus, Sync-Modul-Name und dessen Scharf-Status.
  Nutzt blinkpys eingebauten Refresh-Throttle (Default 30 s)
- `POST /cameras/{cameraId}/arm` / `/disarm` — Bewegungserkennung der Einzelkamera
- `POST /system/{syncName}/arm` / `/disarm` — Sync-Modul scharf/unscharf
- `POST /cameras/{cameraId}/snapshot` — `snap_picture()`, wartet auf den Refresh und
  liefert das neue Standbild (Timeout ~15 s — Blink braucht Sekunden)
- `GET /cameras/{cameraId}/thumbnail` — aktuelles Standbild als JPEG-Bytes
- `GET /cameras/{cameraId}/clips` — Clip-Liste aus dem Local-Storage-Manifest
  (Id, Zeitstempel), neueste zuerst
- `GET /cameras/{cameraId}/clips/{clipId}` — Clip als MP4 (Download in ein
  Temp-Verzeichnis, dann ausliefern)

**Strikte Abgrenzung zum Erkennungs-Poller:** Der Dashboard-Lesepfad markiert Clips
**nicht** als verarbeitet — der Dedupe-Store des Pollers bleibt unberührt, sonst liefe
ein im Dashboard angesehener Türclip nie durch die Gesichtserkennung.

Der Sidecar bleibt ohne eigene Auth und nur im `app_net` — erreichbar ausschließlich
fürs Backend.

## Backend (Modul `blink/`)

Neues Modul `backend/src/main/java/com/household/manager/blink/` (Muster `nuki/`,
`tractive/`):

- **`BlinkSidecarClient`** — HTTP-Client gegen den Sidecar, **HTTP/1.1 erzwungen**
  (Javas Default HTTP_2 verliert Request-Bodys gegen Python/uvicorn-Sidecars)
- **`BlinkCameraService` + `BlinkController`** unter `/api/v1/blink`:
  - `GET /cameras`, `GET /cameras/{id}/thumbnail`, `GET /cameras/{id}/clips`,
    `GET /cameras/{id}/clips/{clipId}` (Medien werden durchgestreamt — das Frontend
    spricht nie direkt mit dem Sidecar)
  - `POST /cameras/{id}/snapshot`
  - `POST /cameras/{id}/arm|disarm`, `POST /system/{syncName}/arm|disarm`
- **`BlinkPollingService`** — `@Scheduled` alle 60 s, meldet Entitäten
  (`EntitySource.BLINK`, neuer Enum-Wert):
  - `binary_sensor.blink_<cameraId>_armed` je Kamera (Attribute: Name, Typ,
    Batterie, Sync-Modul)
  - `binary_sensor.blink_sync_<syncName>_armed` je Sync-Modul
  - Sidecar nicht erreichbar oder nicht eingeloggt → zuletzt gemeldete Entitäten
    `unavailable`; die Scheduled-Methode wirft nie. Nach jeder Schaltaktion
    sofortiges Nachpollen (Muster Nuki)
- **Security** (`SecurityConfig`, beide Richtungen in `SecurityRulesTest`):
  - Lesen: generische `GET /v1/**`-Regel → KIOSK, keine eigene Zeile
  - `POST /v1/blink/cameras/*/snapshot` → KIOSK-POST-Whitelist (ändert nichts am
    Systemzustand, Muster Speedtest/Tractive-Refresh)
  - Arm/Disarm → `anyRequest` → MEMBER, bewusst keine eigene Zeile
- **Audit:** `blink.camera.arm/disarm`, `blink.system.arm/disarm`; Schnappschuss
  bewusst ohne Audit (reiner Lesevorgang)
- **Fehlerabbildung:** Sidecar-Fehler → 502 mit Klartext; „nicht bei Blink
  angemeldet" → 400 mit Hinweis auf die Gesichtserkennungs-Seite (dort lebt der
  Login weiterhin). **Niemals 401** (der Auth-Interceptor würde die
  Haushalts-Session auswerfen)

Keine DB-Migration — kein neuer persistenter Zustand; Entitäten laufen über den
bestehenden Entity-State-Layer.

## Frontend

**Website-Seite „Kameras"** (`pages/cameras/`, Route `/cameras`, Navi „Smart Home"):

- Karte pro Kamera: Standbild, Name, Typ-Badge, Batteriestatus, Scharf-Schalter;
  Kopfzeile je Sync-Modul mit System-Scharf-Schalter
- „Schnappschuss"-Knopf je Kamera: Spinner während der Sekunden Wartezeit, danach
  Bildtausch
- Clip-Bereich je Kamera (auf-/zuklappbar): Liste mit Zeitstempel, Klick spielt den
  Clip im `<video>`-Element (Quelle = Backend-URL)
- Selbst-Refresh 60 s; nur der Erstabruf meldet Fehler, spätere behalten den letzten
  Stand
- Backend nicht bei Blink angemeldet (400) → Hinweis mit Link zur Seite
  „Gesichtserkennung"

**Tablet-Ansicht** (`/tablet/cameras`, `pages/tablet-cameras/`, sechster Eintrag in
`TABLET_VIEWS` — die Leiste scrollt seitwärts):

- `tablet-shell`, Kachelraster: Standbild + Name + Scharf-Status-Badge +
  Schnappschuss-Knopf; Clips ansehbar
- **Keine Arm/Disarm-Schalter im Tablet-Markup** — die Steuerung ist dort gar nicht
  vorhanden (nicht nur 403): ein Fremder vor dem Wandtablet soll den Weg nicht sehen

**Technische Punkte:**

- Thumbnails/Clips über `<img>`/`<video>` mit Backend-URL (Session-Cookie ist
  HttpOnly und wird mitgesendet); kein Base64-Umweg
- Cache-Buster (`?t=<lastUpdated>`) am Thumbnail, sonst zeigt der Browser nach einem
  Schnappschuss das alte Bild
- Kein neues Dashboard-Widget auf der Hauptseite

## Rollen (KIOSK/MEMBER)

- KIOSK (Wandtablet): ansehen (Status, Standbilder, Clips) + Schnappschuss auslösen
- MEMBER: zusätzlich Scharf/Unscharf (Kamera und System)

Begründung: analog Nuki (KIOSK darf nur verriegeln) — ein Fremder vor dem Wandtablet
darf die Kameras nicht unscharf schalten; ein Schnappschuss ist harmlos.

## Fehlerbehandlung

- Sidecar down/Timeout → 502 mit Klartext; Entitäten beim nächsten Poll
  `unavailable`; Frontend behält den letzten Stand
- Blink-Session abgelaufen → Sidecar meldet `loggedIn: false` → 400 mit Hinweis;
  Entitäten `unavailable`. Kein automatischer Re-Login (Zugangsdaten werden nie
  gespeichert — Bestandsentscheidung)
- Schnappschuss-Timeout → 502, altes Standbild bleibt
- Flow-Falle dokumentiert: ein Trigger auf `value: "unavailable"` kann nie feuern
  (engine-weit unterdrückte Richtung)

## Bewusste Grenzen v1

- **Kein Live-View** (proprietäres Streaming-Protokoll; Standbild + Clips reichen)
- **Kein Flow-Aktions-Node** `blink-arm` — Entitäten taugen für Trigger/Bedingungen
  (z. B. Warnung „Abwesend, aber Kameras unscharf" via `telegram-send`); ein
  Aktions-Node wäre ein eigener kleiner Folgeausbau
- Keine Clip-Historie in der eigenen DB — das Manifest des Sync-Moduls ist die
  Quelle; was Blink löscht, ist weg
- Keine Bewegungs-Push-Ereignisse — die Gesichtserkennung bleibt der einzige
  Ereignispfad
- Clip-Latenz 15–45 s (Abruf läuft trotz Local Storage über die Blink-Cloud)

## Tests

- Backend: Unit-Tests für Service/Poller mit gemocktem `BlinkSidecarClient`
  (inkl. `unavailable`-Pfad und Attribut-Erhalt beim `unavailable`-Markieren),
  `SecurityRulesTest` in beide Richtungen (KIOSK darf Snapshot, KIOSK darf nicht
  arm/disarm), Controller-Test für die Fehlerabbildung (400 vs. 502, nie 401)
- Frontend: Karma-Tests für beide Seiten (Erstabruf-Fehler vs. stiller
  Refresh-Fehler, Tablet-Markup ohne Schalter, Cache-Buster)
- Sidecar: Tests für die neue Kamera-/Manifest-Logik mit gefaktem blinkpy-Objekt;
  der echte Blink-Zugriff bleibt bis zum Realtest unverifiziert
