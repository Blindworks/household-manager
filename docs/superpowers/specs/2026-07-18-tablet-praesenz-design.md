# Design: Tablet-Präsenzerkennung („Wall Tablet App")

**Datum:** 2026-07-18
**Status:** Entwurf zur Review

## Ziel

Das Household-Manager-Dashboard läuft auf einem Android-Wandtablet. Eine neue
native Android-App zeigt das Dashboard im Vollbild an und erkennt per
Frontkamera, ob jemand vor dem Tablet steht. Ohne Anwesenheit wird das Display
abgedunkelt (Soft-Off), bei Bewegung wacht es sofort wieder auf. Präsenz-Wechsel
werden an das Backend gemeldet und stehen dort als Entity für Flows zur
Verfügung.

## Entscheidungen aus dem Brainstorming

| Frage | Entscheidung |
|---|---|
| Anzeige-Lösung auf dem Tablet | Eigene Android-App (WebView-Wrapper), kein Fully Kiosk |
| Display ausschalten | Soft-Off: schwarzes Overlay + Helligkeit 0 (kein Root, kein Device Owner) |
| Erkennung | Hybrid: Bewegung weckt, Gesicht hält wach |
| Backend-Anbindung | Ja: Präsenz als Binärsensor im Entity-State-Layer, nutzbar als Flow-Trigger |
| Zielgerät | Android 10+ (minSdk 29) |
| Betrieb | Backend/Frontend bleiben auf dem lokalen Server (Docker); die App ist reiner Client im LAN |

## Architektur

```
┌────────────────────── Android-Tablet ──────────────────────┐
│  tablet-app (Kotlin)                                       │
│  ┌──────────────┐   ┌─────────────────────────────────┐    │
│  │ KioskActivity │   │ PresenceDetector (CameraX)      │    │
│  │  └ WebView ───┼─┐ │  ├ MotionDetector (Frame-Diff)  │    │
│  └──────────────┘ │ │  └ FaceDetector (ML Kit)        │    │
│  ┌──────────────┐ │ └────────────┬────────────────────┘    │
│  │DisplayCtrl   │◄┼──────┐       ▼                         │
│  └──────────────┘ │  ┌───┴──────────────────┐              │
│  ┌──────────────┐ │  │ PresenceStateMachine │              │
│  │PresenceRep.──┼─┼──┤ (reiner Kotlin-Code) │              │
│  └───────┬──────┘ │  └──────────────────────┘              │
└──────────┼────────┼────────────────────────────────────────┘
           │ HTTP   │ HTTP (Dashboard-URL)
           ▼        ▼
┌────────────── Lokaler Server (Docker) ─────────────────────┐
│  Backend :8080                        Frontend :4200       │
│  POST /api/v1/tablet-presence/{id}                         │
│  TabletPresenceService → Entity-State-Layer → Flow-Engine  │
└────────────────────────────────────────────────────────────┘
```

## Android-App (`tablet-app/`, Kotlin, minSdk 29)

Neues Top-Level-Modul im Repo, eigenständiges Gradle-Projekt.

### Komponenten

- **KioskActivity** — Single-Activity mit Vollbild-WebView, lädt die
  konfigurierbare Dashboard-URL vom lokalen Server. Setzt
  `FLAG_KEEP_SCREEN_ON`, damit Android das Gerät nie selbst sperrt — die App
  allein entscheidet über hell/dunkel.
- **PresenceDetector** — CameraX-`ImageAnalysis` auf der Frontkamera mit
  niedriger Auflösung (~320×240) und wenigen Frames pro Sekunde, um CPU zu
  schonen. Zwei austauschbare Analyse-Stufen:
  - **MotionDetector**: Luma-Frame-Differenz mit Schwellwert — billig, läuft
    dauerhaft, weckt das Display.
  - **FaceDetector**: ML Kit Face Detection (Fast-Modus, vollständig lokal,
    keine Cloud) — bestätigt, dass eine Person vor dem Tablet steht, und hält
    das Display wach.
- **PresenceStateMachine** — die Hybrid-Logik als reiner, testbarer
  Kotlin-Code ohne Android-Abhängigkeiten:
  - `AUS` → Bewegung erkannt → `AN`
  - `AN` → für N Sekunden (Default 60 s, konfigurierbar) weder Bewegung noch
    Gesicht erkannt → `AUS`
- **DisplayController** — Soft-Off: schwarzes Overlay über dem WebView +
  `WindowManager.LayoutParams.screenBrightness = 0`. Aufwachen entfernt das
  Overlay und stellt die vorherige Helligkeit wieder her. Die Kamera läuft im
  dunklen Zustand weiter.
- **PresenceReporter** — meldet Zustandswechsel per HTTP an das Backend im
  LAN, plus periodischer Heartbeat (60 s). Backend nicht erreichbar → stilles
  Retry; die Display-Logik funktioniert vollständig offline.
- **SettingsScreen** — Dashboard-URL, Backend-URL, Timeout,
  Bewegungsempfindlichkeit; Ablage in SharedPreferences. Erreichbar über eine
  versteckte Geste (langes Drücken in einer Bildschirmecke), damit der
  Kiosk-Charakter erhalten bleibt.

### Netzwerk im LAN (Klartext-HTTP)

Der Server läuft im Heimnetz ohne HTTPS. Android 10+ blockiert Klartext-HTTP
standardmäßig; die App erlaubt es daher explizit per
`android:usesCleartextTraffic="true"` (bzw. Network Security Config auf das
Heimnetz beschränkt).

### Fail-safe-Prinzip

Bei Kamera- oder Erkennungsfehlern bleibt das Display **an**. Ein Wandtablet,
das fälschlich dunkel bleibt, ist das schlechtere Verhalten.

### Verteilung

Build per Gradle auf dem Entwicklungsrechner, Installation per APK-Sideload
auf das Tablet. Kein Play Store, keine Cloud-Dienste.

## Backend-Integration (Spring Boot)

- **Neue API** `POST /api/v1/tablet-presence/{tabletId}` mit Body
  `{ "present": boolean }`. Bewusst schlank; das Tablet registriert sich beim
  ersten Aufruf selbst (kein Verwaltungs-UI).
- **TabletPresenceService** — hält den letzten Zustand je Tablet und spiegelt
  ihn nach dem bestehenden Hook-Muster (try/catch um das Mapping) in den
  Entity-State-Layer als Binärsensor (`EntitySource.TABLET`, Anzeigename z.B.
  „Wandtablet Präsenz"). Damit sind Flows wie „Präsenz erkannt → Flurlicht an"
  sofort möglich.
- **Offline-Erkennung** — bleibt der Heartbeat ~3 Intervalle (180 s) aus, geht
  die Entity auf `unavailable` (per `@Scheduled`-Prüfung, analog zu den
  bestehenden Polling-Services).
- **Keine eigene Historien-Tabelle** im ersten Schritt (YAGNI) — der
  Entity-State-Layer genügt. Persistenz/Auswertung kann später ergänzt werden.

## Fehlerbehandlung

| Fehlerfall | Verhalten |
|---|---|
| Kamera nicht verfügbar / Fehler in der Analyse | Display bleibt dauerhaft an; Fehler wird geloggt |
| Backend nicht erreichbar | Display-Logik läuft lokal weiter; Reporter versucht es beim nächsten Wechsel/Heartbeat erneut |
| Heartbeat bleibt aus (Backend-Sicht) | Entity geht auf `unavailable`, Flows können darauf reagieren |
| WebView-Ladefehler | Einfache Fehlerseite mit Retry (Server-Neustart etc.) |

## Testing

- **App**: JUnit-Tests für die `PresenceStateMachine` (Timeout-Verhalten,
  Hybrid-Übergänge, Konfigurierbarkeit) mit gemockten Detektoren — die Logik
  ist bewusst Android-frei geschnitten. Detektoren und DisplayController
  werden hinter Interfaces gekapselt.
- **Backend**: Service-Tests für Zustandswechsel, Entity-Spiegelung und
  Offline-Erkennung nach dem Muster der bestehenden Tests (JUnit, ohne lokale
  DB).

## Bewusst ausgeklammert (YAGNI)

- Personenunterscheidung / Gesichts-**Wiedererkennung** — es wird nur erkannt,
  *dass* ein Gesicht da ist, keine Identität.
- Play-Store-Verteilung.
- Verwaltungs-Frontend für mehrere Tablets — die API kann mehrere `tabletId`s,
  aber es gibt kein UI dafür.
- Historien-Speicherung der Präsenzdaten.
