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

export type ZigbeeDeviceCategoryKey = 'buttons' | 'water' | 'motion' | 'contacts' | 'climate' | 'light' | 'other';

export interface ZigbeeDeviceCategory {
  key: ZigbeeDeviceCategoryKey;
  title: string;
  devices: ZigbeeDevice[];
  /** Geraete, die nicht ACTIVE sind oder zigbee2mqtt nicht mehr kennt. */
  attentionCount: number;
}

/**
 * Reihenfolge = Prioritaet: ein Geraet landet in der ERSTEN passenden Kategorie. Ein
 * Bewegungsmelder mit Helligkeitswert ist ein Bewegungsmelder, ein Wassermelder mit
 * Temperatur ein Wassermelder. Erkannt wird am Suffix der Entity-ID, das der
 * ZigbeeEntityMapper aus dem Messtyp bildet (`..._occupancy`, `..._action`, …).
 */
const CATEGORY_RULES: { key: Exclude<ZigbeeDeviceCategoryKey, 'other'>; title: string; suffixes: string[] }[] = [
  { key: 'buttons', title: 'Taster', suffixes: ['_action'] },
  { key: 'water', title: 'Wassermelder', suffixes: ['_water_leak'] },
  { key: 'motion', title: 'Bewegungsmelder', suffixes: ['_occupancy'] },
  { key: 'contacts', title: 'Tür- und Fensterkontakte', suffixes: ['_contact'] },
  { key: 'climate', title: 'Klima (Temperatur, Luftfeuchte)', suffixes: ['_temperature', '_humidity', '_pressure'] },
  { key: 'light', title: 'Helligkeit', suffixes: ['_illuminance'] }
];

const OTHER_TITLE = 'Sonstige (ohne Messwerte, Router)';

export function deviceCategory(device: ZigbeeDevice): ZigbeeDeviceCategoryKey {
  const rule = CATEGORY_RULES.find(r =>
    device.entities.some(e => r.suffixes.some(suffix => e.entityId.endsWith(suffix))));
  return rule?.key ?? 'other';
}

/**
 * Einzige Gruppierungsregel der Zigbee-Seite. Leere Kategorien entfallen; innerhalb
 * einer Kategorie bleibt die Sortierung von sortDevicesForDisplay (Kranke zuerst).
 */
export function groupDevicesByCategory(devices: ZigbeeDevice[]): ZigbeeDeviceCategory[] {
  const sorted = sortDevicesForDisplay(devices);
  const all = [...CATEGORY_RULES.map(r => ({ key: r.key, title: r.title })), { key: 'other' as const, title: OTHER_TITLE }];
  return all
    .map(({ key, title }) => {
      const members = sorted.filter(d => deviceCategory(d) === key);
      return {
        key,
        title,
        devices: members,
        attentionCount: members.filter(d => !d.knownToBridge || d.health.status !== 'ACTIVE').length
      };
    })
    .filter(category => category.devices.length > 0);
}
