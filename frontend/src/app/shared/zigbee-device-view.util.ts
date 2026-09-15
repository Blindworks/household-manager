import { ZigbeeBridgeEvent, ZigbeeDevice, ZigbeeDeviceHealthStatus } from '../models/zigbee.model';

/**
 * Reine Anzeige-Logik der Zigbee-Seite (kein Angular): Sortierung, Badge, Texte,
 * Countdown. Einzige Definition — Seite und Tests fragen dieselben Funktionen.
 */

/** Reihenfolge der Sortierung: das Handlungsbeduerftige zuerst. */
const STATUS_RANK: Record<ZigbeeDeviceHealthStatus, number> = {
  OFFLINE: 0,
  SILENT: 0,
  UNKNOWN: 0,
  INTERVIEW: 0,
  ACTIVE: 1
};

export function sortDevicesForDisplay(devices: ZigbeeDevice[]): ZigbeeDevice[] {
  return [...devices].sort((a, b) =>
    STATUS_RANK[a.health.status] - STATUS_RANK[b.health.status]
    || a.friendlyName.localeCompare(b.friendlyName, 'de', { sensitivity: 'base' }));
}

export interface HealthBadge {
  label: string;
  cssClass: string;
}

/**
 * Exhaustiv ueber den Typ: ein sechster Status wird zum Compilerfehler statt still auf
 * das falsche Badge zu landen (Muster presenceRingClass).
 */
const BADGES: Record<ZigbeeDeviceHealthStatus, HealthBadge> = {
  ACTIVE: { label: 'Aktiv', cssClass: 'badge--active' },
  SILENT: { label: 'Verstummt', cssClass: 'badge--silent' },
  OFFLINE: { label: 'Offline', cssClass: 'badge--offline' },
  UNKNOWN: { label: 'Unbekannt', cssClass: 'badge--neutral' },
  INTERVIEW: { label: 'Interview', cssClass: 'badge--neutral' }
};

export function healthBadge(status: ZigbeeDeviceHealthStatus): HealthBadge {
  return BADGES[status];
}

export function silentText(silentSeconds: number | null): string {
  if (silentSeconds == null) {
    return 'nie gehört';
  }
  const minutes = Math.floor(silentSeconds / 60);
  if (minutes < 1) {
    return 'zuletzt gehört vor weniger als einer Minute';
  }
  if (minutes < 60) {
    return `zuletzt gehört vor ${minutes} ${minutes === 1 ? 'Minute' : 'Minuten'}`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    const rest = minutes % 60;
    return rest === 0
      ? `zuletzt gehört vor ${hours} Std.`
      : `zuletzt gehört vor ${hours} Std. ${rest} Min.`;
  }
  const days = Math.floor(hours / 24);
  return `zuletzt gehört vor ${days} ${days === 1 ? 'Tag' : 'Tagen'}`;
}

/** Countdown aus dem Server-Ende (permitJoinEnd), nie negativ. */
export function permitJoinRemainingSeconds(permitJoinEnd: string | null, nowMs: number): number {
  if (!permitJoinEnd) {
    return 0;
  }
  const end = Date.parse(permitJoinEnd);
  if (isNaN(end)) {
    return 0;
  }
  return Math.max(0, Math.round((end - nowMs) / 1000));
}

export function bridgeEventText(event: ZigbeeBridgeEvent): string {
  const name = event.friendlyName ?? event.ieeeAddress ?? 'Unbekanntes Gerät';
  switch (event.type) {
    case 'device_joined':
      return `${name} beigetreten`;
    case 'device_interview':
      if (event.status === 'successful') {
        const detail = [event.model, event.vendor].filter(Boolean).join(', ');
        return detail ? `${name}: Interview erfolgreich (${detail})` : `${name}: Interview erfolgreich`;
      }
      if (event.status === 'failed') {
        return `${name}: Interview fehlgeschlagen`;
      }
      return `${name}: Interview läuft …`;
    case 'device_announce':
      return `${name} hat sich gemeldet`;
    case 'device_leave':
      return `${name} hat das Netz verlassen`;
  }
}
