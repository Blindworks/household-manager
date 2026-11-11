# Wäschetrocknung im Keller: Vorhersage „könnte trocken sein“

Stand: 2026-11-11, Entwurf aus dem Gespräch mit dem Nutzer.

## Anlass und Ziel

Wäsche wird im Keller auf dem Ständer getrocknet. Der Aqara-Fühler „Keller“ sieht das deutlich: Nach dem Aufhängen steigt die relative Feuchte um 8 bis 15 Prozentpunkte und fällt dann über ein bis drei Tage zurück (Beispiele 2026-11-07: 46 → 62 %, 2026-11-11: 58,9 % um 17:35, 62,3 % um 18:04, aufgehängt gegen 17:56). Bisher muss man selbst nachsehen, ob die Wäsche trocken ist.

Das System soll:

1. am Wandtablet fragen, ob Wäsche aufgehängt wurde,
2. danach schätzen, wann sie trocken ist, und diese Schätzung im Intelligence Hub zeigen,
3. per Push und Hub-Karte melden, dass sie trocken sein **könnte**,
4. per Rückfrage am Tablet lernen, wie lange es bei diesem Keller wirklich dauert.

Erfolg aus Nutzersicht: nach einigen Waschgängen stimmt die Meldung „könnte trocken sein“ ungefähr — lieber etwas zu spät als zu früh.

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele**
- Start per Dialog am Wandtablet „Wäsche aufgehängt?“ (Ja / Nein), ausgelöst wie unten beschrieben.
- Während des Trocknens eine Hub-Karte mit Schätzung („Wäsche trocknet – voraussichtlich trocken morgen gegen 14 Uhr“).
- Wenn die Schätzung erreicht ist: Push **und** Hub-Karte, formuliert als Vermutung („Die Wäsche im Keller könnte jetzt trocken sein.“).
- Gleichzeitig am Tablet die Rückfrage „Ist die Wäsche trocken?“ (Ja / Noch nicht), aus der das System lernt.
- Antippen der Hub-Karte bedeutet jederzeit „Wäsche ist abgenommen“ und beendet den Waschgang.

**Nicht-Ziele (v1)**
- Kein anderer Ort als der Keller, kein zweiter Wäscheständer gleichzeitig.
- Keine Steuerung von Entfeuchter oder Lüftung, keine Lüftungsempfehlung.
- Kein Wäschetrockner (Maschine), keine Unterscheidung nach Wäscheart oder Menge.
- Kein Dialog auf der Website oder auf dem Handy — Fragen nur am Wandtablet.

**Anti-Ziele**
- Eine Meldung, die systematisch zu früh kommt. Im Zweifel später melden.
- Nerven: höchstens eine offene Frage gleichzeitig; ein „Nein“ beendet die Fragerei für diesen Waschgang.
- Ein geratener Zustand: ohne frische Kellerwerte keine Schätzung, kein „trocken“.

## Ablauf (vom Nutzer so bestätigt)

### 1. Auslöser für die Frage „Wäsche aufgehängt?“

Die Frage erscheint, wenn kein Waschgang läuft und keine Frage offen ist, in einem von zwei Fällen:

- **A — Steckdose aus (Hauptfall):** die Steckdose der Waschmaschine (`switch.meross_2112156531504590863548e1e9817420`) wechselt von `on` auf `off`, und zwar nicht durch einen Flow. Ob von Hand oder per App, ist nicht unterscheidbar und egal.
- **B — fertig + Feuchteanstieg:** die Waschmaschine meldet fertig (Helfer `input_boolean.manual_waschmaschine_fertig` wird `on`, gesetzt von Flow #1), und innerhalb von 3 Stunden danach steigt die Kellerfeuchte um mindestens 3 Prozentpunkte über den Wert zum Zeitpunkt „fertig“.

„Ja“ startet den Waschgang. Startzeit ist die Antwortzeit; Ausgangswert („Grundfeuchte“) ist die Kellerfeuchte unmittelbar vor dem Auslöser (bei B der Wert zum Zeitpunkt „fertig“). „Nein“ schließt die Frage; für diesen Waschgang (bis zum nächsten „läuft“ der Waschmaschine) wird nicht erneut gefragt. Unbeantwortet verfällt die Frage nach 6 Stunden ohne Waschgang.

### 2. Schätzung während des Trocknens

Physikalische Grundlage: Wäsche trocknet ungefähr proportional zum **Sättigungsdefizit** der Luft (VPD, in kPa: Sättigungsdampfdruck bei Kellertemperatur × (1 − RH)). Das System summiert ab Start minütlich `VPD × Zeit` („Trockenleistung“, kPa·h) und vergleicht mit dem gelernten Bedarf `K` (kPa·h je Waschgang).

- Restzeit = (K − bisherige Trockenleistung) / aktuelles VPD. Daraus `predictedDryAt`, bei jedem Takt neu.
- **Startwert für K ohne gelernte Waschgänge:** 24 h bei 16 °C / 60 % (VPD ≈ 0,73 kPa) ⇒ K ≈ 17,5 kPa·h. Bewusst eher lang (Anti-Ziel „zu früh“).
- **Kurvenkriterium:** ist die Mehrfeuchte (aktuelle RH − Grundfeuchte) nach ihrem Höchstwert auf höchstens 25 % des Höchstwerts gefallen und seit mindestens 60 min dort, gilt die Wäsche ebenfalls als „könnte trocken sein“, auch wenn K noch nicht erreicht ist. Frühestens 6 h nach Start (sonst greift es, bevor der Anstieg überhaupt da ist). **Nur wenn der Höchstwert der Mehrfeuchte mindestens 3 Prozentpunkte betrug** (Entwurfsentscheidung) — sonst wäre die Bedingung bei kaum sichtbarem Anstieg sofort erfüllt; dann gilt allein K. Zeitpunkt und Trockenleistung beim Greifen des Kriteriums werden am Waschgang gespeichert.
- Sind die Kellerwerte älter als 60 min (Fühler still oder `unavailable`), wird nicht weitergezählt; die Karte sagt „Schätzung pausiert – Kellerfühler meldet nichts“.

### 3. „Könnte trocken sein“

Sobald K erreicht ist oder das Kurvenkriterium greift:

- Push an alle Geräte: Titel „Wäsche“, Text „Die Wäsche im Keller könnte jetzt trocken sein.“ — **einmal je Waschgang**.
- Hub-Karte wechselt auf „Wäsche könnte trocken sein“.
- Tablet-Frage „Ist die Wäsche trocken?“ erscheint.
- **Ruhezeit 22:00–07:00:** fällt der Zeitpunkt hinein, werden Push, Kartenwechsel und Frage auf 07:00 verschoben. Die Karte zeigt nachts weiter die Schätzung. Ebenso wird eine Wiederholung von „Ist die Wäsche trocken?“ nach „Noch nicht“ auf 07:00 verschoben. **Die Frage „Wäsche aufgehängt?“ kommt auch nachts** (Entwurfsentscheidung): sie folgt unmittelbar auf eine Handlung am Gerät, jemand steht also gerade da.

### 4. Rückmeldung und Lernen

- **„Ja, trocken“:** Waschgang beendet. Die Trockenleistung bis zur Antwort ist eine **bestätigte Probe** für K.
- **„Noch nicht“:** Frage kommt nach 2 Stunden wieder (ohne neuen Push). Jede „Noch nicht“-Antwort ist eine Untergrenze: K war mindestens so groß.
- **Hub-Karte antippen („abgenommen“):** Waschgang beendet. Vor der Meldung „könnte trocken sein“ ist das keine Probe (Wäsche war vielleicht nass); danach zählt es wie „Ja“.
- **Nichts beantwortet:** nach 72 h ab Start endet der Waschgang von selbst. Hat das Kurvenkriterium gegriffen, zählt die Trockenleistung **bis zu dem Zeitpunkt, an dem es griff** (nicht bis 72 h) als **schwache Probe**.
- **K lernen:** Median der bestätigten Proben der letzten 10 Waschgänge; schwache Proben zählen nur, solange es weniger als 3 bestätigte gibt. Untergrenzen (Entwurfsentscheidung): je Waschgang zählt nur die größte „Noch nicht“-Untergrenze, nur aus den letzten 10 Waschgängen und nur aus Waschgängen **ohne** bestätigte Probe; liegt die größte davon über dem Median, gilt sie. So kann eine alte Untergrenze K nicht dauerhaft hochhalten. Ohne Probe gilt der Startwert.

## Oberflächen

- **Wandtablet:** die beiden Fragen als Dialog im Tablet-Modus — auf dem Dashboard und auf allen Tablet-Unterseiten (der Nutzer steht nicht unbedingt vor dem Dashboard). Wegen der `lumina`-Stilkapselung braucht das eigenes Markup bzw. eine eigenständig gestylte Komponente (siehe CLAUDE.md „Dashboard style encapsulation“). Das Tablet fragt den Zustand im 30-s-Takt ab. Antworten muss KIOSK dürfen (KIOSK-POST-Whitelist).
- **Intelligence Hub (Dashboard, beide Ansichten):** Karte „Wäsche trocknet“ mit Schätzung bzw. „Wäsche könnte trocken sein“, Icon z. B. `dry_cleaning`/`local_laundry_service`. Antippen = „abgenommen“; vorher frisch nachladen und nur beenden, wenn der Waschgang noch läuft (Regel aus `confirmToggle`).
- **Push:** über den bestehenden `push-send`-Weg (siehe Builder-Spielraum).
- **Admin/Website:** in v1 nichts Eigenes; gelernter Wert und letzte Waschgänge sollen aber über die API lesbar sein (zur Kontrolle).

## Randbedingungen aus dem Bestand

- Kellerwerte: `sensor.zigbee_temperatur_aqara_keller_temperature` und `…_humidity` (Aqara meldet nur bei Änderung, deshalb „letzter Wert“ je Minute verwenden).
- **Die Meross-Steckdose wird nicht gepollt** (`SmartDeviceStatePollingService` nimmt Meross bewusst aus). Ihre Switch-Entität ändert sich nur, wenn über die App/das Dashboard geschaltet wird. Auslöser A greift also nur bei Schalten über die App; ein Druck auf den Knopf an der Steckdose ist unsichtbar (dann bleibt Auslöser B).
- Flow #1 „Waschmaschine fertig“ setzt die Helfer `…_waschmaschine_fertig`/`…_laeuft`; die Schwellen dort sind unverändert.
- Zeit über den `Clock`-Bean (Europe/Berlin), Ruhezeit über `TimeWindow`.
- Neue Tabellen nur per Liquibase, je DDL ein Changeset (MariaDB-Autocommit), JPA-Repositories in `com.household.manager.repository`.
- Kein `deviceClass` an neuen Entitäten, kein Trigger auf `unavailable`.

## Was dem Builder überlassen ist

- Paketname, Tabellenschnitt (Vorschlag: `laundry_drying_session` je Waschgang mit Status, Zeiten, Grundfeuchte, Trockenleistung, Probe-Art), Endpunktnamen unter `/v1/laundry/...`.
- Ob der Push direkt aus Java (`PushNotificationService`) oder über einen Flow auf einer Zustands-Entität (z. B. `sensor.laundry_drying` mit `idle/asking/drying/maybe_dry`) läuft. Projektvorliebe ist der Flow (Wortlaut ohne Redeploy änderbar); dann muss die Ruhezeit-Verschiebung im Backend über den Zeitpunkt des Zustandswechsels laufen.
- Wie „nicht durch einen Flow“ erkannt wird (z. B. über den Audit-Aktor).
- Genaue Formel für den Sättigungsdampfdruck (Magnus-Formel genügt).
- Tests: Schätzung, Kurvenkriterium, Lernregel, Ruhezeit und die Dialog-Regeln (eine Frage gleichzeitig, „Nein“ sperrt den Waschgang) gehören in Unit-Tests.

## Offene Punkte nach Realbetrieb

- Startwert K, 3-Punkte-Schwelle, 25-%-Kurvenkriterium und 72-h-Grenze sind Schätzungen aus einer Woche Daten; nach einigen Waschgängen nachziehen.
