import * as L from 'leaflet';
import { ChargePoint, ChargingStation } from '../models/charging.model';
import { formatOccupiedDuration, formatPower, formatPrice, stationTone } from './charging-status.util';

/**
 * Marker-Icon einer Ladesaeule: farbiger Kreis mit der Zahl freier Ladepunkte, Stern bei
 * Favoriten. Die Klassen `charging-marker--free|busy|unknown` und `charging-marker--favorite`
 * stylen beide Seiten in ihrer eigenen SCSS (Leaflet rendert divIcons ausserhalb der
 * Komponenten-Kapselung, deshalb per ::ng-deep bzw. global in der Seite).
 */
export type MarkerShape = 'dot' | 'pin';

/** Rahmen und Anker je Form; bei der Tropfenform liegt der Anker auf der Spitze. */
export const MARKER_GEOMETRY: Record<MarkerShape, { size: [number, number]; anchor: [number, number]; popup: [number, number] }> = {
  dot: { size: [30, 30], anchor: [15, 15], popup: [0, -14] },
  pin: { size: [30, 40], anchor: [15, 40], popup: [0, -38] }
};

/**
 * Marker-Icon einer Ladesaeule: farbige Form mit der Zahl freier Ladepunkte, Stern bei
 * Favoriten. `dot` ist ein Kreis um den Standort (Website), `pin` eine Tropfenform, deren
 * SPITZE auf dem Standort steht - der Anker muss dann auf der Spitze liegen, sonst wandert
 * der Pin beim Zoomen um den festen Pixelversatz gegen die Karte (real passiert).
 * Die Klassen `charging-marker--*` stylt jede Seite in ihrer eigenen SCSS (Leaflet rendert
 * divIcons ausserhalb der Komponenten-Kapselung, deshalb per ::ng-deep).
 */
export function stationIcon(station: ChargingStation, shape: MarkerShape = 'dot'): L.DivIcon {
  const tone = stationTone(station);
  const label = station.total > 0 ? String(station.free) : '–';
  const geometry = MARKER_GEOMETRY[shape];
  return L.divIcon({
    className: `charging-marker charging-marker--${shape} charging-marker--${tone}`
      + `${station.favorite ? ' charging-marker--favorite' : ''}`,
    html: `<span class="charging-marker__count">${label}</span>`,
    iconSize: geometry.size,
    iconAnchor: geometry.anchor,
    popupAnchor: geometry.popup
  });
}

/**
 * Popup-Inhalt als kleine Karte: Name, Betreiber, Adresse, dann drei Kennzahlen (frei,
 * Leistung, Preis). Die Klassen `charging-popup*` stylt jede Seite in ihrer eigenen SCSS
 * (Leaflet rendert Popups ausserhalb der Komponenten-Kapselung). Alle Texte der Quelle
 * werden escaped - Betreiber- und Adresstexte kommen von einem Fremdsystem.
 */
export function stationPopupText(station: ChargingStation, now: Date = new Date()): string {
  const tone = stationTone(station);
  const facts = [
    `<div class="charging-popup__fact"><span class="charging-popup__value charging-popup__value--${tone}">`
      + `${station.free}<small>/${station.total}</small></span><span class="charging-popup__label">frei</span></div>`,
    `<div class="charging-popup__fact"><span class="charging-popup__value">${escapeHtml(formatPower(station.maxPowerKw))}`
      + `</span><span class="charging-popup__label">Leistung</span></div>`
  ];
  if (station.pricePerKwh !== undefined && station.pricePerKwh !== null) {
    facts.push(`<div class="charging-popup__fact"><span class="charging-popup__value">`
      + `${escapeHtml(formatPrice(station.pricePerKwh))}</span><span class="charging-popup__label">EnBW-Tarif</span></div>`);
  }
  const subtitle = [station.operator, station.address].filter(s => !!s).map(s => escapeHtml(s!)).join(' · ');
  return `<div class="charging-popup">`
    + `<div class="charging-popup__title">${station.favorite ? '<span class="charging-popup__star">★</span>' : ''}`
    + `${escapeHtml(station.name)}</div>`
    + (subtitle ? `<div class="charging-popup__subtitle">${subtitle}</div>` : '')
    + `<div class="charging-popup__facts">${facts.join('')}</div>`
    + chargePointsHtml(station.chargePoints, now)
    + `</div>`;
}

/**
 * Ladepunkte einzeln (nur Favoriten liefern sie): Leistung, Status, bei Belegung die Dauer
 * ("seit 38 min" / "seit mind. 38 min") und der Preis. Die Dauer wird beim Bauen des Popups
 * gerechnet; die Seiten tauschen die Marker mit jedem Refresh aus, sie altert also hoechstens
 * eine Minute.
 */
function chargePointsHtml(points: ChargePoint[] | undefined, now: Date): string {
  if (!points || points.length === 0) {
    return '';
  }
  const rows = points.map(point => {
    const status = point.status.toLowerCase();
    const label = pointLabel(point);
    const since = point.status === 'OCCUPIED'
      ? formatOccupiedDuration(point.occupiedSince, point.minimumDuration, now) : '';
    const price = point.pricePerKwh !== undefined && point.pricePerKwh !== null ? formatPrice(point.pricePerKwh) : '';
    return `<li class="charging-popup__point charging-popup__point--${status}">`
      + `<span class="charging-popup__point-power">${escapeHtml(formatPower(point.maxPowerKw))}</span>`
      + `<span class="charging-popup__point-status">${label}</span>`
      + (since ? `<span class="charging-popup__point-since">${escapeHtml(since)}</span>` : '')
      + (price ? `<span class="charging-popup__point-price">${escapeHtml(price)}</span>` : '')
      + `</li>`;
  });
  return `<ul class="charging-popup__points">${rows.join('')}</ul>`;
}

function pointLabel(point: ChargePoint): string {
  switch (point.status) {
    case 'FREE': return 'frei';
    case 'OCCUPIED': return 'belegt';
    case 'OUT_OF_SERVICE': return 'außer Betrieb';
    case 'UNKNOWN': return 'unbekannt';
  }
}

/** Zuhause-Marker: kleiner blauer Punkt. */
export function homeIcon(): L.DivIcon {
  return L.divIcon({ className: 'charging-home', iconSize: [14, 14], iconAnchor: [7, 7] });
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
