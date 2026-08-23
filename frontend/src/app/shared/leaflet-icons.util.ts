import * as L from 'leaflet';

/**
 * Leaflet ermittelt die Standard-Marker-Icons ueber eine relative URL zum
 * Bundle; im Angular-Build zeigt die ins Leere. Die Dateien werden deshalb
 * lokal ausgeliefert (angular.json-Assets-Glob) und hier fest verdrahtet -
 * bewusst NICHT von einem CDN: das Dashboard muss ohne Internet funktionieren.
 *
 * Mehrfaches Aufrufen ist unschaedlich; die Funktion setzt nur Optionen.
 */
export function useLocalLeafletIcons(): void {
  const iconPrototype = L.Icon.Default.prototype as L.Icon.Default & { _getIconUrl?: unknown };
  delete iconPrototype._getIconUrl;
  L.Icon.Default.mergeOptions({
    iconRetinaUrl: 'assets/leaflet/marker-icon-2x.png',
    iconUrl: 'assets/leaflet/marker-icon.png',
    shadowUrl: 'assets/leaflet/marker-shadow.png'
  });
}
