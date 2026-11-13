import { BODY_DEVICE_IDS } from './house-appliance-bodies.util';
import { EntityMap, isOn, isWindowContact } from './house-bubbles.util';
import { HouseDevice, devicesInArea } from './house-devices';
import { Vec3, areaFloorBase } from './house-geometry.util';
import { DevicePositions, deviceAnchor } from './house-placement.util';

/**
 * Zustandseffekte der 3D-Hausansicht („lebendiges Haus“): was an einem Geraet in der
 * Szene leuchtet. Einzige Definition der Regeln; der Renderer zeichnet nur.
 *
 * Ein Effekt folgt genau denselben Zustaenden wie die Hinweise der Raum-Bubble
 * (`isOn`, kein Veraltungsfilter): sonst zeigte die Bubble „Fenster offen“, waehrend
 * die Szene nichts zeigt - ein ruhender Kontakt meldet sich tagelang nicht.
 * `unavailable`/`unknown` sind nie `on` und erzeugen deshalb nie einen Effekt.
 *
 * Ein Kontakt oder eine Haustuer, die mit einem Fenster/einer Tuer der Szene verknuepft ist
 * (`linkedDeviceIds`), zeigt „offen“ am Element selbst und gluehte sonst doppelt.
 *
 * Ein Geraet mit Koerper (`BODY_DEVICE_IDS`) zeigt „an“/„laeuft“/„heizt“ am Koerper selbst und
 * bekommt deshalb weder den blauen Punkt noch den Laufbogen noch das Heiz-Gluehen. Seit die
 * Thermostate Heizkoerper sind, haben `heating` und `running` heute kein Geraet mehr.
 */
export type HouseEffectKind = 'light' | 'powered' | 'heating' | 'open' | 'motion' | 'running';

export interface HouseEffect {
  deviceId: string;
  kind: HouseEffectKind;
  /** 0..1, nur bei `light` aus der Helligkeit; sonst 1. */
  intensity: number;
  /** Platz des Geraets (Rasterplatz oder gespeicherter Punkt, Hoehe nach Art). */
  anchor: Vec3;
  /** Bodenhoehe unter dem Anker (Lichtschein, Ringe). */
  floorY: number;
}

/** Auch eine fast ganz gedimmte Lampe bleibt in der Szene erkennbar. */
export const MIN_LIGHT_INTENSITY = 0.15;

/** Effekte aller Geraete der angefragten Bereiche; `brightness` je Schalter-Entity in Prozent. */
export function buildHouseEffects(areaIds: readonly string[], entities: EntityMap,
                                  brightness: ReadonlyMap<string, number>, positions: DevicePositions,
                                  linkedDeviceIds: ReadonlySet<string>): HouseEffect[] {
  const effects: HouseEffect[] = [];
  for (const areaId of areaIds) {
    for (const device of devicesInArea(areaId)) {
      const effect = effectOf(device, entities, brightness, linkedDeviceIds);
      const anchor = effect ? deviceAnchor(device.id, positions) : null;
      if (effect && anchor) {
        effects.push({ deviceId: device.id, ...effect, anchor, floorY: areaFloorBase(device.area) });
      }
    }
  }
  return effects;
}

function effectOf(device: HouseDevice, entities: EntityMap, brightness: ReadonlyMap<string, number>,
                  linkedDeviceIds: ReadonlySet<string>)
  : { kind: HouseEffectKind; intensity: number } | null {
  if (BODY_DEVICE_IDS.has(device.id)) {
    return null;
  }
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
      return !linkedDeviceIds.has(device.id) && isWindowContact(device) && isOn(entities, device.entityId)
        ? { kind: 'open', intensity: 1 } : null;
    case 'door':
      return !linkedDeviceIds.has(device.id) && isOn(entities, device.contactEntityId)
        ? { kind: 'open', intensity: 1 } : null;
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
