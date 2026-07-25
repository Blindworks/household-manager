# Usermanagement — Design

Datum: 2026-07-25
Status: Entwurf, vom Nutzer freigegeben

## Ziel

Mehrere Haushaltsmitglieder erhalten eigene Konten mit festen Rollen; Aktionen werden
nachvollziehbar protokolliert. Die App bleibt LAN-only — Authentifizierung dient primär
Rollen und Audit, nicht der Absicherung gegen das Internet (Brute-Force-Schutz o. Ä. ist
bewusst außerhalb des Scopes).

Nicht-Ziele (v1):

- Kein Zugriff aus dem Internet, kein HTTPS-/Reverse-Proxy-Setup
- Keine Selbstregistrierung, kein Passwort-Reset per E-Mail (Admin setzt Passwörter)
- Keine feingranularen Einzelrechte — nur die drei Rollen unten

## Entscheidungen (mit Nutzer abgestimmt)

| Frage | Entscheidung |
|---|---|
| Hauptziel | Mehrere Mitglieder, Rollen/Rechte, Nachvollziehbarkeit |
| Wandtablet | Eigenes Gerätekonto mit Rolle KIOSK, einmaliger Login im WebView |
| Maschinen-Clients | Individuelle Service-Tokens (Env-Variable), einzeln widerrufbar |
| Rollenmodell | 3 feste Rollen: ADMIN, MEMBER, KIOSK |
| Erreichbarkeit | LAN-only |
| Technik | Spring Security mit Server-Sessions (HttpOnly-Cookie), kein JWT |

## Architektur

### Authentifizierung (Browser)

- Neue Dependency `spring-boot-starter-security`; Session-basiert, `JSESSIONID` als
  HttpOnly-Cookie.
- Endpunkte: `POST /api/v1/auth/login` (Benutzername + Passwort), `POST /api/v1/auth/logout`,
  `GET /api/v1/auth/me` (aktueller Nutzer inkl. Rolle — Grundlage für das Frontend-Verhalten).
- Passwörter mit BCrypt gehasht.
- CSRF über `CookieCsrfTokenRepository` (Cookie `XSRF-TOKEN`); Angulars eingebauter
  XSRF-Mechanismus sendet den Header automatisch. Requests mit Service-Token sind vom
  CSRF-Schutz ausgenommen (kein Cookie-Kontext).

### Authentifizierung (Maschinen)

- Eigener Filter vor der Session-Auth prüft den Header `X-API-Token`.
- Tokens liegen **gehasht** (SHA-256) in der DB; jeder Token hat Name, Rolle, aktiv-Flag
  und `last_used_at`. Dadurch im Audit unterscheidbar und einzeln widerrufbar.
- Bei Erstellung wird der Klartext-Token genau einmal angezeigt, danach nur noch der Hash
  gespeichert.
- Vorgesehene Tokens: blink-vision-Sidecar (Rolle MEMBER — Webhooks/Embeddings),
  Tablet-App (Rolle KIOSK — nativer Presence-POST), flow-mcp-server (Rolle ADMIN —
  Flow-Verwaltung).
- Die reinen Maschinen-Endpunkte (Vision-Webhook/Embeddings-Abruf, `tablet-presence`)
  werden in der SecurityFilterChain als eigene Gruppe geführt und verlangen einen gültigen
  Service-Token — unabhängig von dessen Rolle. Die Rollenmatrix unten gilt für die
  Browser-/menschlichen Endpunkte. Der Telegram-Bot läuft im Backend selbst
  und braucht keinen Token; die alexa-remote2-Sidecar-Richtung ist Backend→Sidecar und
  damit nicht betroffen.

### Interne Aktoren

- Flows, Scheduler und Polling-Services laufen ohne Security-Kontext; im Audit erscheinen
  sie als Aktor-Typ `SYSTEM` (z. B. `FLOW:<id>`).
- Der Telegram-Bot protokolliert als `TELEGRAM:<chatId>`.

### Bootstrap

- Existiert beim Start kein Nutzer, legt das Backend einen Admin `admin` an. Passwort aus
  Env `INITIAL_ADMIN_PASSWORD`; fehlt die Variable, wird ein Zufallspasswort erzeugt und
  einmalig ins Log geschrieben.

## Datenmodell (Liquibase)

- `app_user`: id, username (unique), display_name, password_hash, role
  (ADMIN/MEMBER/KIOSK), enabled, created_at
- `service_token`: id, name (unique), token_hash, role, enabled, created_at, last_used_at
- `audit_log`: id, timestamp, actor_type (USER/SERVICE/SYSTEM/TELEGRAM), actor
  (Username/Token-Name/Flow-Id/ChatId), action (kurzer Schlüssel, z. B. `nuki.unlatch`),
  detail (frei, z. B. Entity/Parameter)

Alle Repositories liegen in `com.household.manager.repository` (JpaConfig-Einschränkung).

## Rollenmatrix

| Bereich | ADMIN | MEMBER | KIOSK |
|---|---|---|---|
| Dashboard, Sensoren, Charts, Verbraucher (lesen) | ✔ | ✔ | ✔ |
| Schalter schalten, Modi setzen | ✔ | ✔ | ✔ |
| Nuki verriegeln | ✔ | ✔ | ✔ |
| Nuki entsperren / Tür öffnen | ✔ | ✔ | ✖ |
| Kalender pflegen, Zählerstände erfassen, CSV-Import | ✔ | ✔ | ✖ |
| Ankündigungen (Alexa) senden | ✔ | ✔ | ✖ |
| Flows verwalten, Nutzer/Tokens verwalten, Audit-Log lesen, Preise pflegen, Vision-Personen verwalten, Blink-/Alexa-Login | ✔ | ✖ | ✖ |

Durchsetzung serverseitig in der `SecurityFilterChain` (URL-basierte Regeln pro
Endpunkt-Gruppe); das Frontend blendet zusätzlich nur aus, was die Rolle nicht darf
(UI-Komfort, keine Sicherheitsgrenze).

## Audit

- Zentraler `AuditService` (`record(actorAusSecurityContext, action, detail)`), aufgerufen
  an den sicherheitsrelevanten Stellen: Login/Logout (inkl. Fehlversuche), Nuki-Aktionen,
  Schalter/Modi, Flow-Änderungen (create/update/deploy/enable/delete), Nutzer- und
  Token-Verwaltung, Kalender-Änderungen.
- Kein automatischer Aufräumjob in v1 (Haushaltsgrößen; analog zur Kalender-Entscheidung —
  erste Stelle zum Nachziehen, falls die Tabelle je groß wird).

## Frontend

- **Login-Seite** (Route `login`, ohne Layout-Chrome), `AuthService`
  (login/logout/me, hält den aktuellen Nutzer als Signal/Observable).
- **HTTP-Interceptor:** 401 → Umleitung zur Login-Seite (mit Rücksprung-URL); 403 →
  Hinweis „keine Berechtigung".
- **Route-Guards:** angemeldet für alles außer `login`; Admin-Guard für Verwaltungsseiten.
- **Neue Admin-Seiten:** Nutzerverwaltung (anlegen, Rolle ändern, deaktivieren, Passwort
  setzen), Token-Verwaltung (anlegen mit Einmal-Anzeige, widerrufen), Audit-Log
  (chronologisch, Filter nach Aktor).
- **Header:** Anzeigename des angemeldeten Nutzers + Logout; Menüpunkte nach Rolle
  ausgeblendet.
- **Tablet:** meldet sich im WebView einmalig mit dem KIOSK-Gerätekonto an
  (Session-Cookie persistiert im WebView); der native Presence-Heartbeat sendet den
  Service-Token als Header. Session-Timeout großzügig konfigurieren, damit das Tablet
  nicht regelmäßig neu angemeldet werden muss.

## Fehlerbehandlung

- 401 = nicht angemeldet, 403 = Rolle reicht nicht — sauber getrennt, damit das Frontend
  korrekt reagiert.
- Deaktivierte Nutzer: laufende Sessions werden invalidiert (Session-Registry), Login wird
  abgelehnt.
- Ungültiger/widerrufener Service-Token → 401; der Client-Fehler landet im Log des
  jeweiligen Sidecars.
- Login-Fehlermeldung bewusst unspezifisch („Benutzername oder Passwort falsch").

## Migration / Rollout

- Nach dem Deployment sind alle Endpunkte geschützt; die Sidecars brauchen ihre Tokens
  **vor** dem Backend-Update (Env-Variablen im docker-compose), sonst fallen
  blink-vision-Webhooks und Tablet-Presence still aus.
- Reihenfolge: Tokens anlegen (Admin-UI) → Envs setzen → Sidecars neu starten.
- flow-mcp-server erhält den Token über seine Env-Konfiguration in `.mcp.json`.

## Testing

- Backend: MockMvc-Tests der Rollenmatrix pro Endpunkt-Gruppe (`@WithMockUser` bzw.
  Token-Header), Token-Filter-Tests (gültig/ungültig/widerrufen), Bootstrap-Test
  (Admin-Anlage), Audit-Tests für zentrale Aktionen.
- Frontend: Tests für AuthService, Interceptor (401-Redirect) und Guards.
- Hinweis: Bestehende Controller-/Integrationstests brauchen nach Einführung von Spring
  Security Security-Konfiguration im Test-Setup (sonst schlagen sie mit 401 fehl) — fester
  Bestandteil des Implementierungsplans.
