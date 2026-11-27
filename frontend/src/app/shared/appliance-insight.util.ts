import { EntityState } from '../models/entity-state.model';
import { HubInsight } from './hub-insight.model';
import { elapsedText, sinceText } from './insight-time.util';

/** Eine im Hub ueberwachte Maschine: Helfer-Entity-ID plus Anzeige. */
interface ApplianceHelper {
  readonly entityId: string;
  readonly title: string;
  readonly icon: string;
  /** Leistungssensor der Steckdose, an der die Maschine haengt (nur Lauf-Helfer). */
  readonly powerEntityId?: string;
}

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
const FINISHED_HELPERS: ReadonlyArray<ApplianceHelper> = [
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
 * Ueberwachte Laeuft-Helfer, gesetzt von denselben Flows: der Trigger "Leistung
 * ueber 50 W" schaltet sie ein, der Trigger "unter 5 W fuer 10 Minuten" wieder aus.
 * Beide Flanken setzen immer auch den zugehoerigen Fertig-Helfer — "laeuft" und
 * "fertig" sind damit strukturell exklusiv.
 *
 * <p>Fuer die Namensbindung gilt dasselbe wie bei {@link FINISHED_HELPERS}: nur die
 * Helfer "Waschmaschine laeuft" bzw. "Spuelmaschine laeuft" (mit Umlaut) erzeugen
 * genau diese IDs.
 */
const RUNNING_HELPERS: ReadonlyArray<ApplianceHelper> = [
  {
    entityId: 'input_boolean.manual_waschmaschine_laeuft',
    title: 'Waschmaschine läuft',
    icon: 'local_laundry_service',
    powerEntityId: 'sensor.meross_2112156531504590863548e1e9817420_power'
  },
  {
    entityId: 'input_boolean.manual_spuelmaschine_laeuft',
    title: 'Spülmaschine läuft',
    icon: 'dishwasher_gen',
    powerEntityId: 'sensor.meross_2205060757549251080148e1e991c4f9_power'
  }
];

/**
 * Below this power the machine counts as stopped. Mirrors the "machine finished" flows
 * (trigger "power below 5 W for 10 minutes"), which live in the flow engine — both
 * copies have to change together.
 */
export const APPLIANCE_STOPPED_BELOW_WATTS = 5;

/** A power reading older than this is not trusted (Meross reports every minute). */
const POWER_READING_MAX_AGE_MS = 5 * 60_000;

/** What a fresh power reading says about a machine whose running helper is on. */
export type ApplianceRunCheck = 'stopped' | 'running' | 'unknown';

/**
 * Checks the plug of a machine against the threshold of the flows: `stopped` only for a
 * fresh, reachable reading below {@link APPLIANCE_STOPPED_BELOW_WATTS}. A missing,
 * unreachable, non-numeric or stale reading is `unknown` — the card is never cleared on
 * a guess.
 */
export function checkApplianceRun(runningHelperId: string, entities: EntityState[], nowMs: number): ApplianceRunCheck {
  const watts = freshPowerWatts(entities, RUNNING_HELPERS.find(h => h.entityId === runningHelperId)?.powerEntityId, nowMs);
  if (watts === null) {
    return 'unknown';
  }
  return watts < APPLIANCE_STOPPED_BELOW_WATTS ? 'stopped' : 'running';
}

/**
 * Baut je fertiger Maschine eine Hub-Karte ("Waschmaschine fertig — Fertig seit
 * 17:46 Uhr.") und je laufender Maschine eine zweite ("Waschmaschine läuft — Läuft
 * seit 42 Minuten.").
 *
 * <p>Die fertigen Maschinen stehen voran: sie verlangen eine Handlung, die laufenden
 * sind reiner Statusbericht. Entsprechend traegt nur die Fertig-Karte ein
 * `dismissEntityId`. The running card carries `checkApplianceEntityId` instead: a tap
 * re-reads the plug and only clears the helper when the machine really draws less than
 * {@link APPLIANCE_STOPPED_BELOW_WATTS} (see `checkApplianceRun`). The card shows the
 * current wattage of a fresh reading, so a tap that changes nothing explains itself.
 *
 * <p>Ist die Steckdose einer laufenden Maschine nicht erreichbar (Leistungssensor mit
 * `reachable: false`, Meross offline), kann der Flow das Ende nie erkennen — die
 * Karte sagt das, statt still weiterzulaufen. `entities` darf dafuer die
 * Meross-Leistungssensoren mit enthalten.
 *
 * <p>Nur `state === 'on'` erzeugt eine Karte. Ein fehlender Helfer oder
 * `unavailable` erzeugt keine — geraten wird nicht (Muster `buildDoorInsights`).
 *
 * @param nowMs Bezugszeitpunkt: fuer die Fertig-Karte die Entscheidung "heute oder
 * mit Datum", fuer die Lauf-Karte die verstrichene Laufzeit.
 */
export function buildApplianceInsights(entities: EntityState[], nowMs: number): HubInsight[] {
  const insights: HubInsight[] = [];
  for (const appliance of FINISHED_HELPERS) {
    const entity = findRunningHelper(entities, appliance.entityId);
    if (!entity) {
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
  for (const appliance of RUNNING_HELPERS) {
    const entity = findRunningHelper(entities, appliance.entityId);
    if (!entity) {
      continue;
    }
    const running = elapsedText(entity.lastChanged, nowMs, 'Läuft', 'Die Maschine läuft gerade.');
    const offline = isPlugUnreachable(entities, appliance.powerEntityId);
    const watts = freshPowerWatts(entities, appliance.powerEntityId, nowMs);
    let text = running;
    if (offline) {
      text = `${running} Steckdose nicht erreichbar – das Ende wird nicht erkannt.`;
    } else if (watts !== null) {
      text = `${running} Aktuell ${formatWatts(watts)}.`;
    }
    insights.push({
      icon: appliance.icon,
      tone: offline ? 'tertiary' : 'secondary',
      title: appliance.title,
      text,
      checkApplianceEntityId: appliance.entityId
    });
  }
  return insights;
}

/** Liefert den Helfer nur, wenn er tatsaechlich auf `on` steht. */
function findRunningHelper(entities: EntityState[], entityId: string): EntityState | undefined {
  const entity = entities.find(candidate => candidate.entityId === entityId);
  return entity?.state === 'on' ? entity : undefined;
}

/** Nur ein ausdrueckliches `reachable: false` zaehlt; ein fehlender Sensor wird nicht geraten. */
function isPlugUnreachable(entities: EntityState[], powerEntityId: string | undefined): boolean {
  if (!powerEntityId) {
    return false;
  }
  const sensor = entities.find(candidate => candidate.entityId === powerEntityId);
  return sensor?.attributes?.['reachable'] === false;
}

/** Watts of a reachable power sensor updated within {@link POWER_READING_MAX_AGE_MS}, else null. */
function freshPowerWatts(entities: EntityState[], powerEntityId: string | undefined, nowMs: number): number | null {
  if (!powerEntityId) {
    return null;
  }
  const sensor = entities.find(candidate => candidate.entityId === powerEntityId);
  if (!sensor || sensor.attributes?.['reachable'] === false) {
    return null;
  }
  const updatedMs = new Date(sensor.lastUpdated).getTime();
  if (Number.isNaN(updatedMs) || nowMs - updatedMs > POWER_READING_MAX_AGE_MS) {
    return null;
  }
  const watts = Number(sensor.state);
  return sensor.state.trim() !== '' && Number.isFinite(watts) ? watts : null;
}

function formatWatts(watts: number): string {
  return `${Math.round(watts).toLocaleString('de-DE')} W`;
}
