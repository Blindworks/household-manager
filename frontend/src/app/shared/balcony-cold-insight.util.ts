import { EntityState } from '../models/entity-state.model';
import { HubInsight } from './hub-insight.model';
import { elapsedText } from './insight-time.util';

/** IKEA-Kontakt der Balkontuer im Schlafzimmer (Id haengt an der Geraete-Id am Dirigera-Hub). */
export const BALCONY_DOOR_ENTITY_ID = 'binary_sensor.dirigera_f3992ae92ca54fb6b276c2c4d5f832c9_1_contact';

/** Amazon-Luftmonitor im Schlafzimmer. */
export const BEDROOM_TEMPERATURE_ENTITY_ID = 'sensor.alexa_gaj230043055005a_temperature';

/**
 * Vorwarnung, bevor Flow #30 („Warnung, wenn die Balkontuer lange offen ist und das
 * Schlafzimmer auskuehlt“) per Telegram, Push und Alexa meldet. Der Flow warnt nach
 * 15 Min. offener Tuer unter 14 °C; die Karte kommt bewusst etwas frueher.
 * Diese Werte stehen damit zweimal (hier und im Flow) — beim Anpassen beide nachziehen.
 */
export const PRE_WARNING_OPEN_MINUTES = 10;
export const PRE_WARNING_BELOW_CELSIUS = 15;

/** Aelter als das gilt der Temperaturwert nicht mehr als Aussage (Klima-Kachel: 60 min). */
const TEMPERATURE_STALE_MS = 60 * 60_000;

/**
 * Baut die Hub-Karte „Schlafzimmer kuehlt aus“, wenn die Balkontuer seit mindestens
 * {@link PRE_WARNING_OPEN_MINUTES} Minuten offen ist und das Schlafzimmer unter
 * {@link PRE_WARNING_BELOW_CELSIUS} °C liegt. Sonst `null`.
 *
 * <p>Nichts wird geraten: Tuer nicht explizit `on`, unlesbarer Oeffnungszeitpunkt,
 * fehlende, nicht numerische oder veraltete Temperatur ergeben keine Karte — die
 * normale Fensterkarte „Balkontür offen“ bleibt dann stehen.
 */
export function buildBalconyColdInsight(
  contacts: EntityState[],
  bedroomTemperature: EntityState | undefined,
  nowMs: number
): HubInsight | null {
  const door = contacts.find(entity => entity.entityId === BALCONY_DOOR_ENTITY_ID);
  if (door?.state !== 'on') {
    return null;
  }
  const openSinceMs = new Date(door.lastChanged).getTime();
  if (isNaN(openSinceMs) || nowMs - openSinceMs < PRE_WARNING_OPEN_MINUTES * 60_000) {
    return null;
  }
  const celsius = readFreshCelsius(bedroomTemperature, nowMs);
  if (celsius === null || celsius >= PRE_WARNING_BELOW_CELSIUS) {
    return null;
  }
  const temperature = celsius.toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  return {
    icon: 'ac_unit',
    tone: 'error',
    title: 'Schlafzimmer kühlt aus',
    text: `Balkontür offen, nur noch ${temperature} °C. `
      + elapsedText(door.lastChanged, nowMs, 'Offen', 'Die Balkontür ist offen.')
  };
}

function readFreshCelsius(entity: EntityState | undefined, nowMs: number): number | null {
  if (!entity) {
    return null;
  }
  const updatedMs = new Date(entity.lastUpdated).getTime();
  if (isNaN(updatedMs) || nowMs - updatedMs > TEMPERATURE_STALE_MS) {
    return null;
  }
  const value = Number(entity.state);
  return entity.state.trim() !== '' && Number.isFinite(value) ? value : null;
}
