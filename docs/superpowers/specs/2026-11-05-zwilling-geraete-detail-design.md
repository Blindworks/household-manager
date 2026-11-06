# Digitaler Zwilling: Geräte-Detail und Bedienung in der 3D-Ansicht

Stand: 2026-11-05 · Status: zur Freigabe · baut auf `2026-11-05-digitaler-zwilling-3d-design.md` auf (gebaut, gepusht be4d4acc)

## Absicht und Warum

In der 3D-Hausansicht (`/tablet/house`) führt ein Tipp auf eine Geräte-Bubble heute auf eine allgemeine Seite (z. B. alle Temperaturen). Der ursprüngliche Wunsch des Nutzers war konkreter: Tipp auf den Temperaturfühler im Wohnzimmer ⇒ **Verlauf dieses Fühlers und die Heizungseinstellung**. Nutzerentscheidung 2026-11-05: **kein Seitenwechsel mehr**, sondern die Kamera fährt an das Gerät heran und **in der 3D-Ansicht öffnet sich ein Detail-Feld** mit aktuellem Wert, Verlauf und — wo sinnvoll — Bedienung. „Für alles“ schaltbar, was schaltbar ist.

## Bedienung (vom Nutzer entschieden)

- **Tipp auf eine Geräte-Bubble in der Raumansicht** ⇒ Kamera fährt animiert näher an den Anker des Geräts (`deviceAnchors`), ein Detail-Feld legt sich über die Szene (Skizze: rechts, Szene bleibt links sichtbar). Kein `navigateByUrl` mehr für Geräte-Bubbles.
- **Schließen:** ✕ im Feld oder Tipp ins Leere ⇒ Feld zu, Kamera zurück in die Raumansicht (nicht in den Überblick).
- **Feldinhalt:** oben der aktuelle Wert groß (mit Alter des Werts), darunter das Verlaufsdiagramm des einen Geräts, darunter/daneben die Bedienung.
- **Bedienung:**
  - **Thermostat:** Sollwert mit − / + ändern (vom Nutzer gewünscht).
  - **Steckdosen und Lampen:** An/Aus im Feld (vom Nutzer „für alles“).
  - **Dimmbare Lampen:** zusätzlich ein Helligkeitsregler (vom Nutzer gewünscht).
- **Welche Bubbles das Feld öffnen** *(meine Entscheidung)*: alle Geräte-Bubbles der Raumansicht — Fühler, Thermostat, Steckdose/Lampe, Haushaltsgerät **und** Kontakt/Bewegung/Taster (diese als reines Lese-Feld ohne Verlauf und ohne Navigation). Für keine Geräte-Bubble der Raumansicht gibt es noch einen Seitensprung.
- Außen-Bubbles (Mülltonne, Haustür, Toni, Garten) und Raum-Bubbles behalten ihr heutiges Verhalten *(meine Entscheidung; Mülltonne/Toni/Haustür navigieren weiter, Garten öffnet als Fühler ebenfalls das Detail-Feld, weil er ein Fühler ist)*.

## Inhalt je Geräteart (mein Vorschlag, vom Nutzer bestätigt)

| Art | Aktueller Wert | Verlauf | Bedienung |
|---|---|---|---|
| Fühler (`climate`) | Temperatur, Feuchte (und IAQ bei Alexa-Monitoren), Alter | Temperatur (+ Feuchte) 24 h / 7 Tage | – |
| Thermostat | Ist, Soll, heizt ja/nein | Ist und Soll (Soll gestrichelt), heizt-Bereiche optional, 24 h / 7 Tage | Soll − / + |
| Steckdose/Lampe (`switch`) | an/aus, Leistung jetzt (nur wenn an und frisch < 5 min, bestehende Regel) | Leistungsverlauf 24 h / 7 Tage, nur wenn die Steckdose misst | An/Aus; bei dimmbaren Lampen Helligkeit |
| Haushaltsgerät (`appliance`, Wasch-/Spülmaschine) | läuft/fertig, Leistung | Leistungsverlauf | An/Aus der Steckdose (mit Ausschalt-Schutz, s. u.) |
| Kontakt, Bewegung, Taster | Zustand und seit wann | keiner (keine Historie im System) | – |

Datenquellen existieren alle schon (keine neue Historie):
- Fühler: `GET /v1/temperatures/series?sensorId=…&range=DAY|WEEK` — die `sensorId` (`zigbee:12`, `alexa:…`, `dirigera:3`, …) ist **nicht** die Entity-ID; die Zuordnung Entity-ID → sensorId liefert `GET /v1/temperatures/current` bzw. `GET /v1/temperatures` (beide tragen je Sensor die `entityId`). Explizite Zuordnung über diese Antwort, kein Namensabgleich. `/current` lässt nicht erreichbare Fühler weg (Dirigera `reachable=false`/`known_to_hub=false`); für die Zuordnung deshalb `GET /v1/temperatures` (Serienliste) nehmen oder beide Antworten vereinen. Findet sich kein sensorId, zeigt das Feld den aktuellen Wert ohne Verlauf mit dem Hinweis „Kein Verlauf verfügbar“ *(meine Entscheidung)*.
- Thermostat-Feld zeigt zusätzlich den **Raumwert**, wenn dem Thermostat ein Raumfühler zugeordnet ist (`roomSensorId`, bestehende Util `roomReading` in `shared/thermostat-view.util.ts`) *(meine Entscheidung — gleiche Anzeige wie die Thermostat-Kachel)*.
- Thermostat: `GET /v1/thermostats/{id}/history?range=DAY|WEEK` (id = `<n>` aus `sensor.thermostat_<n>_…`).
- Leistung: `GET /v1/power-consumers/{entityId}/history?range=DAY|WEEK` (entityId des `sensor.…_power`). Achtung: `PowerConsumerQueryService` schließt einige Quellen aus — Geräte ohne Treffer zeigen dann keinen Verlauf statt eines Fehlers.

## Schalten und Schutz

- **Sollwert:** `PUT /v1/thermostats/{id}/target` (KIOSK erlaubt), gleiche Validierung wie die Tablet-Heizungsansicht: 5,0–30,0 °C in 0,5-Schritten (`ThermostatTargetValidator` ist die Autorität; Frontend-Grenzen nur Bedienhilfe). Eingaben werden kurz gesammelt (mehrere Tipps auf + ergeben **einen** Schreibaufruf) *(meine Entscheidung, Wert der Pause dem Builder überlassen)*.
- **An/Aus:** über den bestehenden Schalt-Weg (`POST /v1/switches/{entityId}/toggle`, KIOSK erlaubt). **Vor jedem Schalten** wird der Zustand frisch gelesen und nur geschaltet, wenn das Ergebnis der Absicht entspricht (der Endpunkt ist ein Toggle: alles außer `on` schaltet EIN; Regel `confirmToggle`). Ein Gerät in `unavailable` wird nicht geschaltet.
- **Ausschalt-Schutz:** Geräte mit `confirmRequired` (Kühlschrank, Router, …) fragen vor dem **Ausschalten** im Feld nach; Einschalten direkt. Vor dem Bestätigen erneut frisch lesen (wie Dashboard/Geräteseite). *(mein Vorschlag, vom Nutzer nicht widersprochen)*
- **Helligkeit:** `PUT /devices/{id}/light` (bestehender Pfad, `SmartDeviceService.setLightState`; Fähigkeit `BRIGHTNESS` aus der Geräte-Selbstauskunft entscheidet, ob der Regler erscheint). Der Regler sendet erst beim Loslassen (`change`), wie in der Geräteliste. `id` ist die Geräte-DB-Id, nicht die Entity-ID — die Zuordnung Entity-ID → Gerät läuft über `GET /api/devices` und `SmartDeviceEntityMapper.entityId(device)` als einzige Definition (ggf. liefert der Endpunkt die entityId mit; Builder entscheidet, wie die Zuordnung ins Frontend kommt, ohne zweite Definition).
- **Haushaltsgeräte (Wasch-/Spülmaschine):** Ausschalten fragt **immer** nach, solange der Helfer „…läuft“ `on` ist — unabhängig von `confirmRequired`; ein Tipp darf keinen laufenden Waschgang abbrechen *(meine Entscheidung, Anti-Ziel 4)*.
- **Helligkeit nur bei eingeschalteter Lampe:** bei ausgeschalteter Lampe ist der Regler gesperrt (sichtbar, aber nicht bedienbar), weil ein Light-Aufruf die Lampe einschaltet (Kasa: `on_off`+`ignore_default`) *(meine Entscheidung, Anti-Ziel 4)*.
- **Schaltweg:** `POST /v1/switches/{entityId}/toggle` erfasst alle schaltbaren Entitäten aller Quellen (`SwitchQueryService` filtert nach `SwitchableEntities.SWITCHABLE_DOMAINS`, nicht nach Quelle — Kasa/Tapo/Meross sind enthalten); geprüft 2026-11-05.
- **Nicht schaltbar:** Fenster/Bewegung/Taster (keine Aktoren) und das Türschloss (sicherheitsrelevant; das Wandtablet darf Nuki nur verriegeln — bleibt außerhalb dieses Features).
- **Fehler:** bleibt im Feld sichtbar („Gerät nicht erreichbar“ o. ä.), Schalter/Regler springen auf den tatsächlichen Zustand zurück (nächster Abruf). Kein 401 aus Gerätefehlern (Projektregel).

## Sicherheitsänderung (vom Nutzer entschieden)

`PUT /devices/*/light` wird für **KIOSK** freigegeben (heute nur MEMBER über `anyRequest`), damit der Regler auf dem Wandtablet funktioniert. Methodenspezifischer Matcher in `SecurityConfig` wie bei den anderen KIOSK-Schreibpfaden; `SecurityRulesTest` hält KIOSK-erlaubt fest; `PUT /devices/*/address` und andere `/devices`-Schreibpfade bleiben MEMBER (Test dafür). Konsequenz, vom Nutzer akzeptiert: jeder vor dem Tablet kann Lampen dimmen (wie er sie heute schon schalten kann). CLAUDE.md (TP-Link-Abschnitt „Beide neuen PUT-Endpunkte … fällt durch auf MEMBER“) ist entsprechend nachzuziehen.

## Ziele / Nicht-Ziele / Anti-Ziele

**Ziele:** Detail-Feld mit Wert, Verlauf, Bedienung für Fühler, Thermostat, Steckdose/Lampe (inkl. Helligkeit), Haushaltsgerät; Zustandsfeld für Kontakt/Bewegung/Taster; Kamerafahrt ans Gerät.

**Nicht-Ziele:** Farbe/Farbtemperatur von Lampen; Thermostat-Modus (Aus/Manuell) und Sammelaktion; Nuki; Blink-Kameras; neue Historien für Kontakte/Bewegung; Deep Links für die bestehenden Seiten.

**Anti-Ziele** (aus dem ersten Design, weiter gültig, plus neu):
1. Ruckeln auf dem Wandtablet — Diagramm darf die Szene nicht ausbremsen (Render-on-demand bleibt; Diagramm-Bibliothek ECharts nur im Lazy-Chunk).
2. Überladen — das Feld zeigt ein Gerät, nicht mehrere.
3. Veraltete Werte, die frisch aussehen — Alter des Werts sichtbar, veraltete Werte abgedunkelt (bestehende Regeln in `house-bubbles.util.ts`).
4. **Neu: versehentliches Schalten** — kein Schalten durch Antippen der Bubble, beim Drehen oder durch einen Hintergrund-Refresh bei offenem Feld; ein ausgeschaltet geglaubtes Gerät darf durch das Feld nie unbeabsichtigt eingeschaltet werden (Toggle-Falle).

## Randbedingungen

- Frontend wie bisher (Angular 19 standalone, eigene `.html/.scss`, lumina-Kapselung beachten — das Feld gehört in die Seite `tablet-house`, nicht in eine fremd gestylte Kind-Komponente; eine eigene Kind-Komponente mit eigenen Styles ist ok). Dunkel wie die Seite (`forceDark`).
- Diagramme mit **ECharts** (ngx-echarts, wie im Rest der App), nicht mit Three.js.
- Refresh: das offene Feld aktualisiert Wert und Zustand mit dem bestehenden 30-s-Takt; der Verlauf lädt beim Öffnen und beim Zeitraumwechsel (laufender Abruf wird beim Wechsel abbestellt, Muster Tablet-Verbrauch).
- Bedienelemente ≥ 44 px, Text ≥ 0,8 rem (wie die Bubbles).
- Audit: Schalten und Sollwert laufen über bestehende Endpunkte, die schon auditieren; für Helligkeit prüfen, ob `PUT /devices/{id}/light` auditiert — falls nicht, ist das ein bestehender Zustand, kein Teil dieses Features *(meine Entscheidung)*.

## Dem Builder überlassen

Feld-Layout im Detail (Position, Breite, Hochformat), Kameradistanz zum Gerät, Animationsdauer, Diagramm-Optik, Sammelpause für − / +, Darstellung „seit wann“, wie die Entity→Gerät-Zuordnung für die Helligkeit ins Frontend kommt (ohne zweite Definition), Tests.
