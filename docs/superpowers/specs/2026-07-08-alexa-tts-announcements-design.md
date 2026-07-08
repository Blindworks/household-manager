# Design: Alexa-TTS-Integration („Ansagen")

**Datum:** 2026-07-08
**Status:** Entwurf zur Review

## Ziel

Der Household-Manager soll Text-to-Speech-Durchsagen auf Amazon-Echo-Geräten abspielen können. Drei Anwendungsfälle:

1. **Manuelle Durchsagen** aus der UI (beliebiger Text an ein oder mehrere Echos).
2. **Geplante Ansagen** (Uhrzeit + Wochentage).
3. **Interner Baustein** für automatische Benachrichtigungen: andere Backend-Services können Ansagen auslösen. Konkrete Ereignis-Trigger (z. B. „Waschmaschine fertig") sind **nicht** Teil dieser Ausbaustufe und bekommen ein eigenes Design.

## Gewählter Ansatz

Direkte Anbindung an die **inoffiziellen Alexa-Endpunkte** (`alexa.amazon.de`), wie sie alexa-remote-control, alexapy und Home Assistants alexa_media_player nutzen. Es gibt keine offizielle Amazon-API für Push-TTS.

Verworfene Alternativen:

- **Über Home Assistant**: sauberere Trennung, aber der HA-Cutover steht noch aus — Feature wäre blockiert.
- **Node-Sidecar (alexa-remote2)**: robuste, bewährte Lib, aber zusätzlicher Container im Deployment.

Erst-Anmeldung erfolgt als **Login-Flow in der App**: E-Mail/Passwort und 2FA-Code werden im Frontend eingegeben, der Backend führt den Amazon-Geräteregistrierungs-Flow aus (gibt sich als Alexa-App aus) und erhält ein Refresh-Token. **E-Mail und Passwort werden nie gespeichert**, nur das Refresh-Token.

## Architektur (Backend)

Neues Package `com.household.manager.alexa`, analog zu `kasa/`, `tapo/`, `meross/`:

| Klasse | Verantwortung |
|---|---|
| `AlexaAuthService` | Geräteregistrierungs-Flow (Login, 2FA, optional Captcha), Refresh-Token-Verwaltung, Token→Cookie-Austausch, CSRF-Handling, In-Memory-Cache der Sitzung |
| `AlexaApiClient` | HTTP-Aufrufe gegen `alexa.amazon.<domain>`: Geräteliste (`/api/devices-v2/device`), TTS (`/api/behaviors/preview`) |
| `AlexaAnnouncementService` | Fachschnittstelle `announce(text, geräte, modus)`; genutzt von Controller, Scheduler und künftig anderen Services |
| `AlexaDeviceService` | Persistenz/Rescan der Echo-Geräte |
| `AlexaScheduledAnnouncementService` | CRUD + minütlicher `@Scheduled`-Job für fällige Ansagen |
| `AlexaController` | REST-Endpunkte (siehe unten) |

- TTS-Modi: **SPEAK** (ein Gerät, ohne Signalton) und **ANNOUNCE** (ein oder mehrere Geräte, mit Signalton). Beide laufen über `/api/behaviors/preview` mit entsprechendem Sequence-JSON.
- Amazon-Domain konfigurierbar: Property `alexa.domain`, Default `amazon.de`.
- Repositories liegen in `com.household.manager.repository` (JpaConfig-Einschränkung).

### Auth-Flow im Detail

1. `POST /auth/login` mit E-Mail/Passwort → Backend startet den Registrierungs-Flow (PKCE, simulierte App-Identität). Antwort: `OK`, `MFA_REQUIRED` oder `CAPTCHA_REQUIRED` (mit Bild-URL).
2. Bei MFA: `POST /auth/mfa` mit dem Code setzt den Flow fort. Bei Captcha: Lösung wird mit dem erneuten Login-Aufruf mitgeschickt.
3. Erfolg → Amazon liefert Refresh-Token → Speicherung in `alexa_account`.
4. Laufender Betrieb: Refresh-Token → Access-Token → Cookie-Austausch für `alexa.amazon.de`; Sitzung wird im Speicher gecacht und bei Ablauf automatisch erneuert (Muster: Tapo-Token-Caching).
5. Schlägt auch der Refresh fehl → Status „Neuanmeldung erforderlich", sichtbar in der UI. Kein Absturz, keine Endlos-Retries.

## Datenmodell (Liquibase-Changesets)

- **`alexa_account`** — genau eine Zeile: `refresh_token`, `amazon_domain`, `account_name`, Zeitstempel.
- **`alexa_device`** — Identität über stabile **`serial_number`** (nicht IP/Listenreihenfolge — Lehre aus der Kasa-Integration), dazu `device_type`, `name`, `tts_capable`, Zeitstempel. Rescan aktualisiert Namen und legt neue Geräte an; es wird nichts automatisch gelöscht.
- **`alexa_scheduled_announcement`** — `text`, `time_of_day`, Wochentage (CSV, z. B. `MON,TUE`), Zielgeräte über Join-Tabelle `alexa_scheduled_announcement_device` (FK auf Ansage + `serial_number`), `mode`, `enabled`, `last_run`, `last_error`.

## REST-API

```
POST /api/alexa/auth/login                  {email, password, captcha?}   → {status: OK|MFA_REQUIRED|CAPTCHA_REQUIRED, captchaImageUrl?}
POST /api/alexa/auth/mfa                    {code}                        → {status}
GET  /api/alexa/auth/status                                               → {loggedIn, accountName, reauthRequired}
POST /api/alexa/auth/logout                                               → Token + Sitzung löschen
GET  /api/alexa/devices?rescan=true|false                                 → [{serialNumber, name, deviceType, ttsCapable}]
POST /api/alexa/announce                    {text, serialNumbers[], mode} → 200 | Fehler
GET/POST/PUT/DELETE /api/alexa/scheduled-announcements                    → CRUD
```

## Frontend

Neue Seite **„Ansagen"** unter `pages/announcements/` mit Route und Navigations-Eintrag; dazu `services/alexa.service.ts` und Models. Drei Bereiche:

1. **Konto-Karte** — Login-Status; Formular für E-Mail/Passwort, danach ggf. 2FA-Feld bzw. Captcha-Bild + Eingabe; Abmelden-Button; Hinweis bei „Neuanmeldung erforderlich".
2. **Durchsage-Karte** — Textfeld, Geräteauswahl per Checkboxen, Umschalter Speak/Announce, Senden mit Erfolgs-/Fehlermeldung, Rescan-Button für die Geräteliste.
3. **Geplante Ansagen** — Liste mit Aktiv-Schalter und Löschen; Formular mit Text, Uhrzeit, Wochentagen, Geräten, Modus; Anzeige von `last_run`/`last_error`.

Standalone-Komponenten, separate HTML/SCSS-Dateien, Logik im Service (Projektkonventionen).

## Fehlerbehandlung

- Alle Amazon-Aufrufe fangen Verbindungs-/HTTP-Fehler ab und liefern verständliche Fehlermeldungen an die UI (graceful, wie bei den übrigen Geräteintegrationen).
- Sitzungsablauf → automatischer Refresh; endgültiger Fehlschlag → `reauthRequired = true`.
- Fehlgeschlagene geplante Ansagen: `last_error` setzen, beim nächsten Termin normal erneut versuchen. Verpasste Termine (Backend war offline) werden **nicht** nachgeholt.
- Risiko: Amazon kann die inoffiziellen Endpunkte oder den Login-Flow jederzeit ändern. Der gesamte Amazon-spezifische Code ist deshalb in `AlexaAuthService`/`AlexaApiClient` isoliert.

## Tests

- **Backend (JUnit, gemockter HTTP-Client — Muster `TapoCloudServiceTest`):** Auth-Zustandsübergänge (OK/MFA/Captcha/Refresh-Fehler), Aufbau der Speak-/Announce-Payloads, Geräte-Rescan-Persistenz (neu/aktualisiert/kein Löschen), Scheduler-Fälligkeit (Uhrzeit, Wochentage, enabled, kein Nachholen).
- **Frontend (Karma/Jasmine):** `AlexaService`-HTTP-Aufrufe, Login-Zustandsanzeige, Formular-Validierung (leerer Text, keine Geräte gewählt).

## Nicht in dieser Ausbaustufe

- Konkrete Ereignis-Trigger (Tasmota-Leistungsabfall etc.) — eigenes Folge-Design.
- Generisches Benachrichtigungs-Regelwerk.
- Lautstärkesteuerung, Musik/Routinen, Alexa-Gerätesteuerung über TTS hinaus.
- Mehrere Amazon-Konten.
