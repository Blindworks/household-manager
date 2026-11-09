# Finanz-Regeln mit UND-Bedingungen und klügerem Vorschlag – Design

Stand: 2026-11-09

## Anlass und Zweck

Viele Ausgaben laufen über Zahlungsdienstleister, vor allem PayPal. Der Empfänger lautet dann immer
„PayPal Europe S.a.r.l. et Cie S.C.A“, der eigentliche Zweck steht nur im Verwendungszweck, zum
Beispiel `1053795742551/. SHELL DEUTSCHLAND GmbH, Ihr Einkauf bei SHELL DEUTSCHLAND GmbH`.

Heute prüft eine Regel (`CategorizationRule`) genau **ein** Feld. Der Regelvorschlag nach einer
Handzuordnung (`CategorizationService.suggestRule`) ist **immer** „Empfänger enthält
<normalisierter Name>“. Nimmt man ihn bei einer PayPal-Buchung an, landen alle PayPal-Zahlungen in
derselben Kategorie: Tanken, Streaming und Einkäufe. Der Vorschlag ist im Banner nicht bearbeitbar.

Ziel: Buchungen über Zahlungsdienstleister lassen sich nach ihrem eigentlichen Zweck einordnen, und
der Vorschlag führt von selbst dorthin.

## Ziele

1. Eine Regel hat **1 bis 5 Bedingungen**, die **alle** zutreffen müssen (UND). Jede Bedingung ist
   Feld (Empfänger, IBAN, Verwendungszweck) + Vergleich (enthält, ist gleich, Regex) + Muster, wie
   eine heutige Regel.
2. **Ein gemeinsamer Bedingungs-Editor** an zwei Stellen, gleichwertig (Nutzerentscheidung):
   - auf der Regel-Seite `finance-rules` (Anlegen **und** Bearbeiten – Bearbeiten fehlt heute im UI),
   - an der Buchung: nach einer Handzuordnung erscheint statt des Ja/Nein-Banners ein vorbelegter,
     bearbeitbarer Vorschlag.
3. **Klügerer Vorschlag bei Zahlungsdienstleistern:** ist der Empfänger PayPal oder Amazon, schlägt
   das System zwei Bedingungen vor: „Empfänger enthält PAYPAL“ und „Verwendungszweck enthält
   <Händler>“, den Händler aus dem Verwendungszweck gezogen.
4. **Spezifischere Regel gewinnt** (Nutzerentscheidung): Regeln mit mehr Bedingungen werden vor
   Regeln mit weniger geprüft; bei Gleichstand entscheidet die Prioritätszahl, dann die Id.
5. **Neu bewerten mit Vorschau** (Nutzerentscheidung): Wer eine Regel anlegt, ändert, an-/abschaltet
   oder löscht, sieht vor dem Anwenden, welche Buchungen ihre Kategorie wechseln würden, und
   bestätigt dann. Neu bewertet werden alle Buchungen, die **nicht von Hand** eingeordnet wurden –
   auch solche, die eine Regel bereits eingeordnet hat.

## Nicht-Ziele

- ODER- oder NICHT-Verknüpfungen, Betragsbedingungen, Datumsbedingungen.
- Aufteilen von Sammelbuchungen. Eine Kreditkartenabrechnung als eine Buchung über den Monatsbetrag
  wird als Ganzes eingeordnet; die einzelnen Käufe darin kennt das System nicht.
- Einzelne Buchungen in der Vorschau abwählen. Wer eine Ausnahme will, ordnet die Buchung danach von
  Hand zu; Handzuordnungen gewinnen immer.
- Selbstlernende Kategorisierung oder KI-Vorschläge.
- Pflegbare Liste der Zahlungsdienstleister (siehe „Entscheidungen des Builders“).
- Wiederkehrende Zahlungen nachziehen: `RecurringPayment.categoryId` wird einmalig bei der Erkennung
  (`RecurringDetectionService`) kopiert und folgt einer Neubewertung nicht. Das ist heute bei einer
  Handzuordnung genauso, die Neubewertung bleibt dort also bewusst konsistent untätig.

## Anti-Ziele (wäre ein Fehlschlag, auch wenn es technisch läuft)

- Eine Regel überschreibt eine **von Hand** gesetzte Kategorie.
- Viele bestehende Buchungen wechseln ihre Kategorie, ohne dass man vorher sieht, welche.
- Der Vorschlag bei einer PayPal-Buchung ist wieder eine reine „Empfänger enthält PAYPAL“-Regel, die
  man mit einem Klick als Sammelregel anlegt.
- Bestehende Regeln verhalten sich nach der Migration anders als vorher (außer durch die neue
  Reihenfolge, siehe unten).

## Bestand (zum Nachlesen)

- Backend: `model/entity/CategorizationRule` (Tabelle `categorization_rules`, Changeset
  `20260624-0021`), `finance/RuleMatcher`, `service/CategorizationService` (Anwenden beim Import,
  Vorschlag), `service/CategorizationRuleService` (CRUD, `applyAllToUncategorized`),
  `controller/CategorizationRuleController` (`/v1/finance/rules`), `service/TransactionService.categorize`
  (setzt `manuallyCategorized = true` und liefert `RuleSuggestion`), `service/StatementImportService`
  (ordnet beim Import ein), `finance/CounterpartyNameNormalizer`.
- `Transaction` hat `categoryId` und `manuallyCategorized`.
- Frontend: `pages/finance-rules/`, `pages/finance-transactions/` (Banner „Künftig … immer als …?“),
  `services/finance.service.ts`, `models/finance.model.ts`.
- Security: `/v1/finance/**` ist MEMBER (`SecurityConfig`). Daran ändert sich nichts.

## Fachliche Regeln

### Auswertung

- Eine Regel passt, wenn **jede** ihrer Bedingungen passt. Eine einzelne Bedingung wertet genau wie
  der heutige `RuleMatcher` aus (Groß-/Kleinschreibung egal; Regex als Vollabgleich, `DOTALL`,
  ungültige Regex = passt nicht).
- **Reihenfolge** der aktiven Regeln: Anzahl Bedingungen absteigend, dann `priority` aufsteigend, dann
  `id` aufsteigend. Die erste passende gewinnt. Diese Ordnung hat **genau eine Definition** im Code,
  die Import, Neubewertung, Vorschau und Vorschlag gemeinsam nutzen.
- Folge der neuen Reihenfolge für den Bestand: heute haben alle Regeln eine Bedingung, ihre relative
  Ordnung bleibt also gleich. Erst neue Mehrfachregeln drängen sich davor.

### Neubewertung

- Kandidaten: alle Buchungen mit `manuallyCategorized = false`, gleich ob mit oder ohne Kategorie.
- Für jede wird die Kategorie mit dem **vollständigen** aktiven Regelsatz **nach** der Änderung neu
  bestimmt. Als Änderung zählt jede Buchung, deren neue Kategorie von der alten abweicht – auch
  „hatte eine, hat jetzt keine“ (etwa nach dem Löschen einer Regel).
- Die Vorschau rechnet auf einem **hypothetischen** Regelsatz und schreibt nichts. Das Speichern
  rechnet beim Anwenden **neu**, statt der Vorschau zu vertrauen; kleine Abweichungen durch einen
  zwischenzeitlichen Import sind akzeptiert.
- Beim Speichern wählt man „Speichern und anwenden“ oder „Nur speichern“. „Nur speichern“ ändert keine
  bestehende Buchung; neue Importe nutzen die Regel ab sofort.
- Der heutige Knopf „Regeln auf unkategorisierte anwenden“ wird zu „Alle Regeln neu anwenden“, mit
  derselben Vorschau.

### Vorschlag nach Handzuordnung

- Unterdrückt wie heute, wenn der aktive Regelsatz diese Buchung schon derselben Kategorie zuordnen
  würde.
- **Zahlungsdienstleister erkannt** (feste Liste im Code, siehe unten): zwei Bedingungen
  1. Empfänger enthält `<Token des Dienstleisters>` (`PAYPAL` bzw. `AMAZON`),
  2. Verwendungszweck enthält `<Händler>`.
  Den Händler zieht ein Extraktor aus dem Verwendungszweck. Für PayPal ist das Muster aus dem
  realen Beispiel „Ihr Einkauf bei <Händler>“ belegt; gezogen wird bis Zeilenende oder Komma, danach
  wird eine nachgestellte Rechtsform entfernt (GmbH, AG, SE, KG, e.K., Ltd, Inc, S.a.r.l. u. ä.), also
  `SHELL DEUTSCHLAND`.
- **Händler nicht extrahierbar** (z. B. Amazon, dessen Verwendungszweck-Format nicht belegt ist): die
  zweite Bedingung erscheint mit **leerem** Muster und dem Hinweis, sie zu ergänzen. Speichern geht
  erst, wenn sie gefüllt oder bewusst entfernt ist.
- **Kein Dienstleister:** wie heute eine Bedingung „Empfänger enthält <normalisierter Name>“.
- Speichert man eine Regel, die nur aus „Empfänger enthält <Dienstleister>“ besteht, warnt der
  Editor („trifft alle PayPal-Zahlungen“), blockiert aber nicht – eine bewusste Sammelregel als
  Auffangnetz ist legitim und steht dank der Reihenfolge hinter den spezifischeren.

## Schnittstellen (Rahmen, Details beim Builder)

- `CategorizationRuleRequest`: statt `field`/`matchType`/`pattern` eine Liste `conditions` (1–5,
  jeweils `field`, `matchType`, `pattern` nicht leer; Regex muss kompilieren, sonst 400), dazu wie
  bisher `categoryId`, `priority`, `enabled` und ein Schalter, ob angewendet werden soll.
  Antworten tragen `conditions` und die Zahl tatsächlich geänderter Buchungen.
- Vorschau-Endpunkt unter `/v1/finance/rules/…`, der eine geplante Änderung (anlegen, ändern,
  löschen, an/aus, „alle neu anwenden“) entgegennimmt und die Gesamtzahl plus eine auf 200 Zeilen
  gekappte Liste liefert: Datum, Empfänger, Verwendungszweck, Betrag, alte und neue Kategorie.
- `RuleSuggestion` trägt `conditions` statt eines einzelnen Felds, dazu optional einen Hinweistext.
- Das alte Einzelfeld-Format entfällt; einziger Konsument ist das eigene Frontend.

## Datenbank

- Neue Tabelle für Bedingungen (je Zeile: Regel-Fremdschlüssel mit `ON DELETE CASCADE`, Feld,
  Vergleich, Muster, Position). Bestehende Regeln werden zu je **einer** Bedingung migriert, danach
  entfallen die Spalten `match_field`, `match_type`, `pattern` an `categorization_rules`.
- Wie im Projekt üblich **ein DDL je Changeset** (MariaDB committet DDL implizit); die
  Datenübernahme als eigenes Changeset zwischen Anlegen und Entfernen.
- Die Changesets laufen lokal nie gegen eine echte MariaDB (keine lokale DB) – **Backup vor dem
  Deploy**.

## Oberfläche

- Gemeinsame Komponente „Bedingungs-Editor“: Zeilen mit Feld, Vergleich, Muster, Entfernen; „Bedingung
  hinzufügen“ bis 5; Kategorie; deutsche Bezeichnungen statt Enum-Namen (Empfänger, IBAN,
  Verwendungszweck; enthält, ist gleich, Regex).
- Regel-Seite: Liste zeigt Bedingungen lesbar („Empfänger enthält PAYPAL **und** Verwendungszweck
  enthält SHELL DEUTSCHLAND“), dazu Bearbeiten.
- Buchungsseite: der Vorschlag erscheint als vorbelegter Editor an Stelle des Banners; „Nein danke“
  schließt ihn.
- Vorschau-Dialog: Anzahl, Tabelle der Änderungen (alt → neu), „Speichern und anwenden“, „Nur
  speichern“, „Abbrechen“.

## Entscheidungen des Builders (nicht mit dem Nutzer abgestimmt)

- Höchstens 5 Bedingungen je Regel.
- Liste der Zahlungsdienstleister fest im Code (PayPal, Amazon), keine Admin-Pflege.
- Vorschau-Liste auf 200 Zeilen gekappt, Gesamtzahl immer vollständig.
- Auch Löschen und An/Aus laufen über die Vorschau.
- Die Warnung bei einer reinen Dienstleister-Regel blockiert nicht.

## Offen bis zum Realtest

- Format des Amazon-Verwendungszwecks (keine Beispielbuchung gesehen) – bis dahin kein Extraktor für
  Amazon, nur die leere Zweck-Bedingung.
- Ob alle PayPal-Buchungen das Muster „Ihr Einkauf bei …“ tragen (belegt sind nur Shell-Beispiele).
