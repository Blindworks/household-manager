# Digitaler Zwilling: Großgeräte als Körper im Raum

Stand: 2026-11-12. Baut auf der 3D-Hausansicht `/tablet/house` auf (Spec `2026-11-05-digitaler-zwilling-3d-design.md`, Geräteplätze `2026-11-06-zwilling-grundriss-und-geraeteplaetze-design.md`, Fenster und Türen `2026-11-11-zwilling-fenster-und-tueren-design.md`).

## Absicht

Heute ist jedes Gerät in der Szene nur eine Bubble mit Text, dazu ein kleiner Effekt (blauer Punkt für „an“, türkiser Bogen für eine laufende Maschine). Der Nutzer will, dass das Haus wie ein Abbild des echten Hauses aussieht. Nach den Fenstern und Türen sollen deshalb die Großgeräte als Körper im Raum stehen: man sieht auf einen Blick, wo die Waschmaschine steht und ob sie läuft.

## Ziele

- Neun Großgeräte (acht Arten) stehen als einfache 3D-Körper in der Szene: Waschmaschine, Spülmaschine, Kühlschrank, Fernseher (Wohnzimmer), Fernseher (Schlafzimmer), PC, Drucker, Laufband, Kaffeemaschine — also die Einträge dieser Labels in `house-devices.ts`.
- Jeder Körper zeigt, ob das Gerät gerade arbeitet: dann wird seine Kontur türkis und sein Merkmal leuchtet.
- Kein Pflegeaufwand: die Ausrichtung ergibt sich aus dem Grundriss.

## Nicht-Ziele

- Keine Lampen als Körper (bleiben Lichtpunkt mit Bodenschein).
- Keine Klimafühler, Thermostate, Kontakte, Bewegungsmelder, Taster, Kamera, Schloss, Tier, Mülltonne als Körper.
- Kein Antippen des Körpers; keine neue Interaktion.
- Keine eigene Drehung pro Gerät, keine Größenwahl, keine Datenbankänderung, keine Backend-Änderung.
- Keine echten 3D-Modelle (glTF o. Ä.), nur Grundformen aus Three.js.

## Anti-Ziele (wäre ein Fehlschlag, auch wenn es technisch läuft)

- Ein Fernseher im Standby leuchtet, als liefe er.
- Ein Körper verdeckt die Bubble oder das Detail-Feld so, dass man sie nicht mehr antippen kann.
- Der Körper zeigt einen Zustand, den Bubble/Detail-Feld anders anzeigen (Körper und Bubble laufen auseinander).
- Ein Körper steht mit der Vorderseite zur Wand.

## Aussehen (Nutzerentscheidung: Stil B)

Halbtransparenter Körper wie Glas und Türblatt der Fenster und Türen: leicht gefüllte Flächen (`rgba(143,211,255,0.10)`), Deckel etwas heller, heller Rand (`#b9e6ff`). Jedes Gerät hat ein Merkmal auf der Vorderseite:

| Gerät | Form | Merkmal |
|---|---|---|
| Waschmaschine | Quader | Bullauge (Kreis) |
| Spülmaschine | Quader | waagerechte Bedienleiste oben |
| Kühlschrank | hoher Quader | Türfuge (Linie Gefrier/Kühl) |
| Fernseher | flache Platte an der Wand | Bildschirmfläche |
| PC | schmaler Tower | senkrechte Leuchtleiste vorn |
| Drucker | flacher Quader | Papierschlitz |
| Laufband | lange flache Lauffläche mit Bedienpult am Kopfende | Pult-Display |
| Kaffeemaschine | kleiner Quader | Tropfschale/Auslauf |

**An/läuft:** Kontur türkis (`#5ff2d6`), das Merkmal leuchtet (Bullauge/Display/Leiste gefüllt türkis, Bildschirm blau-hell `rgba(140,170,255,0.55)`).
**Fertig (nur Waschmaschine und Spülmaschine):** Merkmal gelb, Kontur bleibt hell.
**Kühlschrank aus:** Körper rot (Kontur und Fläche in der Rot-Farbe der offenen Fenster, `#ff8f8f`) — ein abgeschalteter Kühlschrank ist ein Problem.
**Unbekannt** (`unavailable`/`unknown`, fehlende Entität): Ruhezustand, nie geraten.

Die exakten Maße der Merkmale und die Farbtöne darüber hinaus entscheidet der Bauende, passend zum Mockup (`.superpowers/brainstorm/…/geraete-stil.html`, Option B).

## Maße und Höhe (Entscheidung Claude, vom Nutzer abgenickt)

Breite × Tiefe × Höhe, Unterkante über dem Etagenboden:

| Gerät | B × T × H | Unterkante |
|---|---|---|
| Waschmaschine | 0,60 × 0,60 × 0,85 m | 0 |
| Spülmaschine | 0,60 × 0,60 × 0,85 m | 0 |
| Kühlschrank | 0,60 × 0,65 × 1,80 m | 0 |
| Fernseher | 1,20 × 0,06 × 0,70 m | Mitte auf 1,20 m |
| PC | 0,20 × 0,45 × 0,45 m | 0 |
| Drucker | 0,45 × 0,35 × 0,25 m | 0,90 m |
| Laufband | 0,80 × 1,80 × 0,15 m Lauffläche, Pult 1,30 m hoch am Kopfende | 0 |
| Kaffeemaschine | 0,30 × 0,40 × 0,40 m | 0,90 m |

Breite liegt parallel zur Wand, Tiefe zeigt in den Raum. Beim Laufband ist die lange Seite die Tiefe (es steht mit dem Kopfende zur Wand).

## Ort und Ausrichtung

- **Ort:** der Platz des Geräts aus der bestehenden Platzlogik (`house-placement.util.ts`: gespeicherter Platz, sonst Rasterplatz), also derselbe x/z-Punkt wie Bubble und Effekt. Der Körper steht mit seinem Grundriss-Mittelpunkt genau dort; er wird **nicht** an die Wand geschoben (Entscheidung Claude, abgenickt). Wer ihn an der Wand haben will, zieht den Punkt auf der Admin-Seite „Geräteplätze“ dorthin.
- **Ausnahme Fernseher (Nutzerentscheidung):** ein Fernseher rückt mit dem Rücken an die nächste Wand (Abstand Rückseite–Wand ≈ 0), auch ohne gespeicherten Platz; er wird dazu nur senkrecht zur Wand verschoben. Seine Bubble bleibt auf dem Platz aus der Platzlogik. Alle anderen Körper stehen genau auf ihrem Platz.
- **Überlappung (Entscheidung Claude):** keine Kollisionslogik. Körper dürfen sich untereinander, mit Fenstern/Türen oder dem Raumrand überschneiden; das Raster rückt nicht nach. Abhilfe ist das Setzen der Plätze auf der Admin-Seite.
- **Ausrichtung (Nutzerentscheidung: automatisch):** der Körper dreht sich mit dem Rücken zur nächsten Wand seines eigenen Bereichs. „Wand“ sind die Kanten der Rechtecke dieses Bereichs (Raum oder Außenbereich); die nächste ist die mit dem kleinsten senkrechten Abstand zum Punkt, bei der der Lotfußpunkt auf der Kante liegt. Bei Gleichstand gewinnt eine feste Reihenfolge (Bauender wählt sie, muss deterministisch sein). Die Vorderseite zeigt von dieser Wand weg. Nur 90°-Schritte (die Wände sind achsparallel).
- **Die Regel ist eine reine Funktion** in `shared/house/` (Vorschlag: neue Datei `house-appliance-bodies.util.ts`), die aus Gerät + Positionen die Form, Maße, Lage und Drehung ableitet — wie `openingShape` für Fenster/Türen. Renderer und Tests fragen dort.
- Liegt ein Platz außerhalb seines Bereichs (ungültig), gilt wie überall der Rasterplatz.

## Zustand (Nutzerentscheidung: nach Verbrauch)

Eine einzige Regel in derselben Util-Datei, Zustände `idle | active | finished | alarm`:

- **Waschmaschine, Spülmaschine** (`kind: 'appliance'`): `active`, wenn `runningEntityId` `on` ist; sonst `finished`, wenn `finishedEntityId` `on` ist; sonst `idle`. Watt spielt hier keine Rolle (Regel „Gerät läuft kommt nur aus den Helfern“).
- **Fernseher, PC, Drucker, Laufband, Kaffeemaschine** (`kind: 'switch'` mit `powerEntityId`): `active`, wenn der Schalter `on` ist **und** der Leistungswert eine Zahl > **10 W** ist **und** die Messung (`lastUpdated`) jünger als **5 min** ist (dieselbe Frische wie die Watt-Zeile der Bubble, „Switch Power Display“); sonst `idle`. Alle sechs Schalter-Geräte mit Körper haben heute einen `powerEntityId` (Meross-Steckdosen immer, das Laufband per `plug(…, true)`); ein künftiges Gerät ohne Leistungssensor ist immer `idle` (Entscheidung Claude — „an“ ohne Watt wäre geraten). Die 10-W-Schwelle ist Claudes Wahl (Standby liegt bei diesen Geräten darunter) und steht als eine Konstante in der Util-Datei.
- **Kühlschrank:** `alarm`, wenn der Schalter genau `off` ist; sonst `idle` (er leuchtet nie). `unavailable`/`unknown` ⇒ `idle`, kein Alarm (nie geraten — ein toter Stecker-Sensor ist kein Beweis für einen ausgeschalteten Kühlschrank).

Zuordnung „welches Gerät ist welcher Körper“: über das Label des Eintrags in `house-devices.ts` ist zerbrechlich; der Bauende markiert die neun Einträge stattdessen ausdrücklich mit einer Körperart (z. B. Feld `body: 'washer' | 'dishwasher' | 'fridge' | 'tv' | 'pc' | 'printer' | 'treadmill' | 'coffee'`, analog zu `lamp: true`). Ein neues Großgerät braucht dann nur dieses Feld.

## Zusammenspiel mit dem Bestehenden

- **Bubble bleibt** (Nutzerentscheidung): Geräte-Bubbles der Raumansicht bleiben unverändert über dem Körper, Antippen öffnet das Detail-Feld wie bisher. Der Körper ist nicht antippbar; ein Tipp, der auf ihm landet, verhält sich wie ein Tipp in die Szene heute.
- **Effekte:** für Geräte mit Körper entfallen der blaue „an“-Punkt (`switch`) und der türkise Bogen (laufende Maschine) — der Körper zeigt das selbst (Entscheidung Claude, abgenickt). Alle anderen Effekte bleiben.
- **Sichtbarkeit:** Körper der gewählten Etage, in Überblick und Raumansicht gleich (wie Fenster/Türen und Effekte). Nur die Etage, nie andere.
- **Rendern nur bei Bedarf** bleibt: Körper sind statisch, sie animieren nicht (auch nicht beim Raum-Antippen). Neu gezeichnet wird bei Zustandswechsel im 30-s-Takt und bei geänderten Plätzen. Materialien/Geometrien je Art geteilt, `dispose` räumt sie mit auf.
- Three.js bleibt ausschließlich in `pages/tablet-house/house-scene-renderer.ts`; die Komponente reicht die Körper-Beschreibungen per neuer Renderer-Methode (Muster `setOpenings`) weiter, der Fake-Renderer der Tests bekommt sie ebenfalls.
- Die Admin-Seite „Geräteplätze“ bleibt unverändert (keine Körper in der Draufsicht).

## Qualitätsanforderungen

- Unit-Tests für die Util: Zustandsregel aller vier Zustände je Geräteart inkl. Grenzfälle (genau 10 W ist nicht aktiv, Messung knapp unter 5 min frisch, ab 5 min nicht, `unavailable`, Schalter aus bei Watt > 10), Ausrichtung zu jeder der vier Wände, Gleichstand deterministisch, ungültiger Platz ⇒ Raster.
- Komponententest: Körper werden für die gewählte Etage an den Fake-Renderer gegeben; Gerät mit Körper erzeugt keinen Punkt/Bogen-Effekt mehr; Bubble bleibt da.
- Frontend-Testbasis: nur die drei bekannten Altfehler (AppComponent ×2, HeroComponent).
- Browser-Sichtprüfung der Szene im Dev-Server (Muster der vorigen Stufen).
- CLAUDE.md-Abschnitt „Digitaler Zwilling“ ergänzen.

## Dem Bauenden überlassen

Detailform der Merkmale, genaue Farbtöne außerhalb der oben genannten, Gleichstandsreihenfolge bei der Wandwahl, Name der Util-Datei und der Renderer-Methode, Aufteilung in Commits.
