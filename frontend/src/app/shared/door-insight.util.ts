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

/**
 * Fensterkontakte werden nicht einzeln gelistet, sondern am Namen erkannt: jeder
 * Zigbee-Kontakt, dessen Name mit "Fenster" beginnt (Entity-ID
 * `binary_sensor.zigbee_fenster_…_contact`). So erscheint ein
 * neu angelernter Fensterkontakt ohne Codeaenderung im Hub. Bewusst NICHT jeder
 * Kontakt: "Sensor Gas" ist auch einer (Reed-Kontakt am Gaszaehler) und steht
 * dauerhaft auf `on` — er wuerde sonst als ewig offenes Fenster erscheinen.
 */
const WINDOW_CONTACT_PREFIX = 'binary_sensor.zigbee_fenster_';
const CONTACT_SUFFIX = '_contact';

/**
 * Baut je offenem Fenster eine Hub-Karte, z. B. "Fenster Badezimmer offen — Offen seit 17:46 Uhr."
 * Gleiche Regeln wie bei den Tueren: nur `on` zaehlt, `unavailable` erzeugt keine Karte.
 * Reihenfolge nach Entity-ID, damit die Karten zwischen zwei Refreshes nicht springen.
 */
export function buildWindowInsights(entities: EntityState[], nowMs: number): HubInsight[] {
  return entities
    .filter(entity => entity.entityId.startsWith(WINDOW_CONTACT_PREFIX)
      && entity.entityId.endsWith(CONTACT_SUFFIX)
      && entity.state === 'on')
    .sort((a, b) => a.entityId.localeCompare(b.entityId))
    .map(entity => ({
      icon: 'window',
      tone: 'tertiary' as const,
      title: `${windowLabel(entity)} offen`,
      text: sinceText(entity.lastChanged, nowMs, 'Offen', 'Das Fenster ist gerade offen.')
    }));
}

/** "Fenster Badezimmer Kontakt" → "Fenster Badezimmer" (der Kontakt-Zusatz stammt vom Entity-Mapper). */
function windowLabel(entity: EntityState): string {
  return entity.displayName.replace(/\s+Kontakt$/, '');
}
