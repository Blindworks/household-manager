---
name: blink-motion-webhook
description: Task 4 des Blink-Bewegung-Plans - POST /v1/blink/motion (SERVICE) + Anreicherung von GET /cameras um letzte Bewegung
metadata:
  type: project
---

Umgesetzt 2026-08-27 (Commit 30651af, Branch feature/blink-bewegung-tablet-schalten):

- `BlinkController.reportMotion` (`POST /v1/blink/motion`, 204) reicht die Sidecar-Bewegungsmeldungen
  an `BlinkMotionService.processMotions` durch. Security: `SecurityConfig` — die Zeile
  fuer `/v1/vision/recognitions`/`/v1/vision/heartbeat` (SERVICE_AUTHORITY, deutlich vor der
  generischen `anyRequest().hasRole("MEMBER")`) wurde um `/v1/blink/motion` erweitert statt eine
  neue Zeile anzulegen — gleiches Muster, gleiche Reihenfolge.
- `GET /v1/blink/cameras` liefert jetzt `BlinkCameraService.CameraResponse` (eigener Record: die 7
  Sidecar-Felder unveraendert plus `lastMotionAt`/`lastMotionClipId`, null wenn `BlinkMotionService.lastMotion`
  leer ist) statt direkt `BlinkSidecarClient.SidecarCamera`. `BlinkCameraService` bekam `BlinkMotionService`
  als vierte `final`-Abhaengigkeit — alle Tests mussten auf den neuen Konstruktor umgestellt werden
  (Mockito-Konstruktion per Hand, kein Spring-Context in diesem Unit-Test).
- Pflichtfeld-Pruefung in `BlinkMotionService.processMotions`: `isIncomplete(motion)` (null-Check auf
  motion selbst + alle vier Felder) ueberspringt+loggt eine kaputte Meldung, statt dass `Map.of(...)` in
  `fireEventSafely` bei einem `null`-Wert eine `NullPointerException` wirft und damit die Verarbeitung
  der **uebrigen** Meldungen desselben Webhook-Aufrufs verhindert. Webhook nimmt Fremdeingaben entgegen —
  realer Pfad, kein theoretisches Risiko.
- **`SecurityRulesTest` hatte vor diesem Task ueberhaupt kein SERVICE_AUTHORITY-Testmuster** — der
  Plantext behauptete "Muster der Vision-Webhooks uebernehmen", aber es existierte keine Vorlage
  irgendwo im Testbaum (grep ueber ganzes `src/test` leer). Selbst rekonstruiert aus dem Slice-Kommentar
  oben in der Datei: `BlinkController` ist NICHT im `@WebMvcTest(controllers = {...})`-Slice, also
  bedeutet eine erlaubte Rolle 404 (kein Controller gefunden), eine verbotene 403 (Security greift vor
  dem Dispatch). `@WithMockUser(authorities = SecurityConfig.SERVICE_AUTHORITY)` vergibt die rohe
  Authority ohne `ROLE_`-Praefix, passend zu `hasAuthority(...)`.
- Mutationsprobe bestaetigt: SERVICE-Zeile testweise entfernt -> `/v1/blink/motion` faellt durch auf
  `anyRequest().hasRole("MEMBER")` -> ADMIN-Test (403 erwartet) wird 404, SERVICE-Test (404 erwartet)
  wird 403. Beide Tests kippen wie gefordert; Zeile danach zurueckgesetzt, 98/98 gruen.

Siehe auch [[blink-motion-service]] fuer die vorgelagerte `BlinkMotionService`-Grundlage (Task 3),
[[blink-security-rules]] fuer die uebrigen Blink-Sicherheitsregeln.
