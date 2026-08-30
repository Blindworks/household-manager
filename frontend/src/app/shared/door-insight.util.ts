import { EntityState } from '../models/entity-state.model';
import { HubInsight } from './hub-insight.model';
import { sinceText } from './insight-time.util';

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
      text: sinceText(entity.lastChanged, nowMs, 'Offen', 'Die Tür ist gerade offen.')
    });
  }
  return insights;
}
