import { EntityState } from '../models/entity-state.model';
import { HubInsight } from './hub-insight.model';

/**
 * Ueberwachte Tuerkontakte: Entity-ID des Zigbee-Kontakts → Name der Tuer im Hub.
 * Die Entity-IDs entstehen aus den zigbee2mqtt-Friendly-Names ("Eingangstuer",
 * "Terassentuer" — letzterer traegt den Tippfehler des realen Geraets).
 */
const DOOR_CONTACTS: ReadonlyArray<{ entityId: string; label: string }> = [
  { entityId: 'binary_sensor.zigbee_eingangstuer_contact', label: 'Haustür' },
  { entityId: 'binary_sensor.zigbee_terassentuer_contact', label: 'Terrassentür' }
];

/**
 * Baut je offener Tuer eine Hub-Karte, z. B. "Haustür offen — Offen seit 17:46 Uhr."
 *
 * <p>Nur `state === 'on'` (= offen, Kontakt getrennt) erzeugt eine Karte.
 * `unavailable` ist keine Aussage ueber die Tuer — geraten wird nicht
 * (Muster `atHome`), also erscheint dann auch keine Karte.
 *
 * @param nowMs Bezugszeitpunkt fuer "heute"; steht die Tuer seit einem frueheren
 * Tag offen, nennt der Text zusaetzlich das Datum.
 */
export function buildDoorInsights(entities: EntityState[], nowMs: number): HubInsight[] {
  const insights: HubInsight[] = [];
  for (const door of DOOR_CONTACTS) {
    const entity = entities.find(candidate => candidate.entityId === door.entityId);
    if (entity?.state !== 'on') {
      continue;
    }
    insights.push({
      icon: 'door_open',
      tone: 'tertiary',
      title: `${door.label} offen`,
      text: openSinceText(entity.lastChanged, nowMs)
    });
  }
  return insights;
}

function openSinceText(lastChanged: string, nowMs: number): string {
  const since = new Date(lastChanged);
  if (isNaN(since.getTime())) {
    return 'Die Tür ist gerade offen.';
  }
  const time = since.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  if (isSameLocalDay(since, new Date(nowMs))) {
    return `Offen seit ${time} Uhr.`;
  }
  const date = since.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' });
  return `Offen seit ${date}, ${time} Uhr.`;
}

function isSameLocalDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
}
