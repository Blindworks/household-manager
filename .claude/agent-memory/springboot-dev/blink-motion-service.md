---
name: blink-motion-service
description: BlinkMotionService (Task 3, event.blink_<id>_motion + In-Memory letzte Bewegung); Verifikation von EntityIds.build und Map.of-Nullrisiko
metadata:
  type: project
---

Task 3 des Plans `docs/superpowers/specs/.../2026-08-27-blink-bewegung-tablet-schalten.md`
implementiert (`backend/.../blink/BlinkMotionService.java` + Test, Commit d9b3b0a auf
`feature/blink-bewegung-tablet-schalten`), Task 4 (Webhook-Endpunkt + Kameraliste) und
Task 5 (Security) folgen separat.

**Verifiziert:** `EntitySource.BLINK` existierte bereits (fuer Task-1/2 Scharf-Status).
`EntityIds.build(EVENT, BLINK, "123", "motion")` ergibt tatsaechlich `event.blink_123_motion`
(`EntityIds.build`: `<domain>.<source>_<slug(ref)>_<slug(suffix)>`) — Plan-Annahme stimmte.

**Muster:** eng an [[vision-integration]] (`VisionRecognitionService.fireEventSafely`)
angelehnt — Event-Feuern in eigenem try/catch, unabhaengig vom In-Memory-Merken
(ConcurrentHashMap, Muster `NetworkDeviceStatusMonitor`, ueberlebt keinen Neustart).

**Offener Punkt, nicht behoben (bewusst, da ausserhalb des Auftrags):** `Map.of("cameraName",
motion.cameraName(), ...)` wirft NPE, falls der Webhook (Task 4, fremde Eingabe) eines der
Felder `null` liefert. Im Plan-Test sind alle Felder belegt, deckt das also nicht ab. Für
Task 4 relevant: entweder am Controller validieren oder in `BlinkMotionService` defensiv
mit `HashMap`+Null-Filter statt `Map.of` arbeiten.

**Mutationsproben bestaetigt:** try/catch entfernen -> genau `eventFehlerVerhindertDasMerkenNicht`
faellt; Entity-Id-Suffix aendern -> genau `feuertEreignisJeBewegung` faellt.
