# Hausverstand: proaktiver KI-Agent – Design

Stand: 2026-10-12. Ausbaustufe 1: **Vorschlagsmodus mit Freigabe je Aktionsklasse** („Stufe 2 mit Stufe 1 als Startzustand").

## Ziel

Der Household Manager soll von sich aus beobachten und handeln: ein LLM (Claude) sieht in regelmäßigen Abständen und bei wichtigen Zustandswechseln den gesamten Hauszustand, urteilt mit Kontext (Anwesenheit, Modi, Tageszeit, Vorlieben) und schlägt Aktionen vor oder führt sie aus. Die Flow-Engine bleibt die deterministische Schicht für alles, was in Sekunden passieren muss oder sicherheitsrelevant ist (Feuerverdacht, Tür offen bei Abwesenheit); der Agent ergänzt sie um Urteilsvermögen für alles, was niemand als Regel aufgeschrieben hat.

Erfolg heißt: Dinge passieren, die nie als Flow formuliert wurden, und jede davon ist mit Begründung nachvollziehbar und widerrufbar.

### Entscheidungen aus dem Brainstorming (2026-10-12)

| Frage | Entscheidung |
|---|---|
| Autonomiestufe | Freigabe je Aktionsklasse: `PROPOSE` (Vorschlag per Telegram) oder `DIRECT`. Start: alles `PROPOSE`, nach Beobachtung Klassen einzeln freigeben |
| Laufzeitort | Im Spring-Backend (verallgemeinerter Agent-Loop des Telegram-Moduls), kein Sidecar, Abrechnung per `ANTHROPIC_API_KEY` |
| Auslöser | Grundtakt 30 min plus Weckerliste (wenige Schlüssel-Entitäten) mit Entprellung |
| Vorschlagskanal | Telegram mit Inline-Knöpfen (v1); Hub-Karten mit Ja/Nein als zweite Ausbaustufe |
| Gedächtnis | Freitext-Notizen für Beobachtungen und Vorlieben; Freigaben strukturiert und nur per Admin änderbar |
| Aktionsklassen v1 | `HEATING`, `SWITCH` (inkl. Licht), `MODE`, `NOTIFY`, `LOCK` (nur verriegeln) |

## Nicht-Ziele (v1)

- Kein Entriegeln/Öffnen der Haustür, kein Unscharfschalten von Kameras, kein Anlegen oder Ändern von Flows, keine Nutzer-/Token-Verwaltung durch den Agenten – nicht aus technischen Gründen, sondern weil ein LLM-Urteil dort nie die letzte Instanz sein darf.
- Keine Vorschlagskarten mit Ja/Nein im Intelligence Hub (nur ein Zähler-Eintrag mit Link).
- Kein Lernen aus Sensorhistorie über Stunden hinaus, keine getrennten Vorlieben je Haushaltsmitglied, kein Sprachkanal über Alexa.
- Kein Sidecar, kein Claude Agent SDK.

## Architektur

Neues Backend-Modul `backend/src/main/java/com/household/manager/agent/`. Der Telegram-Bot bleibt der **reaktive** Dialog, der Hausverstand ist der **proaktive** Teil. Beide teilen sich die Claude-Anbindung, die dafür aus `telegram/` in ein gemeinsames Paket `ai/` wandert: `AnthropicApiClient`, `AnthropicMessage`, `AnthropicResponse`, `AgentTool`, `ToolResult`, Registry. Die Registry bekommt eine Zuordnung Tool → Agent (`TELEGRAM`, `HOUSE`), damit der Hausverstand keine Telegram-Antwort-Tools sieht und umgekehrt. Der Telegram-Agent verhält sich danach unverändert (bestehende Tests bleiben grün).

### Komponenten

- **`AgentWakeService`** – entscheidet, *wann* ein Lauf startet. `@Scheduled` Grundtakt (`agent.tick-interval-ms`, 30 min, Startverzögerung 2 min) plus `@EventListener` auf `EntityStateChangedEvent`, der nur Entitäten der Weckerliste `agent.wake-entities` (Properties, Muster Zigbee-Watchdog) beachtet. Wecker werden mit Grund gesammelt und frühestens nach `agent.wake-debounce-ms` (3 min) als **ein** Lauf gestartet. Ein `AtomicBoolean` verhindert parallele Läufe; Wecker während eines Laufs lösen danach genau einen Folgelauf aus. Läufe laufen auf einem eigenen Ein-Thread-Executor – nie auf dem Scheduler-Thread, nie auf dem Flow-Executor.
- **`HouseSnapshotBuilder`** – baut den Hauszustand als kompakten Text für den Systemprompt aus den vorhandenen Query-Services: Uhrzeit, Sonnenphase, Anwesenheit je Person, Modi und Helfer, Thermostate und Raumtemperaturen, Außentemperatur, offene Kontakte, große Verbraucher, Maschinen-Helfer, Vorräte, nächste Kalendertermine, Netzwerk, Zigbee-Gesundheit; dazu offene und zuletzt entschiedene Vorschläge und alle aktiven Notizen. Jede Quelle ist isoliert: scheitert eine, steht „<Quelle>: nicht abrufbar" im Snapshot statt eines abgebrochenen Laufs. Werte älter als eine Schwelle tragen ihr Alter („Wohnzimmer 18,5 °C, vor 47 min"), damit das Modell nicht auf tote Sensoren heizt. Länge gedeckelt.
- **`AgentRunService`** – der Lauf: Systemprompt (Rolle, Regeln, Snapshot, Notizen, Freigabetabelle) plus Auslöser als Nutzernachricht; Tool-Schleife mit Deckel 8 Iterationen und Zeitbudget 90 s; wirft nie; Audit-Aktor `AGENT` (neuer SYSTEM-Typ, Muster `FLOW:<id>`). Jeder Lauf wird in `agent_run` protokolliert.
- **`AgentActionGate`** – die Schleuse, durch die *jede* Aktion geht. Aktions-Tools führen nichts selbst aus, sie reichen eine typisierte `AgentAction` (Klasse, Ziel, Parameter, Begründung in einem Satz) an die Schleuse.
- **`AgentProposalService`** – legt Vorschläge an, stellt sie zu, verarbeitet Entscheidungen (Telegram-Knopf oder Admin-Seite), führt freigegebene Aktionen aus.
- **`AgentNoteService`** – Notizen (CRUD für Modell und Admin, Obergrenze, Ablauf).
- **Telegram-Erweiterung** – `TelegramApiClient.sendMessage` mit `reply_markup` (Inline-Keyboard), `editMessageReplyMarkup`, `answerCallbackQuery`; `TelegramPollingService` verarbeitet `callback_query`-Updates und leitet sie an `AgentProposalService`, Textnachrichten wie bisher an den Dialog-Agenten. Chat-Allowlist gilt auch für Knöpfe.
- **Admin-Seite „Hausverstand"** (`admin/agent`).

### Modell und Konfiguration

| Property | Default | Zweck |
|---|---|---|
| `agent.enabled` | `true` | Deploy-Ebene (Beans, Scheduler) |
| `agent.model` | `${AGENT_MODEL:claude-sonnet-4-5}` | Sonnet, weil das Urteil hier der Kern ist; Haiku bleibt für den Chat |
| `agent.anthropic-api-key` | `${ANTHROPIC_API_KEY:}` | derselbe Schlüssel wie Telegram |
| `agent.tick-interval-ms` | 1 800 000 | Grundtakt |
| `agent.wake-entities` | `binary_sensor.presence_household, input_boolean.manual_abwesend, input_boolean.manual_toni_allein, input_boolean.manual_nachtmodus, sensor.sun, input_boolean.manual_waschmaschine_fertig, input_boolean.manual_spuelmaschine_fertig` | Weckerliste (Entity-IDs beim Rollout gegen die echte Liste prüfen) |
| `agent.wake-debounce-ms` | 180 000 | Entprellung |
| `agent.quiet-hours` | `22:00-07:00` | Ruhezeiten (über `TimeWindow`) |
| `agent.max-tool-iterations` | 8 | |
| `agent.run-timeout-seconds` | 90 | |

Der **Betriebsschalter** (an/aus im laufenden Betrieb) liegt getrennt davon in `application_settings`, Kategorie `AGENT`, Schlüssel `agent_active` (Default `false`; Lesen wirft nie, unbekannter Wert ⇒ aus). Nach dem Deploy ist der Agent damit installiert, aber still, bis er in der Admin-Seite eingeschaltet wird.

Modell-IDs werden beim Implementieren gegen die aktuelle Modellliste geprüft (claude-api-Skill), nicht aus dem Gedächtnis gesetzt.

## Ablauf eines Laufs

1. `AgentWakeService` ruft `AgentRunService.run(trigger)`. `trigger` ist `SCHEDULED` oder `WAKE` mit der Liste der Änderungen („binary_sensor.presence_household: on → off um 17:42").
2. `HouseSnapshotBuilder` sammelt den Zustand (siehe oben).
3. Systemprompt = feste Rolle und Regeln + Snapshot + Notizen + Freigabetabelle („HEATING: nur vorschlagen; SWITCH: direkt; …"). Rolle und Regeln sind der statische Teil und werden mit Prompt-Caching markiert. Nutzernachricht = Auslöser plus Aufforderung: „Prüfe, ob etwas zu tun ist. Wenn nein, antworte mit einem Satz Begründung und ohne Tool-Aufruf."
4. Tool-Schleife. Lese-Tools sind wenige, weil der Snapshot den Normalfall abdeckt: Verlauf eines Sensors der letzten Stunden, Kalender der nächsten Tage. Aktions-Tools gehen durch die Schleuse, die dem Modell als Ergebnis „ausgeführt" oder „als Vorschlag Nr. 17 gestellt" zurückmeldet. Notiz-Tools: anlegen, ändern, löschen.
5. Der Lauf endet mit der Textantwort des Modells als Kurzbegründung, die in `agent_run` landet. Nur Vorschläge und direkte Aktionen erzeugen Nachrichten an Menschen.

### Feste Regeln im Prompt (Code, nicht Admin-pflegbar)

- Nie etwas vorschlagen, das in den letzten 24 h abgelehnt wurde oder noch offen ist.
- Komfort nur bei Anwesenheit, Sparen bei Abwesenheit.
- Im Zweifel nichts tun.
- Sicherheitsrelevante Beobachtungen (Fenster offen bei Regen, Verbraucher läuft seit Stunden bei Abwesenheit) immer melden, auch ohne Aktion; dringende Meldungen als solche markieren (umgeht die Ruhezeit).
- Geräte beim Anzeigenamen nennen, nie bei Entity-IDs.

### Kostenrahmen

Bei Sonnet grob 4–6 k Eingabe-Tokens je Lauf ohne Tool-Aufrufe (≈ 1,5–2 Cent). 48 Grundtakt-Läufe plus geschätzt 20 Wecker am Tag ≈ 1–1,50 € täglich. Die Admin-Seite zeigt die Tagessumme der Tokens aus `agent_run`.

## Aktionsschleuse, Freigaben, Telegram-Knöpfe

**`AgentAction`**: Aktionsklasse (`HEATING`, `SWITCH`, `MODE`, `NOTIFY`, `LOCK`), Ziel (Entity-ID bzw. Thermostat-ID), Parameter, Begründung.

**Freigaben** (`agent_action_policy`): je Klasse `PROPOSE` oder `DIRECT`. Fehlt eine Zeile, gilt `PROPOSE`. `NOTIFY` ist immer `DIRECT` (Nachrichten sind selbst der Vorschlagskanal). Umschalten auf `DIRECT` nur über die Admin-Seite – nie über Telegram, nie durch das Modell. Das ist die Grenze zwischen Freitext-Gedächtnis und Sicherheit.

**Harte Leitplanken im Code der Schleuse**, unabhängig von Freigabe und Prompt:

- Heizung nur 5,0–24,0 °C (zusätzlich zum `ThermostatTargetValidator`).
- `LOCK` kennt nur Verriegeln (im Tool-Vertrag nicht ausdrückbar, wie beim Telegram-Tool).
- Höchstens 3 offene Vorschläge gleichzeitig; kein zweiter Vorschlag für dasselbe Ziel, solange einer offen ist.
- Ein Vorschlag verfällt nach 2 h unbeantwortet (`EXPIRED`, Knöpfe werden entfernt; Prüfung minütlich im Scheduler).
- Ruhezeiten: Vorschläge entstehen, werden aber erst nach Ende der Ruhezeit zugestellt, sofern dann noch gültig (die Schleuse prüft vor der Zustellung erneut gegen den Zustand). Ausnahme: vom Modell als dringend markierte Meldungen (`NOTIFY` mit `urgent: true`) gehen sofort.

**Ablauf `PROPOSE`:** Zeile in `agent_proposal` (`PENDING`, Aktion als JSON, Begründung); Nachricht an alle erlaubten Chats mit Inline-Knöpfen `[Ja] [Nein] [Ja, und merken]`; Telegram-Message-IDs je Chat gespeichert. Knopfdruck → `AgentProposalService.decide(proposalId, decision, actor)`: nur wenn noch `PENDING` (sonst „bereits entschieden" als Callback-Antwort); bei Ja Ausführung über denselben Pfad wie `DIRECT`; Status setzen; Knöpfe in allen Chats durch „Ausgeführt von <Name>" / „Abgelehnt" ersetzen. „Ja, und merken" führt aus und legt eine `PREFERENCE`-Notiz an („<Name> stimmt zu: Heizung Wohnzimmer bei Anwesenheit auf 21 °C"). Ein Nein legt eine `OBSERVATION`-Notiz mit 24 h Ablauf an, damit die Sperre aus den Prompt-Regeln Daten hat. Die erste Antwort zählt; `decided_by` = Telegram-Chat-ID bzw. Admin-Nutzer.

**Ablauf `DIRECT`:** sofort ausführen, Zeile mit `EXECUTED`, kurze Info-Nachricht ohne Knöpfe („Heizung Wohnzimmer auf 21 °C gestellt: du bist zu Hause und es waren 18,5 °C."). Jede ausgeführte Aktion landet im Audit (`agent.action.execute`, Aktor `AGENT`).

**Vor jeder Ausführung** (Vorschlag oder direkt) wird der aktuelle Zustand neu gelesen; ist das Ziel schon erreicht, Status `EXECUTED` mit Vermerk „war bereits so", keine Geräteaktion (Regel aus `confirmToggle`). Scheitert die Aktion am Gerät: `FAILED` mit Fehlertext; das Modell sieht es im nächsten Snapshot.

Die Ausführung geht über die bestehenden Services (`ThermostatService`, `SwitchCommandService`, `ManualEntityService`, `NukiLockService`, Alexa/Push/Telegram), nicht über die REST-Schicht.

## Persistenz (Liquibase, ein Changeset je Tabelle wegen MariaDB-DDL-Autocommit)

- **`agent_note`**: `id`, `kind` (`OBSERVATION`/`PREFERENCE`), `text` (VARCHAR 500), `source` (`AGENT`/`USER`), `created_at`, `updated_at`, `expires_at` (nullbar). Obergrenze 50 aktive Notizen – ist sie erreicht, lehnt das Notiz-Tool ab und das Modell muss erst aufräumen. Das Modell darf nur `source = AGENT` löschen; Nutzer-Notizen sind für es unantastbar. Abgelaufene Notizen räumt der minütliche Scheduler weg.
- **`agent_proposal`**: `id`, `action_class`, `target`, `action_json`, `reason`, `urgent`, `status` (`PENDING`/`APPROVED`/`REJECTED`/`EXPIRED`/`EXECUTED`/`FAILED`), `decided_by`, `decided_at`, `delivered_at` (nullbar – Ruhezeit), `error`, `created_at`. Telegram-Message-IDs in `agent_proposal_message` (`proposal_id`, `chat_id`, `message_id`, FK `ON DELETE CASCADE`).
- **`agent_action_policy`**: `action_class` (PK), `policy`, `updated_at`.
- **`agent_run`**: `id`, `trigger_type`, `trigger_detail`, `started_at`, `duration_ms`, `model`, `input_tokens`, `output_tokens`, `tool_calls`, `summary`, `error`. Aufbewahrung 30 Tage, Bulk-Delete als `@Transactional @Modifying @Query` (Muster Netzwerk-Retention).

Keine Fremdschlüssel auf `entity_states`. Keine Aufräumjobs für `agent_note` und `agent_proposal` über Ablauf hinaus (Haushaltsgröße).

## API `/api/v1/agent`

| Methode | Pfad | Rolle | Zweck |
|---|---|---|---|
| GET | `/summary` | KIOSK | Zahl offener Vorschläge, aktiv ja/nein (Hub-Eintrag) |
| GET/PUT | `/settings` | ADMIN | Betriebsschalter |
| GET/PUT | `/policies` | ADMIN | Freigaben je Klasse |
| GET/POST/PUT/DELETE | `/notes[/{id}]` | ADMIN | Notizen |
| GET | `/proposals?limit` | ADMIN | Verlauf |
| POST | `/proposals/{id}/decide` | ADMIN | `{decision: APPROVE\|REJECT\|APPROVE_AND_REMEMBER}` |
| GET | `/runs?limit` + Tagessumme | ADMIN | Laufprotokoll |
| POST | `/runs` | ADMIN | „Jetzt laufen lassen", Mindestabstand 60 s → 429 |

Security: `/v1/agent/**` ist ein **methodenloser ADMIN-Matcher vor** der generischen `GET /v1/**`-Regel (Notizen enthalten Gewohnheiten des Haushalts). Einzige Ausnahme `GET /v1/agent/summary` → KIOSK, Matcher **davor**. `SecurityRulesTest` hält alle Richtungen fest. Audit: `agent.settings.update`, `agent.policy.update`, `agent.note.create/update/delete`, `agent.proposal.decide`, `agent.action.execute`.

## Frontend

- **Admin-Seite „Hausverstand"** (`pages/admin-agent/`, Route `admin/agent`, `adminGuard`; Wurzelklasse in `$admin-scopes` eintragen): Betriebsschalter mit Hinweisbanner, wenn Telegram nicht konfiguriert ist; Freigaben je Klasse als Umschalter (`NOTIFY` fest); Notizen (Liste, anlegen, bearbeiten, löschen, Quelle sichtbar); Vorschläge (Verlauf mit Status, Begründung, offene hier annehmen/ablehnen); Läufe (letzte 50 mit Dauer, Tokens, Begründung; Tagessumme; Knopf „Jetzt laufen lassen").
- **Intelligence Hub**: Eintrag „Hausverstand: n Vorschläge offen", nur bei n > 0, Link auf die Admin-Seite (im Tablet-Modus ohne Link, Navigationsfrage wie beim Vorrats-Dialog). Markup direkt im Dashboard (lumina-Kapselung).

## Fehlerfälle

- Anthropic-API nicht erreichbar / Rate-Limit: Lauf endet mit Fehlertext in `agent_run`, kein Retry im selben Takt. Nach 3 Fehlläufen in Folge eine einmalige Telegram-Warnung, danach Ruhe bis zur Erholung (einmal melden, Muster Zigbee-Watchdog).
- Unbekanntes Tool / ungültige Parameter: Fehler-Result an das Modell, Schleuse lehnt mit klarem Text ab; der Iterationsdeckel beendet Schleifen.
- Zeitbudget überschritten: Abbruch, offene Tool-Aufrufe werden nicht mehr ausgeführt, Lauf als Fehler protokolliert.
- Telegram nicht konfiguriert: Vorschläge entstehen, sind nur auf der Admin-Seite sichtbar; Banner dort.
- Backend-Neustart: offene Vorschläge überleben in der DB; Weckerliste im Speicher ist leer; erster Grundtakt nach 2 min.
- Betriebsschalter aus: Wecker werden verworfen, Grundtakt tut nichts, offene Vorschläge bleiben entscheidbar.

## Tests

- `HouseSnapshotBuilder`: jede Quelle einzeln ausfallend; Alter-Kennzeichnung; Länge gedeckelt.
- `AgentActionGate`: Temperaturkorridor, nur Verriegeln, max. 3 offen, kein Doppelvorschlag, Ruhezeit (inkl. `urgent`), `PROPOSE` vs. `DIRECT`, `NOTIFY` immer direkt, `DIRECT` nur bei Policy-Zeile.
- `AgentProposalService`: Doppelklick auf Ja führt genau einmal aus; abgelaufen lehnt ab; „Ja, und merken" erzeugt die Notiz; Nein erzeugt die 24-h-Beobachtung; Neu-Lesen vor Ausführung; `FAILED` bei Gerätefehler.
- `AgentWakeService`: nur Weckerliste zählt; Entprellung; kein Parallel-Lauf; Folgelauf nach Wecker während eines Laufs; Betriebsschalter aus.
- `AgentRunService` mit gestubbtem Client: Tool-Schleife, Iterationsdeckel, Zeitbudget, wirft nie, Protokollzeile.
- `AgentNoteService`: Obergrenze, Ablauf, Modell darf Nutzer-Notizen nicht löschen.
- Telegram: `callback_query`-Parsing, Allowlist, Knopf-Ersetzung; Dialog-Agent unverändert.
- `SecurityRulesTest`: ADMIN für `/v1/agent/**`, KIOSK für `/summary`, MEMBER-403.
- Frontend: Admin-Seite (vier Bereiche), Hub-Eintrag nur bei offenen Vorschlägen.

Das Modellverhalten ist nicht unit-testbar; dafür gibt es „Jetzt laufen lassen" und das Laufprotokoll.

## Rollout

1. Deploy (Migrationen nie gegen die echte DB gelaufen – Backup vorher). `AGENT_MODEL` setzen, `ANTHROPIC_API_KEY` ist vorhanden.
2. Weckerliste gegen die echten Entity-IDs prüfen (`flow_list_entities`).
3. Betriebsschalter in der Admin-Seite einschalten, „Jetzt laufen lassen", Protokoll lesen.
4. Eine Woche nur `PROPOSE` beobachten, Notizen pflegen (Vorlieben als `USER`-Notizen eintragen: Zieltemperaturen, Ruhezeiten, was nie passieren soll).
5. Danach einzelne Klassen auf `DIRECT`, beginnend mit `SWITCH`/`MODE`, zuletzt `HEATING`.

## Bewusst offen

- Hub-Vorschlagskarten mit Ja/Nein (Ausbaustufe 2).
- Ob die Weckerliste in die DB wandert (heute Properties, kein Grund für Laufzeitänderung).
- Qualität des Urteils – wird nur im Betrieb sichtbar; Schwellen in den Prompt-Regeln sind Startschätzungen.
