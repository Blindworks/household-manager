# Digitaler Zwilling: Fenster und Türen in der Wand

Stand 2026-11-11. Baut auf Stufe 4 auf (`2026-11-06-zwilling-grundriss-und-geraeteplaetze-design.md`): Grundriss, gespeicherte Geräteplätze, Admin-Seite `admin/house-devices`.

## Warum

Türen und Fenster sind in der 3D-Hausansicht bisher nur ein Punkt mit Bubble, und wenn sie offen sind, glüht der Punkt rot. Das Haus sieht damit nicht nach einem Haus aus. Künftig soll jede echte Tür und jedes echte Fenster als Element in der Wand zu sehen sein. Hat ein Element einen Sensor, schwenkt es sichtbar auf, sobald es offen ist.

## Ziele

1. Alle echten Türen und Fenster stehen in der Szene an ihrer Wand, außen und innen. Elemente mit Sensor wie ohne.
2. Gepflegt werden sie auf der Admin-Seite „Geräteplätze“: hinzufügen, an einer Wand entlang verschieben, Größe wählen, mit einem Sensor verknüpfen, löschen. Alles wird gespeichert.
3. Ein Element mit verknüpftem Sensor schwenkt bei „offen“ auf und wird rot. Elemente ohne Sensor bleiben immer geschlossen.

## Nicht-Ziele

- Keine echten Wände mit Aussparungen. Räume bleiben Neon-Kästen, die Elemente liegen in der Wandebene davor.
- Keine Pflege auf dem Wandtablet und keine Pflege in der 3D-Ansicht.
- Keine Animation des Öffnens. Der Zustand wird beim Takt neu gezeichnet: zu oder offen.
- Keine freien Maße. Es gibt nur die festen Größen unten.
- Keine weiteren Sensoren. Verknüpfbar sind nur vorhandene Fenster- und Türkontakte.

## Anti-Ziele

- Ein Element, das frei im Raum schwebt. Es sitzt immer auf einer Wand.
- Ein Element ohne Sensor, das „offen“ aussieht.
- Ein Fenster, das als zu angezeigt wird, obwohl sein Sensor `offen` meldet. Bei `unavailable` oder `unknown` gilt: geschlossen zeichnen, wie bei den bisherigen Effekten (`isOn`-Regel).
- Das Wandtablet kann Elemente anlegen oder ändern.

## Entscheidungen des Nutzers

- Alle echten Türen und Fenster, auch Innentüren zwischen den Räumen.
- Pflege auf der Admin-Seite „Geräteplätze“ per Ziehen. Ein Element rastet an der Wand ein, an die man tippt.
- Größen als feste Auswahl, nicht frei und nicht in Zentimetern.
- Darstellung **B, Glas und Türblatt** (Entwurf `fenster-stil.html` im Brainstorm-Ordner, gitignored):
  - Fenster sind eine leicht bläuliche Glasfläche mit hellem Rahmen.
  - Türen sind ein halbtransparentes Türblatt mit Rahmen.
  - Offen schwenkt die Fläche um etwa 60° nach innen auf, Fläche und Rahmen werden rot.

## Größen (Werte von Claude)

| Art | Größe | Breite | Unterkante | Oberkante |
|---|---|---|---|---|
| Fenster | schmal | 0,6 m | 0,9 m | 2,1 m |
| Fenster | normal | 1,0 m | 0,9 m | 2,1 m |
| Fenster | breit | 1,6 m | 0,9 m | 2,1 m |
| Tür | normal | 0,9 m | 0 | 2,1 m |
| Tür | Terrassentür | 1,8 m | 0 | 2,1 m |

Die Höhen gelten über dem Etagenboden (`STOREY_HEIGHT_M` 2,6). Eine Terrassentür hat zwei Flügel, die außen anschlagen und zur Mitte hin öffnen. Alle anderen Elemente haben einen Flügel mit wählbarem Anschlag.

## Anschlag und Öffnungsrichtung (Claudes Entscheidung)

- **Feste Laufrichtung der Strecke:** Beim Speichern wird die Strecke so normalisiert, dass `(x1, y1)` der kleinere Endpunkt ist, also bei waagerechter Strecke der linke und bei senkrechter der obere.
- **Zwei gespeicherte Werte:**
  - `hinge`: `START` oder `END`, also an welchem Endpunkt der Flügel anschlägt.
  - `swing`: `POSITIVE` oder `NEGATIVE`, die Wandseite, in die er aufschwenkt (+y/−y bei waagerechter, +x/−x bei senkrechter Wand).
- **Standardrichtung:**
  - Außenwand: Der Flügel schwenkt ins Haus.
  - Trennwand: Er schwenkt in den Raum, der in `HOUSE_ROOMS` zuerst steht.
  - Die Admin-Seite zeigt beides als „Anschlag links/rechts“ (von der Öffnungsseite aus gesehen) und bei Trennwänden zusätzlich „Öffnet in: <Raum A> / <Raum B>“.
- **Terrassentür:** `hinge` ist bedeutungslos. Sie hat zwei Flügel mit Anschlag an beiden Enden.

## Wände

Eine Wand ist die Vereinigung aller Kanten der Raumrechtecke einer Etage aus `house-plan.ts` (Claudes Entscheidung).

- **Gültigkeit:** Kanten auf derselben Linie verschmelzen. Ein Element ist gültig, wenn seine ganze Strecke auf dieser verschmolzenen Linie liegt, auch wenn sie über die Kanten zweier Räume läuft. Die Strecke ist waagerecht oder senkrecht.
- **Außenwand oder Trennwand:** Liegt auf nur einer Seite der Strecke ein Raum, ist es eine Außenwand. Liegen auf beiden Seiten Räume, ist es eine Trennwand. Maßgeblich ist der Mittelpunkt der Strecke.
- **Ausschnitte:** `cutouts` (Gäste-WC im Flur) zählen nicht als eigener Raum, ihre Kanten sind aber die Kanten des Raums, der dort liegt (Gäste-WC).
- **Außenbereiche** (Garten, Terrasse, Vorplatz, Balkon) sind keine Wandquelle.
- Ein Element gehört zu keinem Raum, sondern nur zu seiner Etage, und ist sichtbar, wenn diese Etage gewählt ist.
- **Keine Überlappung:** Zwei Elemente auf derselben Linie dürfen sich nicht überlappen. Das prüft nur das Frontend: Verschieben, Anlegen und Vergrößern lehnt es mit Hinweis ab.

Wird ein Raum in `house-plan.ts` später verändert, kann ein gespeichertes Element neben jeder Wand liegen. Dann zeichnet die Szene es nicht. Die Admin-Seite listet es mit „Liegt auf keiner Wand“, damit man es neu setzen oder löschen kann. Das ist dieselbe Regel wie bei den Geräteplätzen.

## Datenmodell und API (Backend)

Tabelle `house_opening`, ein Changeset, Nummer nach dem jüngsten in `db.changelog-master.xml`:

- `id` (PK, auto)
- `floor` (`basement`/`ground`/`upper`)
- `kind` (`WINDOW`/`DOOR`)
- `size` (`SMALL`/`NORMAL`/`WIDE` für Fenster, `NORMAL`/`TERRACE` für Türen)
- `x1`, `y1`, `x2`, `y2` (`DECIMAL(6,1)`): die Strecke auf der Wand in Plan-Einheiten
- `hinge` (`START`/`END`), `swing` (`POSITIVE`/`NEGATIVE`)
- `device_id` (nullable, max. 100 Zeichen, die `HouseDevice.id` des verknüpften Kontakts, **eindeutig**: Unique-Constraint, das Backend antwortet mit 409, wenn das Gerät schon an einem anderen Element hängt)
- `updated_at`

API `/api/v1/house/openings`:

- `GET`: liefert die Liste.
- `POST`: legt an.
- `PUT /{id}`: ersetzt ein Element vollständig.
- `DELETE /{id}`: löscht. Ein fehlendes Element ergibt 404.

Prüfungen im Backend: Enum-Werte, `size` passend zu `kind`, Koordinaten endlich und im Bereich 0–500, die Strecke waagerecht oder senkrecht und ihre Länge gleich der Breite der Größe. `device_id` folgt der Form wie bei den Geräteplätzen. Ob die Strecke auf einer Wand liegt, prüft nur das Frontend, denn nur das kennt den Grundriss (wie bei den Geräteplätzen).

Sicherheit: Lesen ist KIOSK über die generische `GET /v1/**`-Regel. `POST`, `PUT` und `DELETE` sind methodenspezifische ADMIN-Matcher, `SecurityRulesTest` hält alle Richtungen fest. Audit: `house.opening.create/update/delete`, jeweils nach Erfolg.

## Frontend

**Eine Definition:** Ein neues Util in `shared/house/` (Vorschlag `house-openings.util.ts`) enthält:

- die Größentabelle
- die Wandkanten je Etage
- das Einrasten an einer Wand: nächste Kante, Mitte auf die Kante projizieren, ganz innerhalb halten
- die Gültigkeitsprüfung
- die 3D-Geometrie in reinen Zahlen: Ecken, Flügeldrehung

Admin-Seite und Renderer fragen beide dieses Util.

**Sensor-Verknüpfung:** Wählbar sind die Geräte aus `house-devices.ts` mit `kind: 'contact'` und `isWindowContact`, also ohne Gaszähler, dazu die Haustür (`kind: 'door'`, Kontakt `contactEntityId`). Ein Gerät lässt sich nur mit einem Element verknüpfen. Die Liste zeigt nur noch freie Geräte.

- **Neu in `house-devices.ts` (Claudes Entscheidung):** Die Balkontür (`binary_sensor.dirigera_f3992ae92ca54fb6b276c2c4d5f832c9_1_contact`, Konstante `BALCONY_DOOR_ENTITY_ID` in `shared/balcony-cold-insight.util.ts`) kommt als Kontakt „Balkontür“ ins Schlafzimmer. Bisher fehlte sie, weil es keinen Balkon gab. Damit bekommt das Schlafzimmer auch Bubble-Flag und Effekt „Fenster offen“.
- **Offen ist ein Element, wenn sein Gerät offen ist:** beim Kontakt `isOn(entityId)`, bei der Haustür `isOn(contactEntityId)`.

**Verwaiste Verknüpfung (Claudes Entscheidung):** Gibt es die `device_id` in `house-devices.ts` nicht mehr, zeichnet die Szene das Element geschlossen. Die Admin-Seite zeigt im Feld des Elements „Sensor nicht gefunden“ und bietet „kein Sensor“ an.

**Rotes Glühen (Claudes Entscheidung):** Ein Kontakt oder eine Haustür, die mit einem Element verknüpft ist, erzeugt keinen `open`-Effekt (rotes Glühen) mehr, in Überblick und Raumansicht gleich. Das aufgeschwenkte rote Element ersetzt ihn. Ein unverknüpfter Kontakt glüht weiter wie heute. Bubbles und Bubble-Flags („Fenster offen“) bleiben unverändert. Die Terrassentür ist schon ein `contact` im Wohnzimmer und damit verknüpfbar.

**3D (Renderer):** Die Elemente werden je Etage in deren Layer-Gruppe gezeichnet und folgen damit der Etagenwahl.

- Fenster: Glasfläche mit Rahmen.
- Tür: Türblatt mit Rahmen.
- Farben über Konstanten im Renderer, im Neon-Stil der Szene.
- Offen: Die Fläche dreht um ihre Anschlagkante um 60° in den Raum, Fläche und Rahmen sind rot.
- „In den Raum“: Bei einer Außenwand zeigt die Drehung ins Hausinnere. Bei einer Trennwand dreht sie zur Seite des Raums, aus dem der Anschlag gewählt wurde. Vorschlag: zur Seite des ersten Raums in Plan-Reihenfolge, Builder-Entscheidung.
- Render-on-demand bleibt. Neu gezeichnet wird nur, wenn sich Elemente oder offene Zustände ändern.
- Die Elemente laden im 30-s-Takt mit wie die Geräteplätze. Ein Fehler behält still den letzten Stand, ohne einen Stand gibt es keine Elemente.

**Admin-Seite „Geräteplätze“, zweiter Teil:**

- Unter der Etagenwahl stehen die Knöpfe „Fenster hinzufügen“ und „Tür hinzufügen“. Danach tippt man an eine Wand: Das Element entsteht dort mit Standardgröße (Fenster normal, Tür normal) und Anschlag links und wird gespeichert.
- In der Draufsicht ist ein Element ein dicker Strich auf der Wand, Fenster bläulich, Türen heller. Eine Tür bekommt dazu einen kleinen Bogen für die Öffnungsrichtung.
- Ziehen verschiebt das Element entlang seiner Wand. Über eine Ecke hinweg darf es auf eine andere Wand springen, wenn diese näher liegt. Gespeichert wird nur beim Loslassen.
- Antippen öffnet ein kleines Feld mit: Größe, Anschlag links/rechts, „Mit Sensor verknüpfen“ (Auswahl oder „kein Sensor“) und „Löschen“ mit Rückfrage.
- Passt eine größere Größe nicht mehr auf die Wand, lehnt das Feld sie mit Hinweis ab.
- Fehlerverhalten wie bei den Geräteplätzen: Speichern scheitert ⇒ Meldung, und der letzte gespeicherte Stand bleibt. Laden scheitert ⇒ Meldung statt eines leeren Plans.
- Elemente, die auf keiner Wand mehr liegen, stehen in einer eigenen Liste „Liegt auf keiner Wand“ mit „Löschen“.

## Was der Builder entscheidet

- Die Namen von Util, Service und Komponenten.
- Ob die Pflege der Elemente eine eigene Kindkomponente der Admin-Seite wird.
- Die genaue Geometrie im Renderer: Rahmenstärke, Abstand vor der Wand gegen Z-Fighting, Materialien.
- Die Trefferzone für „an eine Wand tippen“ (Vorschlag: höchstens 12 Plan-Einheiten Abstand, sonst passiert nichts).

## Doku und Rollout

- `CLAUDE.md`, Abschnitt „Digitaler Zwilling“: neuer Unterpunkt „Fenster und Türen“ mit Tabelle, API, Security, Audit, Util als einziger Definition und der Rotes-Glühen-Regel.
- Das Changeset ist nie gegen die echte DB gelaufen. Vor dem Deploy ein Backup machen. Danach die Elemente auf der Admin-Seite setzen.
