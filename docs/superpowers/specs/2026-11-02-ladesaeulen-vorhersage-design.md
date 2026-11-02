# Ladesäulen: Belegungshistorie und Vorhersage für Favoriten

Stand: 2026-11-02. Erweitert die Ladesäulen-Übersicht (`docs/superpowers/specs/2026-09-19-ladesaeulen-uebersicht-design.md`, Modul `backend/.../charging/`).

## Absicht

Heute zeigt die Ladesäulen-Übersicht nur den **Augenblick**: frei/belegt und seit wann. Die Frage, die man vor dem Losfahren eigentlich hat, beantwortet sie nicht: *Ist mein Favorit frei, wenn ich ankomme? Wann wird die belegte Säule frei? Wann lade ich am besten?*

Dafür zeichnet das System für die **Favoriten** jede Belegung je Ladepunkt dauerhaft auf und leitet daraus mit einfacher, nachvollziehbarer Statistik drei Vorhersagen ab. Angezeigt werden sie auf dem Wandtablet, abfragbar per Telegram-Bot und als Entitäts-Attribute für Flows.

## Ziele

1. **Belegungshistorie** je Ladepunkt der Favoriten: eine Zeile je abgeschlossener Belegung (Beginn, Ende).
2. **Drei Vorhersagen** je Favorit:
   - **Frei bei Ankunft:** Wahrscheinlichkeit, dass in 15 / 30 / 60 min mindestens ein Ladepunkt frei ist.
   - **Restdauer:** je belegtem Ladepunkt „frei ca. HH:mm, meist bis HH:mm“ (Median und 80-%-Quantil).
   - **Muster:** typische Auslastung je Wochentag × Stunde (Heatmap).
3. **Anzeige** auf `/tablet/charging` (Variante B, siehe unten), **Telegram-Werkzeug** für Fragen, **Attribute** an `sensor.charging_<stationId>_free`.

## Nicht-Ziele

- Keine Vorhersage für Stationen, die keine Favoriten sind (Umkreis).
- Keine Anzeige auf der Website `/charging`.
- Kein ML-Modell, keine neue Bibliothek für Statistik.
- Keine eigene Benachrichtigungslogik im Backend — Meldungen („Säule bald frei“) baut der Nutzer per Flow aus den Attributen.
- Keine getrennte Auswertung von Schulferien.
- Keine Historie für Stationen rückwirkend vor dem Deploy (die Quelle liefert keine).

## Anti-Ziele (das darf nicht passieren)

- **Zu optimistische Restdauer**, nach der man losfährt und vor belegten Säulen steht. Deshalb wird die Restdauer immer als Spanne mit „meist bis“ (80-%-Quantil) gezeigt, nie als einzelne Uhrzeit.
- **Erfundene Zahlen bei dünner Datenlage.** Ohne genug Daten steht „noch zu wenig Daten“, nie eine Prozentzahl aus drei Beobachtungen.
- **Datenlücken als Belegung.** Ein Ausfall der Quelle darf keine „9 Stunden belegt“-Zeile erzeugen, die die Restdauer-Statistik verdirbt.
- **Den bestehenden Poll stören.** Aufzeichnung und Vorhersage werfen nie in den Favoriten-Poll hinein; scheitert sie, laufen Entität und Anzeige wie heute weiter.

## Entscheidungen (vom Nutzer getroffen)

- Alle drei Vorhersagearten.
- Anzeige: Wandtablet und Telegram; Telegram als **Bot-Werkzeug und** als Entitäts-Attribute.
- Aufbewahrung der Historie: **ein Jahr**.
- Tablet-Layout **Variante B**: Liste wie heute mit einer kurzen Prognosezeile je Favorit; **Antippen eines Favoriten ersetzt die Karte** durch einen Detailbereich mit Restdauer je Ladepunkt, Wahrscheinlichkeiten 15/30/60 min und Wochen-Heatmap. (Entwurf im Brainstorming: links Detail, rechts Liste mit markiertem Favoriten.)
- **Feiertage in Hessen zählen als Sonntag** (in Statistik und Vorhersage).
- **Fehlerkultur:** Restdauer als Median + 80-%-Quantil („frei ca. 17:40, meist bis 18:00“); Wahrscheinlichkeiten unverfälscht aus der Statistik, ohne Sicherheitsabschlag.
- Methode: **einfache Statistik** (Häufigkeiten/Quantile), kein Modell.

## Wie es funktioniert

### Aufzeichnung

- Neue Tabelle (Name dem Builder überlassen, Vorschlag `charging_occupancy_history`): `id`, `station_id`, `charge_point_id`, `started_at` (Instant), `ended_at` (Instant), Index auf `(station_id, started_at)`. Liquibase, **je DDL ein Changeset** (MariaDB-Autocommit), kein FK auf Favoriten.
- Geschrieben wird beim Übergang **belegt → frei/außer Betrieb** im bestehenden `ChargingOccupancyTracker` (dort wird die laufende Zeile heute gelöscht): Beginn = `occupied_since` der laufenden Zeile, Ende = jetzt (bzw. `statusSince` des neuen Status, wenn vorhanden — genauer als der Pollzeitpunkt).
- **Nur mit bekanntem Beginn.** Zeilen mit `occupied_since = NULL` (Beginn unbekannt) werden nicht in die Historie übernommen.
- **Datenlücken:** *Meine Festlegung:* Lag zwischen dem letzten erfolgreichen Poll, der die Belegung sah, und dem Poll, der das Ende sah, mehr als eine Lücke (Vorschlag: > 10 min ohne erfolgreichen Abruf dieses Favoriten), wird die Belegung **verworfen** statt mit unscharfem Ende gespeichert. Ebenso nach einem Backend-Neustart mitten in einer Belegung, wenn der Tracker die Lücke nicht beurteilen kann — es sei denn, `statusSince` der Quelle liefert das echte Ende.
- **Belegt → außer Betrieb** ist kein normales Ende: *meine Festlegung:* die Belegung wird **verworfen** (Ende unklar — Defekt oder Abbruch), nicht als Historienzeile geschrieben.
- **Beobachtungszeit (Nenner):** *meine Festlegung:* eine zweite Tabelle (Vorschlag `charging_observation`) zählt je Station und Stunde (`station_id`, `hour_start` als Instant, `observed_minutes`, `out_of_service_point_minutes`), wie viele Minuten der Favorit erfolgreich abgefragt wurde, Upsert im Favoriten-Poll. Sie ist die einzige Quelle für „wann wurde beobachtet“: Heatmap-Nenner, Rate neuer Belegungen je freier Ladepunkt-Stunde und die Anlaufphase rechnen nur über beobachtete Zeit. Ausfälle, Pausen zwischen Entfernen und Neu-Favorisieren und Zeit vor dem ersten Favorisieren sind damit „keine Daten“, nie 0 % Auslastung. Gleiche Retention (365 Tage), eigenes Changeset.
- Die Aufzeichnung **wirft nie** in den Favoriten-Poll hinein (Muster `PowerHistoryRecorder`).
- Wird ein Favorit entfernt, **bleibt** seine Historie (er könnte wieder favorisiert werden); sie altert über die Retention heraus. *Meine Festlegung.*
- **Retention:** nächtlicher Job löscht Zeilen mit `ended_at` älter als 365 Tage, als `@Transactional @Modifying @Query`-Bulk-Delete (abgeleitete Delete-Methoden bringen keine Transaktion mit). Uhrzeit nicht zur vollen Stunde.

### Vorhersage (einfache Statistik)

Alle Uhrzeiten in der Zone des `Clock`-Beans (Europe/Berlin). Ein Tag gilt als Sonntag, wenn er Sonntag **oder gesetzlicher Feiertag in Hessen** ist (Neujahr, Karfreitag, Ostermontag, 1. Mai, Christi Himmelfahrt, Pfingstmontag, Fronleichnam, 3. Oktober, 1. und 2. Weihnachtstag; bewegliche Feiertage aus dem Osterdatum berechnet, keine Bibliothek). Diese Feiertagsregel steht an **einer** Stelle.

**Muster (Heatmap):**
- Auslastung je Wochentag (Mo–So, Feiertag = So) × Stunde = mittlerer Anteil belegter Ladepunkte der Station in diesem Zeitfenster, berechnet aus der Historie der **letzten 8 Wochen** (*meine Festlegung*; damit schlägt eine Veränderung am Standort nach rund zwei Monaten durch).
- Als Nenner zählt nur Zeit, in der Daten erhoben wurden (ab Erfassungsbeginn des Favoriten bzw. ohne bekannte Ausfallzeiten); ein noch nicht gesehenes Zeitfenster ist „keine Daten“, nicht 0 %.

**Restdauer je belegtem Ladepunkt:**
- Vergleichsmenge: frühere Belegungen **derselben Station**, deren Beginn in ähnlichem Zeitfenster lag (*meine Festlegung:* gleicher Tagestyp Werktag/Samstag/Sonntag, Beginn ±2 h) und die **mindestens so lange** dauerten wie die laufende bisher (bedingte Verteilung).
- Ausgabe: Median und 80-%-Quantil der Gesamtdauer → Uhrzeit „frei ca.“ und „meist bis“.
- Zu kleine Vergleichsmenge (*meine Festlegung:* < 10 Belegungen): schrittweise lockern (erst ohne Tagestyp, dann ohne Zeitfenster); reicht es dann noch nicht, keine Restdauer.

**Frei bei Ankunft (15/30/60 min):**
- Je belegtem Ladepunkt: Wahrscheinlichkeit, dass die laufende Belegung bis dahin endet (aus derselben bedingten Verteilung wie die Restdauer).
- Je freiem Ladepunkt: Wahrscheinlichkeit, dass er bis dahin **nicht** neu belegt wird (aus der Häufigkeit von Belegungsbeginnen in diesem Wochentag-/Stundenfenster).
- Ladepunkte **außer Betrieb** oder mit Status `UNKNOWN` zählen weder als „kann frei werden“ noch als „bleibt frei“ — sie fallen aus der Kombination heraus (Wahrscheinlichkeit 0 für diesen Punkt), und in der Heatmap aus dem Nenner. *Meine Festlegung* (lieber pessimistisch).
- **Beginn unbekannt** (`occupied_since = NULL`, „seit mind.“): *meine Festlegung:* `first_seen_occupied_at` dient als angenommener Beginn. Das verschiebt „frei ca.“ eher nach hinten (vorsichtig) und kommt selten vor, weil EnBW praktisch immer `state.updatedAt` liefert.
- Kombiniert zu „mindestens ein Ladepunkt frei“ unter der vereinfachenden Annahme unabhängiger Ladepunkte (bekannte Vereinfachung, steht im Code-Kommentar).
- Wahrscheinlichkeiten werden ohne Abschlag ausgegeben, gerundet auf ganze Prozent.

**Anlaufphase:** Vorhersagen erst, wenn für die Station mindestens **14 Tage** beobachtete Zeit vorliegen (Summe aus `charging_observation`, also auch über ein Entfernen und Neu-Favorisieren hinweg) (*meine Festlegung*); vorher liefert die API einen Status „zu wenig Daten“ (mit Anzahl der bisher erfassten Tage), die UI zeigt „noch zu wenig Daten (X Tage)“. Die Schwellen (14 Tage, 10 Belegungen, 8 Wochen) stehen als Konstanten an einer Stelle.

**Rechenzeitpunkt:** *Meine Festlegung:* Die Vorhersage wird im Favoriten-Poll (minütlich) mitberechnet, weil die Entitäts-Attribute ohnehin dort entstehen; die Muster-Statistik (Heatmap, Beginnhäufigkeiten) wird gecacht und höchstens stündlich neu aus der DB gelesen, damit der minütliche Poll keine Jahres-Abfrage macht.

### API

- `GET /v1/charging/favorites/{stationId}/forecast` (Pfad dem Builder überlassen, solange er unter `/v1/charging` liegt und nicht mit bestehenden Pfadvariablen kollidiert): Status (`OK` / `INSUFFICIENT_DATA` mit Tagen), Wahrscheinlichkeiten 15/30/60, Restdauer je belegtem Ladepunkt (Median-Uhrzeit, 80-%-Uhrzeit), Heatmap 7 × 24 (Werte 0–1 oder `null` = keine Daten).
- Die kurze Prognose für die Liste (Wahrscheinlichkeit 30 min, früheste „frei ca.“) kommt an die bestehende Antwort der Tablet-Liste für Favoriten, damit die Liste keinen zusätzlichen Abruf je Favorit braucht.
- Lesen ist KIOSK über die generische `GET /v1/**`-Regel; keine Schreib-Endpunkte. `SecurityRulesTest` hält das Lesen für KIOSK fest.

### Entität

`sensor.charging_<stationId>_free` bekommt zusätzlich (nur wenn Status `OK`, sonst Schlüssel fehlen statt `null`):
- `freeProbability15min`, `freeProbability30min`, `freeProbability60min` (0–100),
- `expectedFreeAt`, `expectedFreeLatest` (früheste Median- bzw. 80-%-Uhrzeit über alle belegten Ladepunkte; fehlen, wenn ein Ladepunkt frei ist). *Meine Festlegung:* volles Format `yyyy-MM-dd'T'HH:mm:ss` wie `nextSunrise` an `sensor.sun` (eindeutig über Mitternacht). Weil der `StateComparator` nicht mit Zeiten rechnet, zusätzlich `minutesUntilFree` und `minutesUntilFreeLatest` als Zahlen — darauf lassen sich Flow-Bedingungen wie „< 15“ bauen,
- `forecastStatus` (`ok` / `insufficient_data`).
Kein `deviceClass`, kein Trigger auf `unavailable` (bestehende Regeln). `ChargingEntityMapper` bleibt die einzige Id-Definition.

### Telegram

Neues Werkzeug im Paket `telegram/tools` (`AgentScope.TELEGRAM`), dünner Wrapper um denselben Vorhersage-Service: Eingabe ein Favoritenname oder Teil davon (Auflösung über die Favoritenliste; mehrdeutig/unbekannt ⇒ Fehlermeldung mit den vorhandenen Favoriten), Ausgabe aktueller Stand plus die drei Vorhersagen in Klartext, inkl. „noch zu wenig Daten“. Optional eine Zeitangabe („um 18 Uhr“); *meine Festlegung:* Für Zeitpunkte jenseits von 60 min antwortet es mit der Heatmap-Auslastung dieser Stunde statt mit einer Wahrscheinlichkeit.

### Tablet (`/tablet/charging`)

- Liste: unter jedem Favoriten eine Zeile „frei ca. 17:40–18:00 · 72 % in 30 min“ (Median bis 80-%-Quantil; ohne Quantil nur „frei ca. 17:40 · …“) bzw. „noch zu wenig Daten“.
- Antippen eines **Favoriten** zeigt statt der Karte den Detailbereich: Name, Stand, je belegtem Ladepunkt „belegt seit … → frei ca. …, meist bis …“, Wahrscheinlichkeiten 15/30/60 min, Heatmap Wochentag × Uhrzeit mit Markierung „jetzt“. Ein Schließen-Knopf bringt die Karte zurück; Antippen eines Nicht-Favoriten verhält sich wie heute. *Meine Festlegung:* Nur das Antippen **in der Liste** öffnet das Detail; ein Tipp auf einen Marker in der Karte markiert die Station wie heute (gemeinsamer Pfad `select(..., { fromMap: true })`), sonst verschwände die Karte unter dem Finger.
- Der **Kartencontainer bleibt im DOM** (Lehre aus `/pets`, `ViewChild` + `ngAfterViewInit`); der Detailbereich liegt darüber bzw. die Karte wird nur ausgeblendet. Beim Zurückwechseln muss Leaflet `invalidateSize` bekommen.
- Farben nur über die lumina-Tokens (hell/dunkel), die Heatmap-Skala wie die bestehenden Ampelfarben. Höhenkette beachten (Detailbereich füllt die Höhe der Karte).
- Refresh im bestehenden 60-s-Takt; ein fehlgeschlagener Refresh behält die letzte Vorhersage.

## Randbedingungen

- Die Quelle ist inoffiziell (EnBW); fällt sie aus, wächst die Historie nicht, die Vorhersage bleibt aus dem Bestand berechenbar, Restdauer/Wahrscheinlichkeiten beziehen sich aber auf den zuletzt bekannten Zustand — die UI zeigt das Alter wie heute über „Stand von“.
- Favoriten-Poll jede Minute ⇒ Beginn/Ende auf etwa eine Minute genau (genauer, wenn `state.updatedAt` geliefert wird).
- Projektkonventionen aus `CLAUDE.md` gelten (Lombok, Liquibase, Recorder wirft nie, `Clock`-Bean statt `systemDefault`, Repositories in `com.household.manager.repository`).
- Changesets nie gegen die echte DB gelaufen ⇒ Backup vor dem Deploy, Rollout-Hinweis in `CLAUDE.md`.

## Dem Builder überlassen

- Tabellen-, Klassen- und Endpunktnamen; Aufteilung in Recorder / Statistik / Vorhersage-Service.
- Genaue Form des Caches der Muster-Statistik.
- Darstellung der Heatmap (ECharts-Heatmap oder CSS-Raster), solange sie in die Kartenfläche passt.
- Tests: Feiertagsberechnung (Ostern-abhängige Tage mehrerer Jahre), bedingte Restdauer, Kombination der Wahrscheinlichkeiten, Lockerungsstufen, Anlaufphase, Lückenverwerfung, Retention, Security-Lesen, Tablet-Umschaltung Karte ↔ Detail.
- Abschnitt in `CLAUDE.md` unter „Ladesäulen-Übersicht“ ergänzen.
