import { CanvasNode } from './flow-graph.mapper';
import { EntityState } from '../../models/entity-state.model';
import { SmartDevice } from '../../models/smart-device.model';

/**
 * Live-Status, den ein Canvas-Kästchen unter seiner Kopfzeile zeigt: welche Entität
 * bzw. welches Gerät es referenziert, in welchem Zustand das gerade ist und seit wann.
 * Reine Anzeige für den Viewer/Debug-Einsatz des Editors — es wird nichts geschaltet.
 */
export interface NodeStatus {
  /** Anzeigename der Entität bzw. des Geräts; bei Unbekanntem die rohe Referenz. */
  name: string;
  /** Roher Zustandstext (`on`, `off`, `dusk`, `unavailable`, …) oder `unbekannt`/`offline`. */
  state: string;
  /** „seit 19:52:19" bzw. mit Datum, wenn nicht heute; null ohne verwertbaren Zeitstempel. */
  since: string | null;
  tone: NodeStatusTone;
}

/** Steuert die Farbe der Statuszeile: an = grün, unavailable = rot, aus/neutral dezent. */
export type NodeStatusTone = 'on' | 'off' | 'unavailable' | 'neutral';

/** Die beiden Quellen, aus denen ein Kästchen seinen Status zieht. */
export interface NodeStatusSources {
  entities: EntityState[];
  devices: SmartDevice[];
}

/**
 * Formatiert den Schaltzeitpunkt sekundengenau — anders als {@code sinceText} aus
 * {@code shared/insight-time.util.ts}: dort geht es um eine Hub-Karte für Bewohner,
 * hier um ein Debug-Werkzeug, bei dem Sekunden zählen (Nachlaufzeit eines Melders).
 * Liegt der Zeitpunkt nicht am heutigen Tag, steht das Datum davor.
 */
export function switchedAtText(lastChanged: string, nowMs: number): string | null {
  const since = new Date(lastChanged);
  if (isNaN(since.getTime())) {
    return null;
  }
  const time = since.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  if (isSameLocalDay(since, new Date(nowMs))) {
    return `seit ${time}`;
  }
  const date = since.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' });
  return `seit ${date}, ${time}`;
}

/**
 * Ermittelt den Live-Status eines Kästchens aus seiner Konfiguration.
 *
 * <p>Trägt die Konfig eine {@code entityId}, zählt die Entität. Trägt sie eine
 * {@code deviceId}, zählt das Gerät — Zustand und Schaltzeitpunkt kommen dabei aus
 * der gespiegelten Switch-Entität ({@code source} = Gerätetyp, {@code sourceRef} =
 * Hardware-Id, dieselbe Regel wie {@code SmartDeviceEntityMapper.entityId}); ohne
 * Spiegel bleiben die Flags der Geräteliste (kein Zeitpunkt). Eine nicht auffindbare
 * Referenz wird als {@code unbekannt} gemeldet statt verschwiegen — genau so fällt
 * eine tote Referenz nach einem Umbenennen auf.
 *
 * @return null für Kästchen ohne Entitäts- oder Gerätebezug (Zeitfenster, Verzögerung, …)
 */
export function nodeStatus(node: CanvasNode, sources: NodeStatusSources, nowMs: number): NodeStatus | null {
  const entityId = node.config['entityId'];
  if (typeof entityId === 'string' && entityId.trim() !== '') {
    return entityStatus(entityId, sources.entities, nowMs);
  }
  const deviceId = parseDeviceId(node.config['deviceId']);
  if (deviceId !== null) {
    return deviceStatus(deviceId, sources, nowMs);
  }
  return null;
}

function entityStatus(entityId: string, entities: EntityState[], nowMs: number): NodeStatus {
  const entity = entities.find(e => e.entityId === entityId);
  if (!entity) {
    return unknown(entityId);
  }
  return {
    name: entity.displayName,
    state: entity.state,
    since: switchedAtText(entity.lastChanged, nowMs),
    tone: toneOf(entity.state)
  };
}

function deviceStatus(deviceId: number, sources: NodeStatusSources, nowMs: number): NodeStatus {
  const device = sources.devices.find(d => d.id === deviceId);
  if (!device) {
    return unknown(`Gerät ${deviceId}`);
  }
  const mirror = sources.entities.find(e =>
    e.domain === 'SWITCH' && e.source === device.deviceType && e.sourceRef === device.externalDeviceId);
  if (mirror) {
    return {
      name: device.deviceName,
      state: mirror.state,
      since: switchedAtText(mirror.lastChanged, nowMs),
      tone: toneOf(mirror.state)
    };
  }
  const state = !device.isOnline ? 'offline' : device.isPoweredOn ? 'on' : 'off';
  return { name: device.deviceName, state, since: null, tone: state === 'offline' ? 'unavailable' : toneOf(state) };
}

function unknown(name: string): NodeStatus {
  return { name, state: 'unbekannt', since: null, tone: 'unavailable' };
}

function toneOf(state: string): NodeStatusTone {
  switch (state) {
    case 'on': return 'on';
    case 'off': return 'off';
    case 'unavailable': return 'unavailable';
    default: return 'neutral';
  }
}

/** deviceId kommt je nach Autor als Zahl oder als String (`"29"`) — beides zählt. */
function parseDeviceId(raw: unknown): number | null {
  if (typeof raw === 'number' && Number.isInteger(raw)) {
    return raw;
  }
  if (typeof raw === 'string' && /^\d+$/.test(raw.trim())) {
    return Number(raw.trim());
  }
  return null;
}

function isSameLocalDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}
