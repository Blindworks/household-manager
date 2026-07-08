# Kompakt-Ansicht für die Devices-View

**Datum:** 2026-07-07
**Status:** Genehmigt

## Ziel

Die Devices-View (`smart-device-list`) erhält eine umschaltbare kompakte Ansicht.
Die Schalter werden kleiner und für Touch-Bedienung (Tablet und Smartphone)
optimiert, sodass mehr Geräte auf einen Screen passen und sich Geräte mit dem
Finger bequem schalten lassen.

## Anforderungen (mit dem Nutzer geklärt)

- **Umschaltung:** Umschalt-Button (Normal / Kompakt), Wahl wird persistiert.
- **Kompakt-Umfang:** kleinere Karten + ganze Karte als Schaltfläche + Meta ausblenden.
- **Touch-Ziel:** responsive, fingerfreundlich auf Tablet und Smartphone.

## Design

### Umschaltung
- Segmentierter Umschalter (`Normal` / `Kompakt`) oben in `.list-actions`.
- Property `viewMode: 'normal' | 'compact'` in `SmartDeviceListComponent`.
- Persistenz in `localStorage`, Key `smartDeviceViewMode`. Standard: `normal`.
- Beim `ngOnInit` wird der gespeicherte Wert (falls vorhanden) geladen.

### Kompakte Karte (`device-card--compact`)
- **Keine Kategorisierung:** In der Kompakt-Ansicht entfallen die Typ-Gruppen
  (KASA/TAPO/MEROSS) samt Überschriften; alle Geräte erscheinen in einem einzigen
  flachen Grid (`orderedDevices`, nach Typ-Reihenfolge sortiert). Die Normal-Ansicht
  bleibt nach Typ gruppiert. Die Karte liegt in einem gemeinsamen `ng-template`.
- **Kleinere Karten:** Grid `minmax(280px, 1fr)` → `minmax(150px, 1fr)`,
  reduziertes Padding, kleinere Schrift/Icon.
- **Ganze Karte = Schalter:** absolut positionierte Overlay-Schaltfläche
  (`<button class="device-card__hit">`) füllt die Karte und ruft
  `toggleDevice(device)` auf. Trägt `aria-label` (An/Ausschalten) und
  `aria-pressed`. Bei Offline `disabled`. Das `<article>`-Markup bleibt semantisch
  sauber und wird nicht dupliziert.
- **Meta reduziert:** Status-Badge-Text entfällt, der Icon-Block wird verkleinert.
  Der Schaltzustand wird ausschließlich über Kartenfarbe (Farbstreifen oben) und
  ein kleines Power-Icon kommuniziert; ein separater AN/AUS/Offline-Text entfällt
  in der Kompakt-Ansicht. Sichtbar bleibt: Gerätename.

### Touch-Optimierung
- Overlay-Schaltfläche füllt die ganze Karte → Tap-Ziel deutlich über 44px,
  auch bei optisch kleineren Karten.
- Bestehende Logik (`toggleDevice`, `togglingDevices`, Spinner) wird
  wiederverwendet; Spinner erscheint in Kompakt zentral über der Karte.
- Responsive: auf schmalen Screens (`max-width: 768px`) nutzt das Kompakt-Grid
  2 Spalten (statt 1 wie in der Normal-Ansicht).

### Unverändert
- Normal-Ansicht, Datenfluss/Service-Aufrufe, Gruppierung nach Typ,
  Rescan/Refresh-Buttons.

## Tests
- `viewMode`-Umschaltung setzt die Property korrekt.
- Persistenz: gesetzter `viewMode` wird in `localStorage` geschrieben und beim
  Init wieder geladen.
- Klick auf die kompakte Karte (Overlay-Button) löst `toggleDevice` aus.
