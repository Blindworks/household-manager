# Household Tablet (Wandtablet-App)

Android-Kiosk-App für das Wandtablet: zeigt das Household-Manager-Dashboard im
Vollbild-WebView und steuert das Display über Anwesenheitserkennung per
Frontkamera (Bewegung weckt, Gesicht hält wach; Soft-Off per schwarzem
Overlay + Helligkeit 0). Präsenz-Wechsel werden an
`POST <backend>/v1/tablet-presence/{tabletId}` gemeldet und stehen im
Entity-State-Layer als `binary_sensor.tablet_<id>_presence` für Flows bereit.

## Build

Voraussetzungen: JDK 17+, Android SDK (Pfad in `local.properties`:
`sdk.dir=...`).

```
.\gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Installation (Sideload)

1. Auf dem Tablet "Unbekannte Quellen" für den Datei-Manager erlauben.
2. APK aufs Tablet kopieren (USB, Netzwerkfreigabe) und installieren —
   oder per ADB: `adb install app/build/outputs/apk/debug/app-debug.apk`
3. App starten, Kamera-Berechtigung erteilen.
4. Einstellungen öffnen: **langes Drücken oben links** — Dashboard-URL,
   Backend-URL (inkl. `/api`), Tablet-ID, Display-Timeout und
   Bewegungs-Schwellwert setzen.

## Verhalten

- Ohne Kamera-Berechtigung oder bei Kamerafehlern bleibt das Display
  dauerhaft an (Fail-safe); die Abschalt-Logik ist dann deaktiviert.
- Tippen auf das dunkle Display weckt es immer.
- Backend nicht erreichbar → Display-Logik läuft lokal weiter, Meldungen
  werden beim nächsten Wechsel/Heartbeat erneut versucht.
- Bleibt der Heartbeat aus, setzt das Backend die Entität nach ~3 Minuten
  auf `unavailable`.

## Tests

```
.\gradlew.bat :app:testDebugUnitTest
```
