# Digitaler Zwilling Stufe 4: Grundriss nachschärfen und echte Geräteplätze

Stand 2026-11-06. Baut auf der 3D-Hausansicht `/tablet/house` auf (Specs `2026-11-05-digitaler-zwilling-3d-design.md`, `2026-11-05-zwilling-geraete-detail-design.md`, `2026-11-06-zwilling-detail-feld-ausbau-design.md`).

## Warum

Heute steht der Grundriss auf geschätzten Maßen, und die Geräte eines Raums sitzen in einem Raster um die Raummitte. Der Lichtschein der Stehlampe erscheint deshalb irgendwo im Wohnzimmer, das rote Glühen des Badfensters mitten im Bad. Die Szene soll das echte Haus zeigen: Räume in der richtigen Größe, der Balkon im Obergeschoss, und jedes Gerät dort, wo es wirklich steht.

## Ziele

1. Der Grundriss bekommt die vom Nutzer im Browser abgestimmten Maße, dazu den Balkon.
2. Eine Admin-Seite der Website, auf der man Geräte in einer 2D-Draufsicht je Etage an ihren echten Platz zieht. Die Plätze werden gespeichert.
3. Das Wandtablet zeichnet Geräte-Bubble und Zustandseffekt am gespeicherten Platz. Die Höhe in der Szene ergibt sich automatisch aus der Geräteart.

## Nicht-Ziele

- Kein Bearbeiten in der 3D-Ansicht und keins auf dem Wandtablet. Das Tablet liest die Plätze nur.
- Keine Höhe pro Gerät (Nutzerentscheidung: automatisch nach Art).
- Die Raumzuordnung (`area` in `house-devices.ts`) wird nicht über die Admin-Seite geändert. Ein Gerät bleibt in seinem Raum.
- Keine Wände, Türen oder Fenster als eigene Geometrie. Räume bleiben Kästen.
- Die Raum-Bubbles im Überblick bleiben unverändert (Raummitte).
- Kein Pflegen des Grundrisses über die Oberfläche. Er bleibt Code in `house-plan.ts`.

## Anti-Ziele

- Ein Gerät, das außerhalb seines Raums landet: in einem anderen Raum, im Gäste-WC-Ausschnitt des Flurs oder außerhalb des Hauses. Das darf weder durch Ziehen noch durch einen späteren Grundriss-Umbau passieren, ohne dass es auffällt.
- Ein Speicherfehler, der das Wandtablet leer oder kaputt aussehen lässt. Fehlen die Plätze, gilt das heutige Raster.
- Das Wandtablet (KIOSK) kann Plätze ändern.

## Teil A: Grundriss

Einheiten wie bisher (`UNITS_PER_METER = 40`). Neue Werte in `HOUSE_ROOMS`, die nicht genannten Räume bleiben unverändert:

| Raum | Rechteck | Maße |
|---|---|---|
| Esszimmer (EG) | `rect(20, 90, 210, 240)` | 4,75 × 3,75 m |
| Küche (EG) | `rect(20, 240, 210, 350)` | 4,75 × 2,75 m |
| Laufband-Raum (EG) | unverändert `rect(20, 350, 210, 440)` | |
| Wohnzimmer (EG) | `rect(210, 90, 420, 270)` | 5,25 × 4,5 m |
| Flur (EG) | `rect(210, 270, 420, 440)`, Ausschnitt Gäste-WC unverändert `rect(210, 370, 290, 440)` | |
| Gäste-WC (EG) | unverändert | |
| Schlafzimmer (OG) | `rect(20, 90, 420, 240)` | 10 × 3,75 m |
| Büro (OG) | `rect(20, 240, 250, 340)` | 5,75 × 2,5 m |
| Bad (OG) | `rect(20, 340, 250, 440)` | 5,75 × 2,5 m |
| Treppenhaus (OG) | `rect(250, 240, 420, 440)` | 4,25 × 5 m |
| Vorratskeller | `rect(20, 90, 210, 280)` | 4,75 × 4,75 m |
| Waschkeller | `rect(20, 280, 210, 440)` | 4,75 × 4 m |
| Pain Cave, Kellereingang | unverändert | |

Garten, Terrasse und Vorplatz bleiben unverändert.

**Balkon:** ein neuer Außenbereich im Obergeschoss, `rect(130, 40, 310, 90)` (4,5 × 1,25 m), mittig an der Gartenseite des Schlafzimmers. Die Größe hat Claude geschätzt, der Nutzer hat nicht widersprochen. Damit das geht, bekommt `OutdoorArea` eine Etage. Garten, Terrasse und Vorplatz liegen im EG, der Balkon im OG. Gezeichnet wird ein Außenbereich auf der Bodenhöhe seiner Etage und nur, wenn diese Etage gewählt ist. Heute hängt das an `areOutdoorAreasVisible(selected)` (nur EG) und an Bodenhöhe 0. Beides wird je Bereich entschieden. Der Balkon hat in v1 keine Geräte (die Balkontür bleibt bewusst außen vor) und ist nur Umriss.

Für die Nutzer-Abstimmung (gitignored, nur lokal) sind die Browser-Entwürfe unter `.superpowers/brainstorm/803-1793980973/content/` maßgeblich: `grundriss-eg-v2.html`, `grundriss-og-keller-v4.html` und `grundriss-keller-v3.html`.

## Teil B: Gespeicherte Geräteplätze

### Datenmodell (Backend)

Eine Tabelle `house_device_position`:
- `device_id`: VARCHAR(100), Primärschlüssel. Das ist `HouseDevice.id` aus dem Frontend.
- `plan_x` und `plan_y`: DECIMAL(6,1), in Skizzen-Einheiten.
- `updated_at`

Ein Changeset je DDL (MariaDB-Autocommit). Das Backend kennt die Geräteliste nicht. Es prüft nur die Form: `device_id` nicht leer, höchstens 100 Zeichen, nur `[a-z0-9_-]`. Die Koordinaten müssen endlich sein (`Double.isFinite`) und im Bereich 0–500 liegen. Ob der Punkt im richtigen Raum liegt, prüft das Frontend (siehe unten). Es ist die einzige Stelle, die den Grundriss kennt.

### API

Unter `/api/v1/house/device-positions`:
- `GET`: alle Plätze als Liste `{deviceId, x, y}`. Das Lesen ist KIOSK über die generische `GET /v1/**`-Regel, denn das Tablet braucht die Plätze.
- `PUT /{deviceId}` mit `{x, y}`: setzen (Upsert).
- `DELETE /{deviceId}`: zurücksetzen. Fehlt die Zeile, antwortet der Endpunkt trotzdem mit 204.

`PUT` und `DELETE` sind **methodenspezifische ADMIN-Matcher**. Ohne sie fielen sie auf `anyRequest` → MEMBER. `SecurityRulesTest` hält drei Richtungen fest: KIOSK darf lesen, MEMBER darf nicht schreiben, ADMIN darf schreiben. Audit: `house.device-position.update` und `house.device-position.delete`, jeweils nach dem Erfolg. Das Detail nennt die `deviceId`, beim Setzen auch die Koordinaten.

### Wo ein Gerät steht (Frontend, eine Definition)

Eine Funktion entscheidet für jedes Gerät seinen Platz. Raumansicht-Bubble, Effekt und Geräte-Detail-Kamera (`cameraForDevice`) fragen alle diese Funktion. Heute heißt sie `deviceAnchor`, sie bekommt die gespeicherten Plätze als Eingabe.
- **Gespeicherter Platz gültig:** Der Punkt liegt in einem Rechteck seines Bereichs und nicht in einem Ausschnitt. Dann gilt x/z aus diesem Punkt.
- **Kein gespeicherter Platz oder ungültig** (z. B. nach einem Grundriss-Umbau außerhalb des Raums): Es gilt das heutige Raster. Platziert werden nur die Geräte des Bereichs **ohne** gültigen Platz. Ein platziertes Gerät belegt also keine Rasterzelle mehr.
- **Die Punktprüfung ist strikt:** Der Punkt muss in einem Rechteck des eigenen Bereichs liegen, der Rand zählt mit. Überlappende Außenflächen werden nicht zusammengelegt. Ein Gerät mit `area: 'garden'` ist nur im Garten-Rechteck gültig (Claudes Entscheidung).
- **Höhe nach Art.** Die Regel ist eine Nutzerentscheidung, die Werte und die Zuordnung je `kind` hat Claude festgelegt. Sie gilt für **alle** Geräte, auch für Geräte im Raster:
  - Lampen (`switch` mit `lamp: true`): 2,3 m über dem Etagenboden.
  - An der Wand oder Tür sitzend, also `thermostat`, `contact`, `door`, `camera`, `motion` und `button`: 1,2 m.
  - Alles andere, also `climate`, `switch` ohne Lampe, `appliance`, `waste` und `pet`: 0,5 m.
  - Außen gilt dieselbe Regel auf der Bodenhöhe der Etage des Außenbereichs.
  - Dadurch kommt `floorY` eines Effekts künftig aus der Etagenbasis, nicht mehr aus `anchor.y - DEVICE_ANCHOR_HEIGHT_M`.
- **Bubble, Effekt und Kamera haben verschiedene Höhen** (Claudes Entscheidung):
  - Die Raumansicht-Bubble sitzt an x/z des Geräts, aber immer 1,2 m über dem Etagenboden. So steht sie weder im Boden noch verdeckt sie die Deckenlampe.
  - `cameraForDevice` zielt auf den Anker auf Ankerhöhe.
- **Effekte:** Ihr hängender Teil (Glühbirne, Glühen) sitzt auf Ankerhöhe, der Schein am Boden bleibt am Boden. Eine Deckenlampe leuchtet also von oben auf den Boden darunter. Wie der Renderer das im Detail zeichnet, entscheidet der Builder.
- `HOUSE_BOUNDS` bleibt unverändert (Claudes Entscheidung). Garten und Balkon liegen wie bisher außerhalb. Die Grenze zentriert nur das Haus.

### Tablet-Seite

`/tablet/house` lädt die Plätze beim Start und im 30-s-Takt mit, wie die Entitäten. Scheitert der Abruf, bleibt der letzte Stand stehen. Gab es noch nie einen Stand, gilt das Raster, ohne Fehlermeldung. Die Szene baut nur neu auf, wenn sich die Plätze tatsächlich geändert haben (Render-on-demand bleibt).

### Admin-Seite „Geräteplätze“

Die Route ist `admin/house-devices`, hinter `adminGuard`. Ein Eintrag kommt in die Admin-Navigation, und die Wurzelklasse kommt in `$admin-scopes` (`styles.scss`), sonst sind die Knöpfe unstilisiert. Die Gestaltung folgt dem Browser-Entwurf `geraeteplaetze-admin.html`:
- Oben stehen die Reiter Keller, Erdgeschoss und Obergeschoss. Der EG-Reiter zeigt zusätzlich Garten, Terrasse und Vorplatz, der OG-Reiter den Balkon.
- Die Draufsicht als SVG zeigt die Räume aus `house-plan.ts` mit Namen. Jedes Gerät mit gültigem Platz ist ein Punkt in der Farbe seiner Art, mit Beschriftung (Gerätename, bei Mehrdeutigkeit mit Raum).
- Rechts steht die Liste „Noch ohne Platz“ für die Geräte der Etage ohne gültigen Platz. Ein Gerät, dessen gespeicherter Platz ungültig geworden ist, steht dort mit dem Vermerk „Platz liegt außerhalb des Raums“.
- **Ziehen per Pointer Events** (Maus und Finger). Losgelassen wird nur gespeichert, wenn der Punkt im eigenen Bereich liegt. Sonst springt der Punkt zurück, und ein Hinweis nennt den Raum, in den das Gerät gehört („Gehört ins Bad“). Ein Gerät aus der Liste wird auf den Plan gezogen. Wer lieber tippt, tippt das Listengerät an und dann die Stelle im Plan, das ist der Ersatzweg. Kein Speichern während der Bewegung, nur beim Loslassen.
- Nach dem Antippen eines platzierten Punkts erscheint „Platz zurücksetzen“. Das ruft `DELETE`, und das Gerät wandert zurück in die Liste.
- Ein Fehler beim Speichern zeigt eine Meldung, und der Punkt springt auf den letzten gespeicherten Stand zurück. Ladefehler: Die Seite zeigt eine Fehlermeldung statt eines leeren Plans, der wie „nichts platziert“ aussähe.
- Gespeicherte Plätze von Geräten, die es im Code nicht mehr gibt, werden ignoriert. Kein Aufräumen in v1.

Die Website-Draufsicht und die 3D-Szene teilen sich die Geometrie- und Gültigkeitsregeln in `shared/house/`, nicht die Darstellung.

## Was der Builder entscheidet

- Den Namen der Platz-Entscheidungsfunktion und ob sie in `house-effects.util.ts` bleibt oder nach `house-geometry.util.ts` wandert.
- Die SVG-Skalierung und den Trefferradius der Punkte (mindestens fingerbreit, etwa 24 px).
- Den Service-Schnitt im Frontend (eigener `HouseDevicePositionService` oder Teil von `HouseDeviceControlService`).
- Die Paketstruktur im Backend (Vorschlag `house/`), Controller und Service schlank nach Projektmuster.

## Dokumentation und Rollout

- In `CLAUDE.md`, Abschnitt „Digitaler Zwilling“: Grundriss-Stand, Balkon, Geräteplätze. Dazu der Hinweis, dass nach einem Grundriss-Umbau Plätze ungültig werden können und dann wieder im Raster stehen.
- Rollout: Backup, dann Deploy (Changeset `20261106-0087` und folgende, nie gegen die echte DB gelaufen). Danach auf der Admin-Seite die Geräte platzieren und auf dem Tablet prüfen.
