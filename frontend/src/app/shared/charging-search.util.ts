/**
 * Zustand des Knopfs "In diesem Bereich suchen" auf der Tablet-Karte. Reine Funktionen, keine
 * Abhaengigkeit von Leaflet oder Angular.
 */

/**
 * Hoechste Diagonale eines durchsuchbaren Ausschnitts. Gegenstueck zu
 * `ChargingAreaSearchService.MAX_SEARCH_DIAGONAL_KM` im Backend: dort verbindlich (400), hier nur
 * Bedienhilfe, damit der Knopf gar nicht erst zu einer abgelehnten Anfrage einlaedt.
 */
export const SEARCH_MAX_DIAGONAL_KM = 30;

/** Ausschnitt in den Namen des API-Vertrags (`GET /v1/charging/search`). */
export interface SearchBox {
  fromLat: number;
  toLat: number;
  fromLon: number;
  toLon: number;
}

export type SearchButtonState = 'hidden' | 'search' | 'zoom-in';

const EARTH_RADIUS_METERS = 6_371_000;
/** Rundungsrauschen von Leaflet-Grenzen gegen den gemerkten Ausschnitt. */
const BOUNDS_TOLERANCE_DEGREES = 1e-9;

/** Haversine-Entfernung in Metern. */
function distanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
}

export function boxDiagonalKm(box: SearchBox): number {
  return distanceMeters(box.fromLat, box.fromLon, box.toLat, box.toLon) / 1000;
}

function corners(box: SearchBox): Array<[number, number]> {
  return [
    [box.fromLat, box.fromLon], [box.fromLat, box.toLon],
    [box.toLat, box.fromLon], [box.toLat, box.toLon]
  ];
}

function insideCircle(box: SearchBox, home: { lat: number; lon: number }, radiusKm: number): boolean {
  return corners(box).every(([lat, lon]) => distanceMeters(home.lat, home.lon, lat, lon) <= radiusKm * 1000);
}

function isWithin(inner: SearchBox, outer: SearchBox): boolean {
  const eps = BOUNDS_TOLERANCE_DEGREES;
  return inner.fromLat >= outer.fromLat - eps && inner.toLat <= outer.toLat + eps
    && inner.fromLon >= outer.fromLon - eps && inner.toLon <= outer.toLon + eps;
}

/**
 * `hidden`: der Ausschnitt liegt vollstaendig im bereits gepollten Suchkreis ums Zuhause oder
 * wurde (bzw. ein groesserer) schon gesucht. `zoom-in`: noch zu gross zum Suchen. Sonst `search`.
 */
export function searchButtonState(
  view: SearchBox | null,
  home: { lat: number; lon: number } | null,
  radiusKm: number | null,
  searched: SearchBox | null
): SearchButtonState {
  if (!view || !home) {
    return 'hidden';
  }
  if (radiusKm !== null && insideCircle(view, home, radiusKm)) {
    return 'hidden';
  }
  if (searched && isWithin(view, searched)) {
    return 'hidden';
  }
  return boxDiagonalKm(view) > SEARCH_MAX_DIAGONAL_KM ? 'zoom-in' : 'search';
}
