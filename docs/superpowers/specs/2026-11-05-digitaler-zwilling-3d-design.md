# Digitaler Zwilling: 3D-Hausansicht mit Info-Bubbles

Stand: 2026-11-05 · Status: zur Freigabe

## Absicht und Warum

Der Household Manager soll ein **Klon des echten Hauses** werden. Langfristig ersetzt eine 3D-Ansicht des Hauses das heutige Dashboard als Startseite: Man sieht das Haus, in jedem Raum steht, was die Sensoren dort gerade erfassen, und ein Tipp auf ein Ding führt zur passenden Detailseite (Temperaturfühler im Wohnzimmer ⇒ Verlauf und Heizungseinstellung; Sprechblase an der Mülltonne vor dem Haus ⇒ „Morgen Hausmüll“).

Vorbild im Look ist **neonplan3d** (Home-Assistant-Erweiterung, Three.js + Lit, MIT): dunkel, leuchtend, frei dreh- und zoombar. Übernommen werden **Ideen, kein Code** — neonplan3d hängt komplett an Home Assistant (Räume = HA-Areas, Daten im HA-Speicher), HA ist seit 2026-10-30 abgeschaltet, und unser Frontend ist Angular.

## Ziele der ersten Version

1. **3D-Ansicht des Hauses** (Keller, Erdgeschoss, Obergeschoss, dazu Garten/Terrasse/Bereich vor dem Haus) im Neon-Look, mit Maus und Touch dreh- und zoombar.
2. **Info-Bubbles** im Raum, antippbar.

Das ist ausdrücklich die Auswahl des Nutzers: „die 3D-Ansicht und die Infos in kleinen Bubbles, die man anklicken kann“.

### Bedienung (vom Nutzer so entschieden)

- **Überblick:** je Raum **eine** Bubble mit den wichtigsten Werten.
- **Tipp auf einen Raum** (auf die Fläche, nicht auf die Bubble): Die Kamera fliegt **animiert** heran, der Raum füllt die Ansicht, und es erscheinen **mehr Bubbles** — je Gerät/Sensor im Raum eine.
- **Tipp auf eine Bubble:** **Sprung zur bestehenden Seite** (kein Overlay-Fenster über dem 3D-Haus).
- **Etagen: Variante C** — das ganze Haus ist sichtbar; die gewählte Etage voll, die Etagen **darüber** nur als durchsichtiger Glas-Umriss; eine Etagenwahl wechselt die Etage.
- Zurück aus der Raumansicht in den Überblick muss offensichtlich sein (z. B. Zurück-Knopf und Tipp ins Leere).

### Inhalt der Bubbles (mein Vorschlag, vom Nutzer bestätigt)

- **Raum-Bubble (Überblick):** Ist-Temperatur und Feuchte des Raums, dazu kleine Symbole nur, wenn etwas los ist: Fenster/Tür offen, Heizung heizt, Gerät läuft, Bewegung.
  - **„Gerät läuft“ kommt im Überblick nur aus den Helfern** `input_boolean.manual_waschmaschine_laeuft|_fertig` und `…_spuelmaschine_laeuft|_fertig`, **nicht aus Wattwerten.** *(meine Entscheidung)* Kühlschrank, Standby-Geräte, PC und Balkonkraftwerke ziehen bzw. liefern praktisch immer Leistung; ein Watt-Symbol wäre dauerhaft an und verletzte Anti-Ziel 2. Die Wattwerte stehen nur in den Geräte-Bubbles der Raumansicht.
- **Geräte-Bubbles (Raumansicht):** je Thermostat (Ist/Soll, heizt), Fühler (Temperatur/Feuchte/Luftqualität), Kontakt (offen/zu), Bewegungsmelder, Taster (letzter Druck), Schalter/Steckdose (an/aus, Watt).
- **Ding-Bubbles außerhalb der Räume:** Mülltonne vor dem Haus (nächste Abfuhr, prominent am Vortag), Haustür (Nuki-Zustand, Türkontakt), Toni (zu Hause / unterwegs), Garten (Außentemperatur).

## Nicht-Ziele (erste Version)

- **Noch nicht die Startseite.** Erst eine eigene Seite; das Dashboard bleibt Startseite, bis sich die Ansicht bewährt hat. *(meine Entscheidung)*
- **Kein Schalten aus der 3D-Ansicht** (kein Tippen-zum-Licht-an wie bei neonplan3d). Bubbles navigieren nur.
- Kein Grundriss-Editor in der App, kein Import (Sweet Home 3D, LiDAR …).
- Keine Möbel, keine realistischen Texturen, keine exakten Maße, keine Wandstärken.
- Kein helles Design. *(meine Entscheidung: Neon wirkt im Hellen nicht; die Seite bleibt dunkel, auch wenn das Dashboard hell eingestellt ist)*
- Keine Heatmap, kein „Gehen durchs Haus“, keine Lichtfarben-Simulation (spätere Ausbaustufen, Ideen aus neonplan3d).
- Geräte **ohne bekannten Raum** (Router, Wasserpumpe, Ladestation, Klimagerät, Lichterkette, Staubsauger-Ladegerät, Fire-Tablet) erscheinen nicht. *(meine Entscheidung)*

## Anti-Ziele (wäre ein Fehlschlag, auch wenn es technisch läuft)

Vom Nutzer bestätigt:

1. **Ruckeln auf dem Wandtablet** (Lenovo Tab M11, Android-WebView). Die Ansicht muss dort flüssig drehen, zoomen und animieren.
2. **Überladen:** zu viele Bubbles. Im Überblick gilt strikt *eine Bubble je Raum*; Symbole nur bei Abweichung vom Ruhezustand.
3. **Veraltete Werte, die aktuell aussehen.** Alte, `unavailable` oder `unknown` Werte müssen sichtbar anders aussehen (abgedunkelt/„–“), nie als frischer Wert.

## Grundriss (mit dem Nutzer abgestimmt)

Draufsicht, **oben = Rückseite (Garten/Terrasse), unten = Straßenseite (Haustür)**, links/rechts wie gezeichnet. Die Skizze liegt in `.superpowers/brainstorm/1649-1793868112/content/grundriss-v6.html` (nicht eingecheckt). Raster in Skizzen-Einheiten, Haus 400 × 350 Einheiten (x 20–420, y 90–440):

**Erdgeschoss**

| Raum | x | y | Bemerkung |
|---|---|---|---|
| Esszimmer | 20–210 | 90–220 | links hinten |
| Küche | 20–210 | 220–350 | links Mitte |
| Laufband-Raum | 20–210 | 350–440 | links vorne, unter der Küche |
| Wohnzimmer | 210–420 | 90–250 | rechts hinten, Terrassentür zur Terrasse |
| Flur | 210–420 | 250–440 | rechts vorne, Treppe, Haustür in der Vorderwand |
| Gäste-WC | 210–290 | 370–440 | Ecke unten links **im** Flur |

Außen: Garten über die ganze Rückseite, Terrasse hinter dem Wohnzimmer, „vor dem Haus“ mit Mülltonnen an der Straßenseite.

**Obergeschoss**

| Raum | x | y |
|---|---|---|
| Schlafzimmer | 20–420 | 90–220 (ganze Rückseite, **ein** Raum) |
| Büro | 20–210 | 220–340 |
| Bad | 20–210 | 340–440 |
| Treppenhaus | 210–420 | 220–440 (über dem Flur) |

**Keller**

| Raum | x | y |
|---|---|---|
| Vorratskeller | 20–210 | 90–220 |
| Waschkeller | 20–210 | 220–440 |
| Pain Cave | 210–420 | 90–250 |
| Kellereingang | 210–420 | 250–440 (Treppe, unter dem Flur) |

**Maße sind geschätzt.** *(meine Entscheidung)* Maßstab: 40 Einheiten = 1 m (Haus ≈ 10 × 8,75 m), Geschosshöhe ≈ 2,6 m. Der Grundriss liegt als **Datendatei im Frontend** (z. B. `frontend/src/app/shared/house-plan.ts` oder JSON unter `assets/`), damit sich Maße später durch Zahlenänderung korrigieren lassen. Jeder Raum hat eine feste Kennung (`wohnzimmer`, `kueche`, …), Etage, Rechteck(e) und optional Türen/Fenster als Wandmarker. Ein Raum darf aus mehreren Rechtecken bestehen bzw. andere ausschneiden (Gäste-WC im Flur).

**Offen, nicht blockierend:** Lage des Balkons (es gibt einen Dirigera-Kontakt „Balkontür“). Bis zur Klärung wird der Kontakt keinem Raum zugeordnet.

## Zuordnung Gerät → Raum

Einmalig **von Hand** als Datei im Frontend gepflegt (Entity-ID → Raum-Kennung, optional Position im Raum) — **explizite Zuordnung, kein Namensabgleich** (Projektregel, siehe Raumfühler je Thermostat: Namen sind frei und ändern sich). Ausgangsliste, aus den Live-Entitäten am 2026-11-05 abgeleitet und vom Nutzer bestätigt (auch die zunächst aus dem Gerätenamen geratenen Einträge und „Pain Cave“ trotz der Id `…_buero_…`):

| Raum | Entitäten (Auszug, Entity-IDs siehe `flow_list_entities`) |
|---|---|
| Wohnzimmer | Thermostate 3 (Fenster) und 8, Alexa-Monitor `…gaj2300425330047_*`, Blink-Kamera 511671, Fernseher, Stehlampe, Stern, Couch-Lichterkette, Terrassentür-Kontakt |
| Esszimmer | Thermostat 2, Dirigera `…dcc8d385…` (Temp/Feuchte), Kasa „Esszimmer“ |
| Küche | Thermostat 4, Dirigera `…4a8044b0…`, Motion Küche, Kaffeemaschine, Kühlschrank, Spülmaschine (+ Helfer läuft/fertig), Küchenzeile |
| Flur | Thermostat 6, Dirigera `…f2af71e5…`, Motion Flur, Tapo „Flur“, Eingangstür-Kontakt, Nuki-Schloss, Blink Frontdoor, Gesichtserkennung |
| Laufband-Raum | Aqara „Laufband“, Tapo „Laufband“ |
| Schlafzimmer | Thermostat 5, Alexa-Monitor `…gaj230043055005a_*`, Licht Schlafzimmer, Fernseher Schlafzimmer |
| Büro | Thermostat 7, Aqara Büro, Motion Büro, Taster Büro (Dirigera oben/unten, Zigbee), PC Büro, Desktop Light, Drucker, Lichtstreifen Büro |
| Bad | Thermostat 1, Aqara Badezimmer, Fenster Badezimmer (Zigbee) und Badezimmer-Kontakt (Dirigera) |
| Treppenhaus | Dirigera `…bffb9dee…`, Motion Treppenhaus, Taster Treppenhaus (Dirigera, Zigbee), Kasa „Treppenhaus“ |
| Kellereingang | Aqara „Keller“, Motion Keller |
| Pain Cave | Zigbee „Pain Cave“ (`sensor.zigbee_temperatur_buero_*`, derzeit `unavailable`) |
| Waschkeller | Waschmaschine (+ Helfer läuft/fertig), Sensor Gas Kontakt |
| Garten | Aqara Garten, Airrohr, Balkonkraftwerke |
| Vor dem Haus | `sensor.waste_calendar_next_collection` (Mülltonne) |

Raumtemperatur in der Raum-Bubble: der Raumfühler (Dirigera/Aqara/Alexa), nicht der Heizkörperfühler des Thermostats; fehlt ein Raumfühler, das Thermostat. *(meine Entscheidung, deckt sich mit „Raumfühler je Thermostat“)*

## Navigationsziele der Bubbles

Sprung auf **bestehende** Seiten. Im Tablet-Modus (`ViewModeService.isTabletView()`) die Tablet-Variante, sonst die Website-Variante, sofern es beide gibt:

| Bubble | Ziel |
|---|---|
| Temperatur/Feuchte-Fühler | `/tablet/temperatures` bzw. `/temperatures` |
| Thermostat | `/tablet/heating` (Website: `/admin/heating` nur für ADMIN, sonst `/tablet/heating`) |
| Luftqualität (Alexa-Monitor, Airrohr) | `/tablet/air-quality` bzw. `/air-quality` |
| Mülltonne | `/waste-collection` |
| Kamera | `/tablet/cameras` bzw. `/cameras` |
| Toni | `/tablet/toni` bzw. `/pets` |
| Steckdose/Schalter/Lampe | `/devices` |
| Kontakte, Bewegung, Taster, Nuki | `/entities` bzw. Dashboard (Builder entscheidet; es gibt keine eigene Seite) |

**Bekannte Grenze:** Die bestehenden Seiten kennen meist keinen Deep Link auf einen einzelnen Sensor („Verlauf genau dieses Fühlers“). In v1 landet man auf der Seite, nicht beim Sensor. Deep Links (Query-Parameter) sind eine Folgestufe. Jede Zielseite im Tablet-Modus hat ihren Zurück-Weg über die Tablet-Shell; Seiten ohne Shell (z. B. `/waste-collection`, `/devices`) sind im Tablet-Modus eine Falle ohne Rückweg (siehe CLAUDE.md „Tablet-Ansichten“) — der Builder muss das lösen (Ziel ändern oder Zurück-Knopf ergänzen).

## Randbedingungen

- **Frontend-only.** Angular 19 standalone, eigene Route (Vorschlag `/tablet/house` als Eintrag in `TABLET_VIEWS` und Website-Navigation unter „Smart Home“). Separate HTML/TS/SCSS-Dateien.
- **Rendering mit Three.js** (neue Abhängigkeit, noch nicht im Projekt), gekapselt hinter einer eigenen Komponente/einem Service — wie `@foblex/flow` im Flow-Editor. Lazy geladen, damit das Bundle der übrigen Seiten nicht wächst. Bubbles als HTML-Overlays (CSS2DRenderer o. ä.), nicht als Texturen, damit Text scharf und antippbar bleibt.
- **Daten** aus der bestehenden Entity-State-Schicht (`EntityStateService`, `GET /v1/entities`), Refresh ≈ 30 s wie das Dashboard; nur der Erstabruf meldet Fehler, ein fehlgeschlagener Refresh behält die letzten Werte (Muster aller Tablet-Ansichten). Keine Backend-Änderung, keine Migration, keine neue Security-Zeile (Lesen ist KIOSK über `GET /v1/**`).
- **Veraltung:** Frische über `lastUpdated` beurteilen; Schwellen aus den vorhandenen Utils wiederverwenden (`isReadingStale` 60 min für Temperaturen, Zigbee-Schwellen aus `zigbee.component.ts`) statt neue zu erfinden. `unavailable`/`unknown` nie als Wert anzeigen.
- **Wandtablet-Leistung:** Szene klein halten (wenige Meshes, keine Schatten, kein Post-Processing außer ggf. günstigem Glow), Pixelratio begrenzen. **Rendern nur bei Bedarf** (Interaktion, Kamerafahrt, Datenänderung), kein Dauer-Loop. *(meine Entscheidung)* Damit braucht es keine Soft-Off-Erkennung: die Tablet-App legt im Soft-Off nur ein schwarzes Overlay über die WebView, die Seite erfährt davon nichts — ein Neuzeichnen je 30-s-Refresh ist akzeptiert, die Tablet-App bleibt unverändert. Ziel: **≥ 30 fps beim Drehen und Zoomen auf dem Lenovo Tab M11.** *(meine Entscheidung)*
- **Reihenfolge: erst ein Durchstich auf dem echten Tablet.** *(meine Entscheidung)* Three.js lazy geladen, eine Etage, ein paar Bubbles, eine Kamerafahrt — vom Nutzer auf dem Wandtablet geprüft, bevor Bubble-Logik, Zuordnung und Optik ausgebaut werden. Ruckelt der Durchstich, wird die Optik reduziert (kein Glow, weniger Overlays), bevor weitergebaut wird.
- **Höhenkette:** Die Seite steckt in `app-tablet-shell`; die Flex-Kette bis zum Canvas muss geschlossen sein (siehe CLAUDE.md „Tablet-Ansichten“, Höhenketten-Test), und der Renderer reagiert per `ResizeObserver`.
- **Styles:** lumina-Tokens für Text/Karten; Neon-Farben eigene Tokens in der Komponente. Kein Kind-Komponenten-Styling über `lumina`-Klassen (Kapselung).
- **Kein Internet nötig** (Wandtablet): keine CDN-Assets, Schriften/Icons lokal.

## Dem Builder überlassen

- Genaue Neon-Optik (Farben, Glow, Glas-Umriss), Kameraperspektive und Animationsdauer.
- Wie die Etagenwahl aussieht und welche Etage beim Öffnen gewählt ist (Vorschlag: EG).
- Bubble-Gestaltung und Positionierung im Raum, Kollisionsvermeidung.
- Ob die Raum-Fläche, die Raum-Bubble oder beides den Raum-Zoom auslöst (Bubble ⇒ Seite ist gesetzt; für die Raum-Bubble im Überblick muss eine eindeutige Regel her).
- Struktur der Datendateien (Grundriss, Zuordnung) und Tests (pure Utils für Zuordnung, Bubble-Inhalt, Veraltung; Komponententest für Navigation und Höhenkette).

## Spätere Ausbaustufen (nicht jetzt)

Startseite statt Dashboard · Schalten per Tipp · Lichter leuchten in echter Farbe, Heizkörper glühen, Warnräume pulsieren · Heatmap · Deep Links auf einzelne Sensoren · Balkon · Geräte ohne Raum zuordnen · helles Design · echte Maße.
