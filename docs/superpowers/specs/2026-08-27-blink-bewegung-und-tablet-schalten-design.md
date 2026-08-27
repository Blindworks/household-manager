# Blink: Bewegungsmeldung + Schalten vom Tablet — Design (2026-08-27)

## Ziel

Zwei Erweiterungen des am selben Tag gebauten Blink-Kamera-Dashboards
(`docs/superpowers/specs/2026-08-27-blink-kamera-dashboard-design.md`):

1. **Schalten vom Wandtablet:** Scharf/Unscharf (Kamera und Sync-Modul) auch in der
   Tablet-Ansicht — bewusste Revision der bisherigen KIOSK-Sperre. Unscharf mit
   Bestätigungsdialog, Scharf direkt.
2. **Bewegungsmeldung:** Erkannte Bewegungen werden als Flow-Ereignis gemeldet
   **und** je Kamera als „Letzte Bewegung" angezeigt.

Dazu ein kleiner Fix: Platzhalter statt leerem Bild, wenn Blink noch kein
Standbild geliefert hat.

## Entscheidung zur Erkennungsquelle

blinkpys `motion_detected` hängt an der Cloud-Video-API und bleibt beim
Abo-losen Betrieb (Local Storage) mutmaßlich leer — verworfen. Stattdessen:
**das Local-Storage-Manifest ist die Quelle.** Jede Bewegung erzeugt dort einen
Clip; der Sidecar-Poller liest das Manifest ohnehin alle 10 s für die
Gesichtserkennung. Latenz real 15–45 s (Cloud-Sicht des Manifests) — bewusst
akzeptiert, eine Türklingel-Sofortmeldung ist das nicht.

## Teil 1: Schalten vom Tablet (Revision der KIOSK-Sperre)

- **Security:** `POST /v1/blink/cameras/*/arm|disarm` und
  `POST /v1/blink/system/*/arm|disarm` kommen in die KIOSK-POST-Whitelist der
  `SecurityConfig`. Die `SecurityRulesTest`-Fälle werden umgedreht (KIOSK darf
  jetzt beide Richtungen); die Testkommentare dokumentieren die Revision, damit
  die Historie nachvollziehbar bleibt.
- **Tablet-Ansicht:** Die Typ-Sperre (`Pick<BlinkService, …>`) wird um
  `setCameraArmed`/`setSystemArmed` erweitert, der Whitelist-Test um die neuen
  Bedienelemente ergänzt — beide Schutzmechanismen bleiben und beschreiben den
  neuen Sollzustand. Alle Kommentare, die die alte Entscheidung begründen
  (Komponente, Spec-Verweise, CLAUDE.md), werden nachgezogen.
- **Bestätigungsdialog nur beim Unscharfschalten** (Kamera und System); Scharf
  direkt. Muster `confirm_required` der Steckdosen: statischer Warntext + roter
  Bestätigungsknopf, eigenes Dialog-Markup in der Tablet-Seite. Vor dem
  Bestätigen wird die Kamera/das Sync-Modul aus der **aktuellen** Liste neu
  aufgelöst; ist der Zustand nicht mehr „scharf", passiert nichts (Regel aus
  `DashboardComponent.confirmToggle` — ein Hintergrund-Refresh bei offenem
  Dialog darf nichts Falsches schalten).
- Die **Website-Seite bleibt unverändert** (direktes Schalten, kein Dialog).
- Die Tablet-Kacheln werden dafür wie auf der Website **nach Sync-Modul
  gruppiert** (Kopfzeile mit System-Schalter).

## Teil 2: Bewegungsmeldung

### Sidecar (blink-vision)

- **Bewegungs-Wächter** als zweiter, unabhängiger Verbraucher des vorhandenen
  10-s-Manifest-Durchlaufs im Poller — kein eigener Zeitplan, keine
  zusätzlichen Cloud-Abrufe.
- **Hochwassermarke je Kamera** (im Speicher, geschlüsselt über die stabile
  `camera_id`): Erster Durchlauf setzt sie auf den neuesten vorhandenen Clip,
  **ohne zu feuern** (kein Meldeschwall aus dem Alt-Bestand). Danach: jeder
  Clip oberhalb der Marke = eine Bewegung.
- **Webhook** `POST {BACKEND_URL}/api/v1/blink/motion` (bestehendes
  Service-Token, Muster Erkennungs-Webhooks) mit einer Liste von
  `{cameraId, cameraName, clipId, createdAt}`. Die Marke wird **erst nach
  erfolgreichem Webhook** vorgezogen — schlägt er fehl, wird beim nächsten
  Zyklus erneut gemeldet statt verloren.
- **Strikte Isolation:** Fehler im Bewegungs-Pfad dürfen die Gesichtserkennung
  nicht stören und umgekehrt (am Erkennungs-Pfad hängt der Auto-Unlock-Flow).
  Der Wächter fasst weder den Dedupe-Store der Erkennung noch deren
  Download-Pfad an; er liest nur Manifest-Metadaten und lädt keine Clips.

### Backend

- **`POST /v1/blink/motion`** mit SERVICE-Authority (wie die Vision-Webhooks;
  Browser-Sessions kommen nicht ran); `SecurityRulesTest` in beide Richtungen.
- **`BlinkMotionService`:**
  - feuert je Bewegung `event.blink_<cameraId>_motion` (`EntitySource.BLINK`,
    EVENT-Domain, Attribute `cameraName`, `clipId`, `createdAt`) über den
    bestehenden Ereignis-Mechanismus (Muster Zigbee-Taster/Vision) — Flows wie
    „Bewegung + Modus Abwesend → Push" entstehen danach via flow-mcp, ohne Code;
  - merkt sich je Kamera `lastMotionAt` + `lastMotionClipId` — **nur im
    Speicher** (Muster `NetworkDeviceStatusMonitor`): überlebt keinen Neustart,
    bewusste Grenze.
- **`GET /v1/blink/cameras`** liefert über einen eigenen Antwort-Record
  zusätzlich `lastMotionAt`/`lastMotionClipId` je Kamera.
- EVENT-Entitäten tauchen nicht in der `unavailable`-Markierung des Pollers auf
  (ein Ereignis hat keinen fortdauernden Zustand — wie bei Zigbee). Die
  bekannte Falle gilt weiter und wird dokumentiert: ein Flow-Trigger auf
  `value: "unavailable"` kann nie feuern.

### Frontend

- **Beide Kameraseiten:** „Letzte Bewegung: <Datum Uhrzeit>" an der Kachel
  (fehlt der Wert, entfällt die Zeile wortlos). Klick darauf spielt den
  zugehörigen Clip im vorhandenen Player (`lastMotionClipId` → Clip-URL).
- **Thumbnail-Platzhalter:** `(error)`-Handler am `<img>` blendet eine
  gestaltete Platzhalterfläche mit Kamera-Symbol ein (beide Seiten). Der
  Schnappschuss-Knopf bleibt nutzbar — er ist der Weg zum ersten Bild.

## Bewusste Grenzen

- Latenz 15–45 s; keine Sofortmeldung.
- Bewegungen während eines Sidecar-Neustarts gehen verloren (Marke startet auf
  „neuester Clip"); `lastMotionAt` übersteht keinen Backend-Neustart.
- Nur Kameras mit Local Storage werden erfasst.
- Kein automatisch angelegter Flow; der Benachrichtigungs-Flow entsteht nach
  dem Rollout via flow-mcp, wenn die Ereignisse real ankommen.
- `createdAt` kommt aus dem Manifest (`item.created_at.isoformat()`); ob der
  Zeitstempel UTC oder Lokalzeit trägt, ist beim ersten Realtest zu prüfen und
  die Anzeige ggf. nachzuziehen.

## Tests

- **Sidecar:** Hochwassermarke (Erststart feuert nicht; neuer Clip genau
  einmal; Webhook-Fehlschlag → Wiederholung im nächsten Zyklus; Isolation von
  der Gesichtserkennung), Webhook-Aufruf mit korrektem Payload.
- **Backend:** `BlinkMotionService` (Ereignis + Merken + Anreicherung),
  Security (SERVICE-only für den Webhook; KIOSK darf jetzt beide
  Schaltrichtungen — Tests umgedreht, mit Revisions-Kommentar).
- **Frontend:** Dialogverhalten (Unscharf fragt, Scharf nicht; Neu-Auflösung
  vor dem Bestätigen; abgelaufener Zustand → kein Schalten), erweiterter
  Whitelist-Test der Tablet-Bedienelemente, Platzhalter bei Bildfehler.
- Mutationsproben an den sicherheitsrelevanten Tests (etabliertes Vorgehen).
