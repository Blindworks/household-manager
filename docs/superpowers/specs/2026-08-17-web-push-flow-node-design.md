# Web Push für die PWA + Flow-Node `push-send` — Design

**Datum:** 2026-08-17
**Status:** Vom Nutzer freigegeben (Design und Spec vorab bestätigt)

## Ziel

Push-Benachrichtigungen aus der Flow-Engine auf das iPhone (und andere Geräte) über
Standard-Web-Push in die bestehende PWA. Neuer Aktions-Node `push-send` analog zu
`telegram-send`, plus die nötige Backend- und Frontend-Infrastruktur
(VAPID-Schlüssel, Subscription-Verwaltung, Anmelde-Seite).

## Entscheidungen (mit dem Nutzer geklärt)

- **Adressierung:** Standard = alle abonnierten Geräte; optionales Node-Feld
  `userId` schränkt auf die Geräte eines Nutzers ein (analog zum optionalen
  `chatId` bei `telegram-send`).
- **UI:** Eigene Seite „Benachrichtigungen" (`pages/notifications/`, Route
  `/notifications`, Navi unter Smart Home).
- **Scope:** Baustein bauen **und** die bestehenden aktiven Telegram-Flows via
  flow-mcp um einen parallelen `push-send`-Zweig ergänzen (erst nach dem
  Prod-Deploy, siehe unten).
- **Technik:** `nl.martijndwars:web-push` + BouncyCastle (Ansatz 1). Kein
  Krypto-Eigenbau, kein ntfy-Sidecar.

## Randbedingungen

- **iOS-Regeln:** Web Push funktioniert auf iOS erst ab 16.4 und **nur in der
  zum Home-Bildschirm hinzugefügten PWA**; die Berechtigungsanfrage muss aus
  einer Nutzer-Geste heraus erfolgen (Button, kein Auto-Prompt).
- **Zustellweg:** Immer über die Push-Dienste von Apple/Google/Mozilla
  (`web.push.apple.com` etc.) — nicht übers LAN. Das Backend braucht nur
  ausgehenden Internetzugang (vorhanden). Payload ist Ende-zu-Ende
  verschlüsselt (`aes128gcm`), der Push-Dienst kann sie nicht lesen. Bewusst
  akzeptierte Erweiterung des LAN-only-Trade-offs.
- **Voraussetzung Rollout:** Der noch offene PWA-/HTTPS-Rollout (ca.crt auf dem
  iPhone installiert **und** unter Zertifikatsvertrauen aktiviert, Zugriff über
  :4443) muss stehen, sonst lässt sich die PWA nicht installieren.
- **iOS-Eigenheit:** iOS lässt Subscriptions verfallen bzw. entzieht der PWA
  die Berechtigung bei längerer Nichtnutzung. Gegenmittel: Selbstbereinigung
  über 404/410 (siehe unten) plus sichtbare Geräteliste.

## Datenmodell (Liquibase `20260817-0047`)

Tabelle `push_subscription`:

| Spalte | Typ | Anmerkung |
|---|---|---|
| `id` | BIGINT PK auto | |
| `user_id` | BIGINT FK → `app_user` | `ON DELETE CASCADE` — gelöschter Nutzer räumt seine Geräte mit ab |
| `endpoint` | VARCHAR(500), UNIQUE | identifiziert das Gerät beim Push-Dienst |
| `p256dh_key` | VARCHAR(255) | Client-Public-Key der Subscription |
| `auth_secret` | VARCHAR(255) | Auth-Secret der Subscription |
| `device_label` | VARCHAR(255) | aus dem User-Agent abgeleitet, für die Geräteliste |
| `created_at` | DATETIME | |
| `last_used_at` | DATETIME NULL | letzter erfolgreicher Versand |

**VAPID-Schlüsselpaar** in `application_settings` (Kategorie `PUSH_VAPID`,
Public/Private Base64-URL-kodiert), **beim ersten Start automatisch erzeugt**.
Bewusst keine Env-Variable: erspart den Rollout-Stolperstein „Env vergessen →
still tot" (zweimal real dokumentiert bei Service-Tokens). Kein manueller
Rollout-Schritt.

## Backend (`backend/src/main/java/com/household/manager/push/`)

- **`VapidKeyService`**: erzeugt beim ersten Zugriff das Schlüsselpaar
  (P-256), persistiert es in `application_settings`, liefert es danach von dort.
- **`PushSubscriptionService`**: Anmelden (**Upsert per `endpoint`** — erneutes
  Abonnieren desselben Geräts erzeugt keine Dublette, aktualisiert Schlüssel
  und Besitzer), Abmelden (nur eigene), eigene Geräte auflisten.
- **`PushNotificationService`**: Versand über die Library. **Fire-and-forget
  nach Telegram-Muster: wirft nie**; Fehler einzelner Geräte stoppen die
  anderen nicht (warn-Log). Antwortet der Push-Dienst **404 oder 410, wird die
  Subscription gelöscht** (verfallen). Keine Subscriptions global → debug-Log,
  keine Subscriptions für `userId` → warn-Log. Methoden: `sendToAll(title, body)` und
  `sendToUser(userId, title, body)`.
- **Controller `/api/v1/push`**:
  - `GET /vapid-public-key` — für die Anmeldung im Frontend
  - `GET /subscriptions` — nur die eigenen
  - `POST /subscriptions` — Subscription des Browsers speichern
  - `DELETE /subscriptions/{id}` — nur eigene (sonst 404)
  - `POST /test` — Testnachricht an die eigenen Geräte
- **JPA-Repository** liegt in `com.household.manager.repository`
  (`JpaConfig` scannt nur dieses Paket).
- **Security:** Keine neuen Matcher-Zeilen. Lesen fällt unter die generische
  `GET /v1/**`-KIOSK-Regel (unkritisch: nur eigene Geräte sichtbar), Schreiben
  unter die `anyRequest`-MEMBER-Regel (das KIOSK-Wandtablet kann in der
  Android-WebView ohnehin kein Web Push). `SecurityRulesTest` hält beide
  Richtungen fest (Muster Futtervorrat).
- **Audit:** `push.subscribe` / `push.unsubscribe` (Test-Versand ohne Audit).
- **Dependencies:** `nl.martijndwars:web-push` + BouncyCastle (`bcprov-jdk18on`).

## Flow-Node `push-send`

Analog `TelegramSendNodeHandler` (`flowengine/nodes/PushSendNodeHandler.java`):

- **Felder:** `message` (STRING, Pflicht), `title` (STRING, optional, Default
  „Household Manager"), `userId` (STRING, optional; leer = alle abonnierten
  Geräte; muss numerisch sein — Validierungsfehler sonst).
- **Platzhalter** in `title` und `message`: `{entityId}`, `{newState}`,
  `{oldState}` (identisch zu `telegram-send`).
- **Verhalten:** ein Ausgangs-Port, gibt die Message unverändert weiter;
  Versandfehler schluckt der `PushNotificationService` — der Flow läuft weiter.
  Unbekannte `userId` beim Versand → warn-Log (nicht validierbar, Nutzer können
  nach dem Deploy des Flows gelöscht werden).
- Klick auf die Benachrichtigung öffnet das Dashboard (`/`).

## Frontend

- **`PushService`** (`services/push.service.ts`) um Angulars `SwPush`:
  `isSupported`, Anmeldung (`requestSubscription` mit dem VAPID-Public-Key vom
  Backend, dann `POST /v1/push/subscriptions`), Abmelden (lokal
  `unsubscribe()` + `DELETE`), eigene Geräte laden, Testnachricht auslösen.
- **Payload-Format** ist das ngsw-Notification-Schema — der vorhandene
  `ngsw-worker.js` zeigt die Benachrichtigung selbst an, **kein eigener
  Service-Worker-Code**:

  ```json
  {
    "notification": {
      "title": "…",
      "body": "…",
      "data": { "onActionClick": { "default": { "operation": "openWindow", "url": "/" } } }
    }
  }
  ```

- **Seite „Benachrichtigungen"** (`pages/notifications/`, Route
  `/notifications` mit `authGuard`, Navi unter Smart Home):
  - Aktivieren-Button (Berechtigungsanfrage nur auf Klick — iOS-Pflicht)
  - Statusanzeige: aktiv auf diesem Gerät / nicht unterstützt / Berechtigung
    verweigert
  - Liste der eigenen Geräte (`device_label`, angemeldet am) mit Entfernen
  - Testnachricht-Button
  - Sichtbarer iOS-Hinweis: funktioniert nur in der **installierten** PWA
    (Home-Bildschirm), nicht im Safari-Tab

## Flows ergänzen (nach dem Prod-Deploy)

Die bestehenden aktiven Telegram-Flows bekommen via flow-mcp einen parallelen
`push-send`-Zweig (gleicher Trigger, beide Kanäle). **Zwingend erst nach dem
Prod-Deploy des neuen Backends** — vorher kennt die Flow-Validierung den
Node-Typ nicht und `flow_deploy` schlägt fehl. Welche Flows genau, wird zu dem
Zeitpunkt per `flow_list` aus Prod gelesen (Stand heute erwartet: Flows 1–3
sowie der Zigbee-Ausfall-Flow; der Futtervorrat-Warnflow, falls schon angelegt).

## Fehlerbehandlung — Zusammenfassung

| Fall | Verhalten |
|---|---|
| Push-Dienst antwortet 404/410 | Subscription löschen (Selbstbereinigung) |
| Push-Dienst nicht erreichbar / sonstiger Fehler | warn-Log, restliche Geräte weiter, Flow läuft weiter |
| Keine Subscriptions global | debug-Log, kein Fehler |
| Keine Subscriptions für `userId` | warn-Log, kein Fehler |
| VAPID-Keys fehlen/unlesbar | beim Start neu erzeugen; Lesen wirft nie in den Versandpfad |
| Browser ohne Push-Support (Wandtablet-WebView) | Seite zeigt „nicht unterstützt", kein Fehler |

## Tests

- **`PushSendNodeHandlerTest`**: validate (message fehlt, `userId` nicht
  numerisch), Platzhalter-Rendering, Zielauswahl (mit/ohne `userId`).
- **`PushNotificationServiceTest`**: 410 → Subscription gelöscht; Fehler eines
  Geräts stoppt die anderen nicht; wirft nie.
- **`PushSubscriptionServiceTest`**: Upsert per Endpoint; Abmelden nur eigene.
- **`SecurityRulesTest`**: Lesen KIOSK erlaubt, Schreiben KIOSK verboten /
  MEMBER erlaubt.

## Bekannte Grenzen (bewusst akzeptiert)

- Zustellung über Apple/Google-Cloud (Inhalt E2E-verschlüsselt) — Erweiterung
  des LAN-only-Trade-offs.
- iOS kann Berechtigungen bei längerer Nichtnutzung der PWA entziehen; sichtbar
  nur über die Geräteliste bzw. ausbleibende Nachrichten.
- Keine Zustell-Garantie und keine Lesebestätigung — für kritische Meldungen
  bleibt Telegram der zweite Kanal (beide parallel in den Flows).
