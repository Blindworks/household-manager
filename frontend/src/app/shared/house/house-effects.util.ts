import { EntityMap, isOn, isWindowContact } from './house-bubbles.util';
import { HOUSE_DEVICES, HouseDevice, devicesInArea } from './house-devices';
import { DEVICE_ANCHOR_HEIGHT_M, Vec3, deviceAnchors } from './house-geometry.util';

/**
 * Zustandseffekte der 3D-Hausansicht („lebendiges Haus“): was an einem Geraet in der
 * Szene leuchtet. Einzige Definition der Regeln; der Renderer zeichnet nur.
 *
 * Ein Effekt folgt genau denselben Zustaenden wie die Hinweise der Raum-Bubble
 * (`isOn`, kein Veraltungsfilter): sonst zeigte die Bubble „Fenster offen“, waehrend
 * die Szene nichts zeigt - ein ruhender Kontakt meldet sich tagelang nicht.
 * `unavailable`/`unknown` sind nie `on` und erzeugen deshalb nie einen Effekt.
 */
export type HouseEffectKind = 'light' | 'powered' | 'heating' | 'open' | 'motion' | 'running';

export interface HouseEffect {
  deviceId: string;
  kind: HouseEffectKind;
  /** 0..1, nur bei `light` aus der Helligkeit; sonst 1. */
  intensity: number;
  /** Platz der Bubble des Geraets in der Raumansicht. */
  anchor: Vec3;
  /** Bodenhoehe unter dem Anker (Lichtschein, Ringe). */
  floorY: number;
}

/** Auch eine fast ganz gedimmte Lampe bleibt in der Szene erkennbar. */
export const MIN_LIGHT_INTENSITY = 0.15;

/**
 * Platz eines Geraets in der Szene: sein Rasterplatz im Bereich, in der Reihenfolge von
 * `devicesInArea` - genau der Platz, an dem die Raumansicht seine Bubble zeichnet.
 */
export function deviceAnchor(deviceId: string): Vec3 | null {
  const device = HOUSE_DEVICES.find(entry => entry.id === deviceId);
  if (!device) {
    return null;
  }
  const devices = devicesInArea(device.area);
  return deviceAnchors(device.area, devices.length)[devices.indexOf(device)] ?? null;
}

/** Effekte aller Geraete der angefragten Bereiche; `brightness` je Schalter-Entity in Prozent. */
export function buildHouseEffects(areaIds: readonly string[], entities: EntityMap,
                                  brightness: ReadonlyMap<string, number>): HouseEffect[] {
  const effects: HouseEffect[] = [];
  for (const areaId of areaIds) {
    for (const device of devicesInArea(areaId)) {
      const effect = effectOf(device, entities, brightness);
      const anchor = effect ? deviceAnchor(device.id) : null;
      if (effect && anchor) {
        effects.push({ deviceId: device.id, ...effect, anchor, floorY: anchor.y - DEVICE_ANCHOR_HEIGHT_M });
      }
    }
  }
  return effects;
}

function effectOf(device: HouseDevice, entities: EntityMap, brightness: ReadonlyMap<string, number>)
  : { kind: HouseEffectKind; intensity: number } | null {
  switch (device.kind) {
    case 'switch':
      if (!isOn(entities, device.entityId)) {
        return null;
      }
      return device.lamp
        ? { kind: 'light', intensity: lightIntensity(brightness.get(device.entityId)) }
        : { kind: 'powered', intensity: 1 };
    case 'thermostat':
      return isOn(entities, device.heating) ? { kind: 'heating', intensity: 1 } : null;
    case 'contact':
      return isWindowContact(device) && isOn(entities, device.entityId)
        ? { kind: 'open', intensity: 1 } : null;
    case 'door':
      return isOn(entities, device.contactEntityId) ? { kind: 'open', intensity: 1 } : null;
    case 'motion':
      return isOn(entities, device.entityId) ? { kind: 'motion', intensity: 1 } : null;
    case 'appliance':
      // Der Schalter einer Maschine ist fast immer an; „laeuft“ kommt nur aus dem Helfer.
      return isOn(entities, device.runningEntityId) ? { kind: 'running', intensity: 1 } : null;
    default:
      return null;
  }
}

function lightIntensity(percent: number | undefined): number {
  if (percent === undefined || !Number.isFinite(percent)) {
    return 1;
  }
  return Math.min(1, Math.max(MIN_LIGHT_INTENSITY, percent / 100));
}
