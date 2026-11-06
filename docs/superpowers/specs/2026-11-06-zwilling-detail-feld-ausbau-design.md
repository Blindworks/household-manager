# Digitaler Zwilling: Detail-Feld ausbauen (Stufe 3)

Stand 2026-11-06. Baut auf `2026-11-05-zwilling-geraete-detail-design.md` auf (Detail-Feld der Hausansicht `/tablet/house`) und auf dem lebendigen Haus (081ad2b9).

## Absicht

Auf dem Wandtablet soll das Detail-Feld der 3D-Hausansicht das Gerät bedienen, ohne dass man die Ansicht verlassen muss. Bisher wechseln Haustür, Kamera, Mülltonne und Toni auf ihre eigene Seite. Danach zeigt das Haus selbst alles, was man im Vorbeigehen braucht. Lampen bekommen Farbe und Farbtemperatur, Thermostate ihren Modus.

## Ziele

1. **Lampen: Farbe und Farbtemperatur** im Feld, abhängig von den Fähigkeiten des Geräts.
2. **Thermostat: Modus** (Aus, Manuell, Frostschutz) im Feld.
3. **Haustür** öffnet das Feld und zeigt den Zustand von Schloss und Kontakt. Bedienbar ist dort nur das Verriegeln.
4. **Kamera** öffnet das Feld: Standbild, Bewegungserkennung an/aus, Schnappschuss, letzte Bewegung.
5. **Mülltonne** öffnet das Feld und zeigt den nächsten Termin je Tonnenart.
6. **Toni** öffnet das Feld: zu Hause oder unterwegs, Akku, letzter Spaziergang, Futter- und VomiSan-Vorrat.

Danach gibt es in der Hausansicht kein Gerät mehr, das auf eine andere Seite navigiert.

## Nicht-Ziele

- Entsperren oder „Tür öffnen“ am Tablet. Diese Knöpfe erscheinen im Feld überhaupt nicht, auch nicht ausgegraut.
- Clip-Wiedergabe im Kamera-Feld.
- Karte im Toni-Feld.
- Futter oder VomiSan buchen. KIOSK bekäme ohnehin 403.
- Haustür-Kamera und Airrohr. Beide sind weiterhin nicht in der Szene platziert.
- Backend-Änderungen. Alle nötigen Endpunkte gibt es, und KIOSK darf sie nutzen. Das ist im Plan zu belegen, siehe „Sicherheit“.

## Anti-Ziele (wäre ein Fehlschlag, auch wenn es technisch läuft)

- Das Tablet kann die Haustür entriegeln oder öffnen.
- Ein Hintergrund-Refresh bei offenem Feld schaltet etwas.
- Eine ausgeschaltete Lampe geht an, weil man eine Farbe gewählt hat.
- Die Kamera-Erkennung geht aus, ohne dass jemand nachgefragt wurde, oder das Feld schaltet auf einem veralteten Stand.
- Ein vergangener Abfuhrtermin steht als „heute“ da.
- Ein fehlender Wert (kein Akku, nie ein Spaziergang) erscheint als 0 statt als „–“.

## Gemeinsame Regeln (gelten für alle neuen Teile)

- Bisheriges Muster des Felds: Die Seite hält nur die Id des offenen Geräts und baut das Feld per `computed` aus Entitäten und Takt (`buildDeviceDetail`). Der 30-s-Refresh aktualisiert das Feld und löst nie eine Aktion aus.
- Daten, die nicht in den Entitäten stehen, lädt die Seite einmal beim Öffnen und danach im 30-s-Takt, solange das Feld offen ist. Gemeint sind Kameraliste, Tierdaten, Spaziergänge, Vorräte, Abfuhrtermine und Lampenfarbe. Bisher läuft das nach dem Muster `lightDevice`/`thermostatRoom` einmal beim Öffnen. Neu ist, dass diese Daten bei offenem Feld mitlaufen. Späte Antworten nach dem Schließen werden abbestellt.
  - **Meine Entscheidung:** Ein fehlgeschlagener Abruf behält still den letzten Stand. Schlägt schon der erste fehl, steht „–“ in der Zeile.
- Jede Aktion liest den Zustand vorher frisch und handelt nur, wenn er der Absicht widerspricht und eindeutig ist. Danach meldet das Feld `(changed)`, und die Seite lädt sofort, nach 1,5 s und nach 5 s neu (`FOLLOW_UP_AFTER_ACTION_MS`).
- Alles Neue liegt in `HouseDeviceControlService` (Seiten-Provider). Die reine Aufbereitung liegt in `shared/house/device-detail.util.ts`. Wo sie eigenen Umfang braucht, kommt sie in eine eigene Util-Datei unter `shared/house/`.
- `opensDetail(kind)` liefert danach für **alle** Arten `true`. Die Regel bleibt als einzige Definition bestehen. `bubbleTarget` wird für Bubbles in der Hausansicht nicht mehr gebraucht.
  - **Meine Entscheidung:** `bubbleTarget` bleibt nur bestehen, wenn es noch andere Nutzer hat. Sonst wird es entfernt.
- Außendinge im EG-Überblick (Haustür, Mülltonne, Toni) öffnen das Feld genau wie heute der Garten. Die Kamera fährt dabei an den Anker (`cameraForDevice`).

## 1. Lampen: Farbe und Farbtemperatur

- **Farbfelder** erscheinen nur bei Fähigkeit `COLOR`. Es sind 8 runde Felder:
  - Warmweiß: Farbtemperatur 2700 K, keine Farbe. **Meine Entscheidung:** Warmweiß ist ein Farbtemperatur-Wert, weil das die naheliegende Bedeutung ist.
  - Rot (0°), Orange (30°), Gelb (55°), Grün (120°), Türkis (175°), Blau (225°), Lila (280°), jeweils mit Sättigung 100.
  - **Meine Entscheidung:** die genauen Farbtöne.
- **Regler warm↔kalt** erscheint nur bei Fähigkeit `COLOR_TEMP`. Der Bereich kommt aus `metadata.colorTempRangeMin/Max`, ersatzweise 2500–6500 K, wie in der Geräteliste. Der Regler hat Schritte von 100 K. Die Regler-Spur läuft farblich von warm nach kalt.
- **Hervorhebung:**
  - Steht die Lampe im Farbmodus (`colorTemp` fehlt oder ist 0 und `hue` ist bekannt), wird das Feld mit dem nächstliegenden Farbton hervorgehoben.
  - Steht sie im Farbtemperatur-Modus, steht der Regler auf ihrem Wert. Liegt der Wert höchstens 150 K neben 2700, ist zusätzlich Warmweiß hervorgehoben.
  - Ohne bekannte Werte ist nichts hervorgehoben, und der Regler steht in der Mitte.
- **Senden:** wie beim Dimmen.
  - Gesperrt, solange die Lampe aus ist.
  - Ein Feld sendet beim Antippen, der Regler beim Loslassen (`change`).
  - Vorher wird der Schaltzustand frisch gelesen. Ist die Lampe nicht `on`, wird nichts geschrieben und „Lampe ist aus“ angezeigt.
  - Geschrieben wird über `PUT /devices/{id}/light`, mit `{hue, saturation}` oder `{colorTemp}`. Die Exklusivität der Modi regelt das Backend.
  - Nach dem Schreiben wird die Smart-Device-Liste neu geladen.
- Die Fähigkeiten und aktuellen Werte kommen aus der vorhandenen `lightDevice`-Abfrage. Sie wird um `color`, `colorTemp`, `hue`, `saturation`, `colorTempRange` erweitert.
- Heute betrifft das nur die Flur-L530. Der KL110 hat nur `BRIGHTNESS`.
- Fehlerfall: Das Gerät lehnt ab (400/502). Dann erscheint im Feld kurz „Nicht übernommen“, und die Auswahl springt nicht zurück. Das ist dieselbe Regel wie in der Geräteliste.
  - **Meine Entscheidung:** Der Text „Nicht übernommen“.

## 2. Thermostat-Modus

- Unter dem Sollwert stehen drei Knöpfe: **Aus · Manuell · Frostschutz**. Der aktuelle Modus ist hervorgehoben. Er kommt aus `GET /v1/thermostats` (`mode`), zusammen mit dem vorhandenen `thermostatRoom`-Abruf, also ohne zusätzlichen Abruf.
- Antippen schreibt sofort über `PUT /v1/thermostats/{id}/mode`, ohne Nachfrage. Vorher wird der Modus frisch gelesen. Ist er schon der gewünschte, passiert nichts.
- Nach dem Schreiben zeigt das Feld höchstens 60 s den lokalen Modus, bis das Thermostat ihn meldet. Das ist dieselbe Regel wie beim Sollwert.
- Die Backend-Regel bleibt: MANUAL ohne brauchbares Ziel setzt 17 °C.
- **Meine Entscheidung:** − / + bleiben im Modus „Aus“ bedienbar. Ein neues Ziel über 5 °C bringt das Thermostat in den Modus Manuell, weil `deriveMode` das so ableitet. Die Hervorhebung folgt dem gemeldeten Stand.

## 3. Haustür

- Kopf: Zustand des Schlosses (verriegelt, entriegelt, geöffnet, klemmt, sonst „–“) aus `lock.nuki_…`.
- Zeilen:
  - Tür offen oder zu, mit „seit HH:mm“ aus dem Dirigera-Kontakt.
  - Akku des Schlosses aus `GET /v1/nuki/locks` (`batteryCharge`). Ist `batteryCritical` gesetzt, wird die Zeile hervorgehoben.
- Ein einziger Knopf, **„Verriegeln“**:
  - Er erscheint nur, wenn das Schloss laut Entität nicht `locked` ist.
  - Vor dem Senden wird `GET /v1/nuki/locks` frisch gelesen. Ist das Schloss dort schon `locked`, wird nichts gesendet.
  - Sonst geht `POST /v1/nuki/locks/{smartlockId}/actions` mit `LOCK` raus.
  - Keine Nachfrage, denn Verriegeln ist harmlos.
- Die `smartlockId` wird aus der Entity-ID gebildet (`lock.nuki_<id>`).
- Kein Verlauf.

## 4. Kamera (Wohnzimmer)

- Standbild über `thumbnailUrl(cameraId)` mit Cache-Buster. Schlägt das Bild fehl, erscheint ein Platzhalter. Das ist die Regel der Tablet-Kamera-Seite.
- Zeile „letzte Bewegung HH:mm“ aus `lastMotionAt`. Sie fehlt, wenn keine bekannt ist. Liegt die Bewegung nicht am heutigen Tag, wird das Datum vorangestellt.
- Schalter **Bewegungserkennung**:
  - Aktivieren geht direkt.
  - Deaktivieren fragt nach, mit einem eigenen Dialog im Feld.
  - Vor dem Schalten wird die Kameraliste frisch gelesen. Geschaltet wird nur, wenn der Zustand dort noch der Absicht widerspricht.
  - Ist die Kamera aus der Liste verschwunden, wird nicht geschaltet.
  - Die Anzeige folgt `armed` aus der Kameraliste.
  - Während der Aktion zeigt der Schalter einen Spinner und ist gesperrt.
- Knopf **Schnappschuss** mit Spinner. Erst nach Erfolg wird der Cache-Buster hochgezählt. Bei einem Fehler bleibt das alte Bild und kurz erscheint „Schnappschuss fehlgeschlagen“.
- Die `cameraId` wird aus `binary_sensor.blink_<cameraId>_armed` gebildet.
- Kein Clip, kein Verlauf. Die Website- und Tablet-Kameraseiten bleiben unverändert. Das Feld ist eine dritte, eigene Umsetzung, ohne Code mit ihnen zu teilen.
- Nicht angemeldet bei Blink (400) oder Sidecar weg: Die Kamera fehlt in der Liste oder der Abruf scheitert. Dann zeigt das Feld „Kamera nicht erreichbar“, ohne Schalter und ohne Schnappschuss.

## 5. Mülltonne

- Eine Zeile je Tonnenart (`label`) mit ihrem **nächsten** Termin aus `GET /v1/waste-collection/upcoming`, sortiert nach Datum.
- Das Datum lautet „heute“, „morgen“ oder Wochentag plus Datum („Do 12.11.“). Grundlage ist das `date`, verglichen mit dem heutigen Datum auf dem Tablet, nicht `daysUntil`, weil das veraltet sein kann.
- Termine vor heute entfallen.
- Kopf: der früheste Termin.
- Ohne Termine steht „Keine Abfuhr in den nächsten Tagen“ im Feld.
- Kein Verlauf, keine Bedienung.

## 6. Toni

- Kopf: „zu Hause“ oder „unterwegs“ aus `binary_sensor.tractive_…_home`. Ohne Aussage steht „–“, nie geraten.
- Zeilen:
  - **Akku**: `batteryPercent`, dazu „lädt“, wenn `charging` gesetzt ist. Kommt aus `GET /v1/tractive/pets`, über die `trackerId` der Entität.
  - **Letzter Spaziergang**: der jüngste Eintrag aus `getWalks(trackerId, 7)`, als Datum/Zeit, Dauer und Distanz. Die Formatierung kommt aus `shared/walk-format.util.ts`. Ohne Spaziergang in 7 Tagen steht „kein Spaziergang in 7 Tagen“.
  - **Futter** und **VomiSan**: je eine Zeile mit Bestand und Reichweite aus `GET /v1/pet-supplies`. Der Ton kommt aus `shared/pet-supply-level.util.ts`, also kritisch unter 7 Tagen.
- Die drei Quellen sind unabhängig: Fällt Tractive aus, steht der Vorrat trotzdem da.
- Keine Karte, keine Buchung.

## Sicherheit

KIOSK nutzt nur Folgendes:

- **Lesende `GET /v1/**`:** Nuki, Blink, Tractive, Vorräte, Müll und Thermostate. Sie laufen über die generische Regel. Ausnahme ist `/v1/tractive/home-settings`, das hier nicht gebraucht wird.
- **Whitelist-POST/PUT:** `POST /v1/nuki/locks/*/actions`, das für KIOSK nur LOCK annimmt; `POST /v1/blink/cameras/*/arm|disarm|snapshot`; `PUT /v1/thermostats/*/mode`; `PUT /devices/*/light`.

Der Plan muss je Pfad belegen, dass KIOSK ihn darf, durch Lesen von `SecurityConfig`/`SecurityRulesTest`. Fehlt einer, stoppt der Plan und fragt. Er erweitert die Whitelist nicht still.

## Tests

- Util-Tests je Art:
  - Detailaufbau
  - Zuordnung der Farbtöne
  - Datumsformat und Filter der Mülltermine
  - Akku- und Spaziergangszeilen
- Komponententests für die Schutzregeln:
  - kein Schreiben bei ausgeschalteter Lampe
  - kein Verriegeln, wenn das Schloss frisch schon verriegelt ist
  - kein Entsperren-Knopf im DOM
  - Kamera-Deaktivieren nur nach Bestätigung und nach frischer Prüfung
  - Modus schreibt nichts, wenn er frisch schon stimmt
- `opensDetail` ist für alle Arten wahr.

Geräte werden in Tests über Raum oder Id gefunden, nie über Teile der Entity-ID.

## Entscheidungen nach dem Builder-Check (meine, zur Prüfung)

- **Tracker-Id:** Die Entity-ID trägt die Tracker-Id kleingeschrieben (`EntityIds`). Der Abgleich mit `TractivePet.trackerId` läuft deshalb ohne Beachtung der Groß-/Kleinschreibung. Sonst stünden Akku und Spaziergang still auf „–“.
- **Mülltonne:** Die Entität versorgt weiterhin nur die Bubble. Das Feld kommt komplett aus `/upcoming`.
- **Haustür:** `smartlockId` ist der numerische Teil von `lock.nuki_<id>` (heute `lock.nuki_18241688572`). Ist er nicht numerisch, gibt es keinen Akku und keinen Knopf.
- **Takt:** Die zusätzlichen Abrufe hängen am bestehenden 30-s-Refresh der Seite und laufen nur bei offenem Feld. Es gibt keinen eigenen Timer. Das Kamerabild lädt nur beim Öffnen und nach einem erfolgreichen Schnappschuss neu, nicht im Takt, damit es nicht flackert.
- **Kamera erreichbar:** Maßgeblich ist die Kameraliste (`/v1/blink/cameras`), nicht die Entität `…_armed`. Steht die Kamera in der Liste, gibt es Schalter und Schnappschuss.
- **Frostschutz:** `ThermostatMode` kennt `FROST_PROTECTION` im Frontend-Modell und im `ThermostatService` des Backends. Der Knopf ist also baubar.
- **Hervorhebung:** Farb- und Farbtemperatur-Modus schließen sich aus. Die Farbtonregel gilt nur im Farbmodus, die Warmweiß-Regel (±150 K um 2700) nur im Farbtemperatur-Modus. Beide können nie gleichzeitig zutreffen.
- **`bubbleTarget`:** Die Bubbles tragen ihr Ziel über `house-bubbles.util`. Der Plan prüft dort, ob `bubbleTarget` nach dem Umbau noch einen Nutzer hat, und entfernt es nur dann.

## Dem Bauenden überlassen

- Layout und Styles im Feld (lumina-Tokens, keine hart kodierten Farben außer den Farbfeldern selbst)
- Aufteilung auf Dateien
- Benennung
- die genaue Form der erweiterten `LightInfo`

## Bekannte Grenzen

- Farbe und Farbtemperatur sind am echten Gerät nie geschrieben worden. Der Tapo-Schreibpfad ist laut CLAUDE.md unverifiziert, der erste Realtest ist dieser.
- Die Lampenfarbe ist der in der DB gespeicherte Wert. Eine Änderung in der Hersteller-App sieht das Feld erst nach dem nächsten Scan.
- `lastMotionAt` lebt nur im Speicher des Backends und fehlt nach jedem Neustart.
