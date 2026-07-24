# Telegram-KI-Assistent — Design

**Datum:** 2026-07-24
**Status:** Entwurf abgenommen (mündlich), Implementierung ausstehend

## Ziel

Der Nutzer will von unterwegs (iPhone, daher keine eigene App) mit dem lokal laufenden
Household-Manager kommunizieren: Fragen zum System stellen („Wie warm ist es im
Wohnzimmer?", „Was verbraucht gerade am meisten?") und Befehle geben („Schalte das Licht
im Wohnzimmer an"). Kanal ist **Telegram** (offizielle Bot-API, kostenlos, Long-Polling —
keine Portfreigabe nötig; WhatsApp wurde verworfen: Business-API kostenpflichtig,
inoffizielle Wege riskieren Kontosperrung). Die Sprachverarbeitung übernimmt die
**Claude API** (Anthropic Messages API mit Tool-Use).

## Entscheidungen (mit Nutzer abgestimmt)

1. **KI-Backend:** Claude API (Cloud, Tool-Use). Kein lokales LLM, kein Kommando-Bot.
2. **Befugnisse:** Lesen (alle Zustände/Verbräuche) + Schalten (Schalter, Lichter, Modi)
   + Nuki **nur verriegeln**. Entriegeln/Tür öffnen ist für den Bot technisch nicht
   vorhanden — durchgesetzt im Code, nicht per Prompt.
3. **Push-Kanal:** Ja — neuer Flow-Node `telegram-send`, damit Flows (z.B. „Waschmaschine
   fertig", Flow #5) Nachrichten aufs Handy schicken können.
4. **Architektur:** Direkt im Spring-Backend (Option B), **kein** Node-Sidecar. Der
   Nutzer hat sich bewusst gegen das Sidecar-Muster entschieden: keine neue Komponente,
   direkter Zugriff auf die Services im selben Prozess.

## Architektur

Neues Paket `backend/src/main/java/com/household/manager/telegram/` (analog `nuki/`,
`alexa/`, `tablet/`):

| Klasse | Verantwortung |
|---|---|
| `TelegramProperties` | Konfiguration (`@ConfigurationProperties`): Bot-Token, erlaubte Chat-IDs, Modell, Limits |
| `TelegramApiClient` | Dünner HTTP-Client für die Bot-API: `getUpdates` (Long-Polling) und `sendMessage`. Kein Bot-Framework. |
| `TelegramPollingService` | Eigener Thread mit Long-Polling-Schleife (Timeout ~30 s). `@Scheduled` passt nicht, weil Long-Polling blockiert. Startet nur bei gesetztem Token; Retry mit Backoff bei Telegram-Ausfall. |
| `TelegramAgentService` | Agent-Loop: Nutzernachricht + Gesprächskontext → Claude API (Messages API, Tool-Use) → Tools ausführen → Ergebnisse zurückschleifen, bis `end_turn` (mit Iterationslimit, z.B. 8) → Antwort in den Chat. |
| `AnthropicApiClient` | HTTP-Client für die Anthropic Messages API (`/v1/messages`) inkl. Tool-Definitionen und `tool_result`-Rückgabe. |
| `TelegramToolRegistry` | Definiert die Tools (JSON-Schema) und führt sie aus — dünne Wrapper um bestehende Services im selben Prozess. |
| `TelegramConversationStore` | Kurzzeitgedächtnis pro Chat: letzte N Nachrichten in-memory, TTL ~30 Min, damit Rückfragen („und im Schlafzimmer?") funktionieren. Kein DB-Schema. |

**Hinweis Java-HttpClient:** Gegen externe APIs `HTTP_1_1` erzwingen ist hier nicht
nötig (Telegram/Anthropic sind keine uvicorn-Sidecars), aber Timeouts explizit setzen.

## Tools der KI

Alle Tools rufen bestehende Services direkt auf (Controller bleiben unberührt):

| Tool | Service | Zweck |
|---|---|---|
| `list_switches` | `SwitchQueryService` | Schalter/Lichter mit Zustand und Namen auflisten |
| `set_switch` | `SwitchCommandService` | Schalter ein-/ausschalten |
| `get_entity_states` | `EntityStateService` | Zustände abfragen (Temperaturen, Sensoren, Präsenz, Luftqualität) — deckt die meisten „Wie ist…?"-Fragen ab; filterbar nach Domain/Suchbegriff |
| `list_power_consumers` | `PowerConsumerQueryService` | Aktuelle Verbraucher, größte zuerst |
| `get_meter_readings` | `MeterReadingService` | Letzte Zählerstände/Verbräuche je Zählertyp |
| `list_modes` / `set_mode` | `HouseModeQueryService` + Mode-Setz-Pfad (wie `ModeController`) | Haus-Modi anzeigen und setzen |
| `get_lock_status` | Entity-State des Nuki-Schlosses | „Ist abgeschlossen?" |
| `lock_door` | `NukiLockService`, hart auf `NukiLockAction.LOCK` fixiert | Nur verriegeln; unlock/unlatch existiert im Tool-Vertrag nicht |

Der Systemprompt beschreibt den Haushalt knapp (Rolle, verfügbare Fähigkeiten, deutsche
Antworten, kurz und chat-tauglich). Modell konfigurierbar
(`telegram.agent.model`, Default `claude-haiku-4-5-20251001` — schnell und günstig;
bei Bedarf auf ein stärkeres Modell umstellbar).

## Sicherheit

- **Allowlist:** `TELEGRAM_ALLOWED_CHAT_IDS` (kommagetrennt). Nachrichten fremder
  Chat-IDs: kein KI-Aufruf, keine Antwort, nur Log-Eintrag.
- **Türschloss:** Das einzige Nuki-Tool für Aktionen ist `lock_door` und ruft im Code
  fest `LOCK` auf. Kein Parameter, keine andere Aktion erreichbar — Prompt-Injection
  über den Chat kann die Tür nicht öffnen.
- **Secrets:** `TELEGRAM_BOT_TOKEN` und `ANTHROPIC_API_KEY` ausschließlich als
  Umgebungsvariablen (docker-compose), nie in DB oder Repo.
- Ohne gesetzten Token startet das Feature nicht (Bedingung wie bei anderen
  Integrationen, z.B. `@ConditionalOnProperty`).

## Push-Richtung: Flow-Node `telegram-send`

- Neuer `TelegramSendNodeHandler` in `flowengine/nodes/` (Muster:
  `AlexaAnnounceNodeHandler`).
- Konfiguration: `message` (Text, mit den üblichen Platzhaltern des Flow-Kontexts),
  optional `chatId`; ohne `chatId` geht die Nachricht an alle erlaubten Chats.
- Aufnahme in den node-types-Katalog, damit der Flow-MCP-Server ihn anbieten kann.

## Fehlerbehandlung

- Telegram nicht erreichbar → Polling-Retry mit Backoff, kein Absturz des Backends.
- Claude API-Fehler/Timeout → freundliche Fehlermeldung im Chat („Ich konnte das gerade
  nicht verarbeiten…"), Details ins Log.
- Tool-Ausführungsfehler → als `tool_result` mit `is_error` an Claude zurück, damit die
  KI sinnvoll antworten kann („Die Steckdose hat nicht reagiert").
- `telegram-send`-Node: Sendefehler bricht den Flow nicht hart ab (Verhalten analog
  Alexa-Announce).

## Bewusst nicht in v1

- Keine DB-Änderungen, keine Frontend-Seite (Konfiguration rein über Env).
- Keine Sprachnachrichten, Fotos, Inline-Buttons.
- Kein Entriegeln/Tür öffnen, auch nicht mit Bestätigung.
- Keine Webhooks (Long-Polling reicht und braucht keine öffentliche Erreichbarkeit).

## Tests

Ohne echte API-Aufrufe (gemockte Clients):

- `TelegramToolRegistry`: jedes Tool ruft den richtigen Service; `lock_door` kann
  ausschließlich verriegeln.
- `TelegramAgentService`: Tool-Use-Loop (tool_use → tool_result → end_turn),
  Iterationslimit, Fehlerpfad Claude-API.
- Allowlist: fremde Chat-ID wird ignoriert.
- `TelegramSendNodeHandler`: Platzhalter-Ersetzung, Standard-Empfänger, Fehlerpfad.
- `TelegramConversationStore`: TTL und Begrenzung der Historie.
