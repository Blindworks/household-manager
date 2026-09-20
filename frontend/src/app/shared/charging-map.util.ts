import * as L from 'leaflet';
import { ChargingStation } from '../models/charging.model';
import { formatPower, formatPrice, stationTone } from './charging-status.util';

/**
 * Marker-Icon einer Ladesaeule: farbiger Kreis mit der Zahl freier Ladepunkte, Stern bei
 * Favoriten. Die Klassen `charging-marker--free|busy|unknown` und `charging-marker--favorite`
 * stylen beide Seiten in ihrer eigenen SCSS (Leaflet rendert divIcons ausserhalb der
 * Komponenten-Kapselung, deshalb per ::ng-deep bzw. global in der Seite).
 */
export function stationIcon(station: ChargingStation): L.DivIcon {
  const tone = stationTone(station);
  const label = station.total > 0 ? String(station.free) : '–';
  return L.divIcon({
    className: `charging-marker charging-marker--${tone}${station.favorite ? ' charging-marker--favorite' : ''}`,
    html: `<span class="charging-marker__count">${label}</span>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15],
    popupAnchor: [0, -14]
  });
}

/**
 * Popup-Inhalt als kleine Karte: Name, Betreiber, Adresse, dann drei Kennzahlen (frei,
 * Leistung, Preis). Die Klassen `charging-popup*` stylt jede Seite in ihrer eigenen SCSS
 * (Leaflet rendert Popups ausserhalb der Komponenten-Kapselung). Alle Texte der Quelle
 * werden escaped - Betreiber- und Adresstexte kommen von einem Fremdsystem.
 */
export function stationPopupText(station: ChargingStation): string {
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
    + `</div>`;
}

/** Zuhause-Marker: kleiner blauer Punkt. */
export function homeIcon(): L.DivIcon {
  return L.divIcon({ className: 'charging-home', iconSize: [14, 14], iconAnchor: [7, 7] });
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
