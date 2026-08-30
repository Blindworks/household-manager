import { EntityState } from '../models/entity-state.model';
import { HubInsight } from './hub-insight.model';
import { sinceText } from './insight-time.util';

/**
 * Ueberwachte Fertig-Helfer: Entity-ID des INPUT_BOOLEAN → Anzeige im Hub.
 * Gesetzt werden sie von den Flows "Waschmaschine fertig" und "Spuelmaschine
 * fertig" ueber den Node `helper-set`.
 *
 * <p>Die IDs entstehen im Backend deterministisch ueber `EntityIds.build` aus dem
 * Helfer-Namen und ueberleben sowohl ein Umbenennen als auch ein Loeschen mit
 * Neuanlegen. Bindend ist damit der NAME: nur "Waschmaschine fertig" bzw.
 * "Spuelmaschine fertig" erzeugen genau diese IDs — ein Tippfehler beim Anlegen
 * laesst die Karte wortlos ausbleiben (siehe CLAUDE.md).
 */
const FINISHED_HELPERS: ReadonlyArray<{ entityId: string; title: string; icon: string }> = [
  {
    entityId: 'input_boolean.manual_waschmaschine_fertig',
    title: 'Waschmaschine fertig',
    icon: 'local_laundry_service'
  },
  {
    entityId: 'input_boolean.manual_spuelmaschine_fertig',
    title: 'Spülmaschine fertig',
    icon: 'dishwasher_gen'
  }
];

/**
 * Baut je fertiger Maschine eine Hub-Karte, z. B. "Waschmaschine fertig — Fertig
 * seit 17:46 Uhr.". Die Karte traegt `dismissEntityId` und ist damit antippbar.
 *
 * <p>Nur `state === 'on'` erzeugt eine Karte. Ein fehlender Helfer oder
 * `unavailable` erzeugt keine — geraten wird nicht (Muster `buildDoorInsights`).
 *
 * @param nowMs Bezugszeitpunkt fuer "heute"; ist die Maschine seit einem frueheren
 * Tag fertig, nennt der Text zusaetzlich das Datum.
 */
export function buildApplianceInsights(entities: EntityState[], nowMs: number): HubInsight[] {
  const insights: HubInsight[] = [];
  for (const appliance of FINISHED_HELPERS) {
    const entity = entities.find(candidate => candidate.entityId === appliance.entityId);
    if (entity?.state !== 'on') {
      continue;
    }
    insights.push({
      icon: appliance.icon,
      tone: 'primary',
      title: appliance.title,
      text: sinceText(entity.lastChanged, nowMs, 'Fertig', 'Die Maschine ist fertig.'),
      dismissEntityId: appliance.entityId
    });
  }
  return insights;
}
