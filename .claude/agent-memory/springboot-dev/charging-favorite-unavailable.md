---
name: charging-favorite-unavailable
description: ChargingPollingService.pollFavorites unavailable-Meldung fuer Favoriten mit fehlgeschlagenem Detailabruf muss aus dem ChargingFavorite-Objekt gebaut werden, nicht aus lastReported
metadata:
  type: project
---

Beim Bauen von `ChargingPollingService` (Task 8 der Ladesaeulen-Uebersicht) gab es zwei
Mockito-/Logik-Fallstricke, die der als Referenz gegebene Testcode selbst enthielt:

1. **Gate-Bug (zwei Runden):** Ein `markUnavailable(stationId)`, das nur meldet wenn
   `lastReported` bereits einen Eintrag hat, meldet einen Favoriten NIE als unavailable, dessen
   allererster Detailabruf scheitert. Erste Korrektur baute unavailable deshalb IMMER aus dem
   `ChargingFavorite`-Objekt (stationName/operator) — das verlor dann aber `total`/`maxPowerKw`/
   `occupiedSince`, wenn der Favorit vorher schon erfolgreich gemeldet war (Review-Fund).
   Endgueltiger Fix: `reportUnavailable(ChargingFavorite)` bevorzugt den vorhandenen
   `lastReported`-Eintrag (reiche Attribute erhalten) und faellt nur ohne Vorwissen auf den
   Favoriten zurueck; `lastReported` wird bei einem Fehlschlag bewusst NICHT ueberschrieben,
   damit ein zweiter Fehlschlag in Folge weiterhin die reichen Attribute des letzten Erfolgs
   hat. Gemeinsamer Kern `unavailableUpdate(previous, fallback)`, von beiden Pfaden genutzt:
   `reportUnavailable(ChargingFavorite)` (Favorit noch in der Liste, Fehlschlag beim Abruf) und
   `reportUnavailable(String stationId)` (Favorit ganz aus der Liste verschwunden, kein
   Favorit-Objekt mehr vorhanden, ausschliesslich aus `lastReported` gebaut).

2. **Mockito-Restub-Falle** (siehe auch [[mockito-restub-after-thenthrow]]): ein zweites
   `when(mock.x()).thenThrow(...)` NACH einem bereits gestubbten `thenThrow` fuehrt den echten
   Aufruf real aus und wirft dabei die alte Exception erneut — `doThrow(...).when(mock).x()`
   verwenden.

3. Der gemeinsame `when(settingsService.getSettings())...`-Stub in `@BeforeEach` muss
   `lenient()` sein, sobald manche Tests (hier: die reinen Favoriten-Tests) ihn nie aufrufen —
   sonst `UnnecessaryStubbingException` unter `MockitoExtension` (Default: strict stubs).
