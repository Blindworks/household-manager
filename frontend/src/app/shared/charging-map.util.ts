import * as L from 'leaflet';
import { ChargingStation } from '../models/charging.model';
import { formatPower, stationTone } from './charging-status.util';

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

export function stationPopupText(station: ChargingStation): string {
  const lines = [
    `<strong>${escapeHtml(station.name)}</strong>`,
    `${station.free}/${station.total} frei · ${formatPower(station.maxPowerKw)}`
  ];
  if (station.address) {
    lines.push(escapeHtml(station.address));
  }
  return lines.join('<br>');
}

/** Zuhause-Marker: kleiner blauer Punkt. */
export function homeIcon(): L.DivIcon {
  return L.divIcon({ className: 'charging-home', iconSize: [14, 14], iconAnchor: [7, 7] });
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
