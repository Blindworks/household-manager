# Leaflet-Karten-Kachel im 2x2-Tablet-Raster (Task 9)

- Container immer im DOM, nie hinter `*ngIf` — sonst haengt der Kartenaufbau an
  der Reihenfolge von Datenankunft und Change Detection. `/pets` hat genau
  diesen Bug (renderMap direkt nach Signal-Set, vor *ngIf-Rendering): erster
  Aufbau laeuft ins Leere, Karte erscheint erst beim naechsten Poll.
- Fix-Muster: `@ViewChild('mapContainer') mapContainer?: ElementRef<HTMLDivElement>`,
  `ngAfterViewInit` setzt `viewReady = true` und ruft `renderMap()`. `renderMap()`
  ist idempotent (baut Map nur einmal, danach nur `marker.setLatLng`) und wird
  sowohl aus `ngAfterViewInit` als auch nach jedem Datenabruf aufgerufen — beide
  Aufrufer koennen zuerst kommen, keiner darf sich aufeinander verlassen.
- Hinweis "Keine Position" liegt als `position: absolute; inset: 0` Overlay
  *ueber* dem Kartencontainer (`&__hint--overlay`), nicht anstelle davon.
- Icon-Fix jetzt zentral in `frontend/src/app/shared/leaflet-icons.util.ts`
  (`useLocalLeafletIcons()`), von `pets.component.ts` und
  `tablet-toni.component.ts` genutzt — vorher war das eine lokale Funktion nur
  in `pets.component.ts`. Assets kommen lokal aus `assets/leaflet` (angular.json
  Assets-Glob), nie CDN.
- Karma/ChromeHeadless: `leaflet` (^1.9.4) + `@types/leaflet` sind schon in
  package.json, laufen unter Test out-of-the-box. Die 404s fuer
  marker-icon.png/marker-shadow.png im Karma-Testserver sind harmlos (Icons
  laden asynchron nach, `.leaflet-container`-Klasse steht trotzdem sofort da) —
  kein Blocker, keine Testanpassung noetig.
