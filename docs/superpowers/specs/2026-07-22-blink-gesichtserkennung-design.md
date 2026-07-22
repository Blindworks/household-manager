# Design: Gesichtserkennung an der Blink-Türkamera mit Nuki-Auto-Unlock

**Datum:** 2026-07-22
**Status:** Entwurf genehmigt

## Ziel

Die vorhandene Blink-Türkamera (mit integrierter Klingel, Sync Module 2 + USB-Speicher,
kein Blink-Abo) so in den Household-Manager integrieren, dass:

1. **Gesichtserkennung** – Bewegungs-Clips werden automatisch analysiert; registrierte
   Bewohner werden erkannt und als Ereignis im System gemeldet.
2. **Personenverwaltung** – Bewohner mit Referenzfotos über das Frontend pflegen,
   Erkennungshistorie mit Konfidenz und Thumbnail einsehen.
3. **Nuki-Auto-Unlock (Schritt 2)** – Ein Flow öffnet bei erkannter Bewohnerin/erkanntem
   Bewohner automatisch die Haustür (`unlatch` über die bestehende Nuki-Integration).

## Bewusst akzeptierte Risiken und Grenzen

- **Foto-Spoofing:** Eine 2D-Kamera kann keine Lebenderkennung leisten. Ein vorgehaltenes
  Foto/Display einer registrierten Person würde als diese Person erkannt. Der Nutzer hat
  sich nach Aufklärung bewusst für vollautomatisches Öffnen entschieden. Milderung:
  hohe Konfidenz-Schwelle, Cooldown, Flow startet deaktiviert und wird erst nach
  Bewährung der Erkennung in der Historie scharfgeschaltet.
- **Latenz:** Bewegung → Clip in der Cloud abrufbar → Erkennung → Tür auf dauert
  realistisch **15–45 s** (akzeptiert). Der Clip-Abruf läuft auch bei USB-Speicherung
  über die Blink-Cloud (das Sync-Modul lädt Clips auf Anfrage hoch).
- **Inoffizielle API:** Blink hat keine offizielle API; wir nutzen `blinkpy`
  (Amazon-Login + 2FA). Gleiche Brüchigkeitsklasse wie die Alexa-Integration –
  der gesamte Blink-spezifische Teil wird deshalb im Sidecar isoliert.

## Entscheidung: eigener Sidecar statt CompreFace/Frigate

Gewählt: **Ansatz A – ein Python-Sidecar `blink-vision/`** (blinkpy + InsightFace).

- CompreFace (Ansatz B) verworfen: eigener Mehrcontainer-Stack mit Postgres, zu schwer
  für die Ziel-Hardware (NAS/Mini-PC-Klasse), Personenpflege außerhalb unseres Frontends.
- Frigate/Double-Take (Ansatz C) verworfen: benötigt RTSP, das Blink nicht anbietet.

## Sidecar `blink-vision/` (neu, Python 3.12 + FastAPI)

Aufbau analog zum Alexa-Sidecar (`alexa-sidecar/`): der brüchige, herstellerspezifische
Teil lebt vollständig im Sidecar, das Backend spricht nur eine stabile HTTP-Schnittstelle.

| Baustein | Verantwortung |
|---|---|
| Auth-Modul | blinkpy-Login als In-App-Flow (E-Mail/Passwort + 2FA-PIN, vom Frontend über das Backend durchgereicht). Persistiert wird nur das Token/Session-JSON in einem Volume, nie Zugangsdaten. |
| Poll-Loop | Alle ~10 s (konfigurierbar) neue Bewegungs-Clips der konfigurierten Türkamera abrufen (Local-Storage-Manifest), neue Clips herunterladen. Bereits verarbeitete Clips werden anhand ihrer Clip-ID/Zeitstempel übersprungen. |
| Analyse | OpenCV: bis zu N Frames pro Clip extrahieren (z. B. jeder 5. Frame, max. 12). InsightFace (CPU, ONNX-Runtime, Modell `buffalo_s`): Embeddings berechnen, Cosine-Ähnlichkeit gegen registrierte Personen-Embeddings, bestes Match je Person über alle Frames. |
| Meldung | `POST` an den Backend-Webhook: erkannte Personen mit Konfidenz, Anzahl unbekannter Gesichter, Clip-Zeitstempel, Thumbnail (JPEG, bester Frame). |
| Cooldown | Dieselbe Person wird höchstens einmal pro 2 Minuten gemeldet (verhindert Mehrfach-Öffnen und Event-Spam). |
| Heartbeat | Regelmäßiger Status-Ping ans Backend; bleibt er aus, wird die Event-Entität `unavailable`. |

HTTP-Endpoints des Sidecars (nur vom Backend aufgerufen):

- `GET /health`, `GET /status` (Login-Zustand, Kamera gefunden, letzter Poll)
- `POST /auth/login`, `POST /auth/verify` (2FA-PIN) – analog zum Alexa-Login-Flow
- `POST /embeddings` – Referenzfoto rein, Embedding raus (wird beim Foto-Upload
  vom Backend aufgerufen)
- `PUT /persons` – vollständige Liste der Personen-Embeddings (Backend pusht bei
  jeder Änderung; zusätzlich holt der Sidecar sie beim Start über
  `GET /v1/vision/embeddings` vom Backend ab)

Der Sidecar ist **zustandslos** bezüglich Personen: führend sind die Daten im Backend.

Konfiguration über Umgebungsvariablen: Backend-URL, Kamera-Name/-ID, Poll-Intervall,
Konfidenz-Schwelle (Default 0.5 Cosine-Ähnlichkeit, bewusst streng), Cooldown.

## Backend: neues Package `vision/`

| Klasse | Verantwortung |
|---|---|
| `VisionProperties` | `vision.enabled`, `vision.sidecar-base-url`, Timeouts |
| `VisionSidecarClient` | Dünner HTTP-Client zum Sidecar (Status, Login-Flow, Embedding-Berechnung, Personen-Push). DTOs mit `@JsonIgnoreProperties(ignoreUnknown = true)`. |
| `VisionPersonService` | Personen-CRUD, Foto-Upload (Foto → Sidecar-Embedding → beides speichern), Push der Embeddings an den Sidecar bei jeder Änderung |
| `VisionRecognitionService` | Webhook-Verarbeitung: Erkennung persistieren, Entity-Event feuern |
| `VisionController` | REST für Frontend und Sidecar (s. u.) |
| `VisionEntityMapper` | Erkennung → Entity-State-Layer, nach dem etablierten Hook-Muster (try/catch um das komplette Mapping) |
| `VisionException` | Fachliche Fehler mit sauberer Meldung ans Frontend |

REST-Endpoints:

- `GET/POST/PUT/DELETE /v1/vision/persons` (+ `POST /v1/vision/persons/{id}/photos`,
  `DELETE .../photos/{photoId}`)
- `GET /v1/vision/recognitions` (Historie, absteigend, mit Thumbnail)
- `GET /v1/vision/status`, `POST /v1/vision/auth/login`, `POST /v1/vision/auth/verify`
  (Proxy zum Sidecar)
- `POST /v1/vision/recognitions`, `POST /v1/vision/heartbeat`,
  `GET /v1/vision/embeddings` (nur Sidecar → Backend)

Liquibase-Changesets (neue Tabellen):

- `vision_person`: id, name, aktiv, angelegt am
- `vision_person_photo`: person_id, Foto (Blob), Embedding (Blob/JSON), angelegt am
- `vision_recognition`: Zeitstempel, person_id (null = unbekannt), Konfidenz,
  Thumbnail (Blob), Anzahl unbekannter Gesichter

Repositories liegen in `com.household.manager.repository` (JpaConfig-Einschränkung).

## Entity-State-Layer

- Neue Source **`VISION`** in `EntitySource`; Domain **`EVENT`** wird wiederverwendet
  (wie bei den Zigbee-Tastern).
- `event.blink_door_person`: jede Erkennung feuert ein `EntityEventFired` mit Payload
  `{ person: "<name>", personId: <id>, confidence: <0..1>, unknownFaces: <n> }`.
  Auch „nur unbekannte Gesichter" feuert ein Event (`person: null`) – nutzbar für
  spätere Benachrichtigungs-Flows.
- Ausbleibender Sidecar-Heartbeat → Entität `unavailable` (gleiches Muster wie beim
  Wandtablet).

## Frontend: neue Seite „Gesichtserkennung“

- **Personenverwaltung:** Personen anlegen/umbenennen/deaktivieren, pro Person mehrere
  Referenzfotos hochladen (Empfehlung im UI: 3–5 Fotos, frontal, gute Beleuchtung),
  Fotos einzeln löschbar.
- **Blink-Konto:** Login-Status; Login-Dialog mit E-Mail/Passwort + 2FA-PIN
  (analog zur Alexa-Anmeldung auf der Ansagen-Seite).
- **Erkennungshistorie:** letzte Erkennungen mit Thumbnail, Name/„Unbekannt",
  Konfidenz und Zeitpunkt – die Grundlage für die Entscheidung, den Auto-Unlock-Flow
  scharfzuschalten.
- `VisionService` (Angular) + Modelle unter `models/`; Styling gemäß bestehender
  Konventionen (keine lumina-Klassen außerhalb von `dashboard.component.scss`).

## Flow „Haustür-Auto-Unlock“ (Schritt 2, per Flow-MCP angelegt)

- **Trigger:** `entity-event-trigger` auf `event.blink_door_person`
- **Condition:** `person` ∈ Menge der freigeschalteten Bewohner (die Konfidenz-Schwelle
  setzt bereits der Sidecar durch; die Condition prüft nur die Person)
- **Aktionen:** `nuki-lock-action` mit `action = unlatch` (smartlockId als String!),
  danach optional Alexa-Ansage „Willkommen zuhause, <Name>"
- **Vorgehen wie beim Waschmaschinen-Flow:** Flow wird erstellt und deployt, aber
  **deaktiviert**. Scharfschaltung erst manuell, nachdem die Erkennungshistorie über
  einige Tage zuverlässige Ergebnisse zeigt (keine False Positives bei Fremden).

## Deployment

- Neuer Compose-Service `blink-vision` mit Volume für die blinkpy-Session, im selben
  Netz wie das Backend. Keine Credentials in Umgebungsvariablen – Login läuft in-app.
- Ressourcen: InsightFace `buffalo_s` auf CPU, RAM-Bedarf ca. 0,5–1 GB –
  passend für die NAS/Mini-PC-Klasse des Zielsystems.

## Fehlerbehandlung

- Blink-Cloud/Login-Fehler: Sidecar loggt, meldet Status „degraded"; Backend setzt die
  Entität `unavailable`; Frontend zeigt den Zustand auf der Seite. Kein Retry-Sturm –
  der nächste reguläre Poll genügt.
- Analyse-Fehler eines einzelnen Clips (korruptes Video o. Ä.) überspringen den Clip
  und stoppen nie den Poll-Loop.
- Webhook-Verarbeitung im Backend: Persistenz- oder Mapping-Fehler dürfen sich nicht
  gegenseitig mitreißen (Hook-Muster, try/catch pro Schritt).
- Nuki-Aktionsfehler im Flow: sichtbar über die Flow-Debug-Einträge; kein stiller
  Fehlschlag.

## Tests

- `VisionRecognitionService`/`VisionEntityMapper`: Webhook → Persistenz + Event-Payload,
  Unbekannt-Fall, `unavailable` bei ausbleibendem Heartbeat.
- `VisionPersonService`: CRUD, Foto-Upload inkl. Embedding-Roundtrip (Sidecar gemockt),
  Embedding-Push bei Änderungen.
- `VisionSidecarClient`: gegen gemockte HTTP-Responses (inkl. unbekannter JSON-Felder).
- Sidecar (pytest): Embedding-Vergleich/Schwelle, Cooldown-Logik, Clip-Dedupe.
- Frontend: Komponententests für Personenverwaltung und Historie.

## Einmalige Voraussetzungen (Benutzerseite)

- Amazon-/Blink-Zugangsdaten für den In-App-Login bereithalten (2FA-PIN kommt per
  E-Mail/SMS).
- Beim ersten Betrieb: 3–5 Referenzfotos pro Bewohner hochladen.
- Nach einigen Tagen Historie prüfen und den Auto-Unlock-Flow bewusst aktivieren.
