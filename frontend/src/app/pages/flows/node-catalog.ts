/**
 * Gemeinsame Präsentations-Metadaten für Node-Typen (Kategorie + Label).
 * Von Palette UND Canvas genutzt, damit Farbschema und Beschriftung an einer
 * einzigen Stelle definiert sind.
 */

/** Fachliche Kategorie eines Node-Typs — steuert Farbe und Gruppierung in der UI. */
export type NodeCategory = 'trigger' | 'logic' | 'action';

/** Nicht-Trigger-Typen, die als „Aktion" gelten (Rest ist „Logik"). */
const ACTION_TYPES = new Set(['alexa-announce', 'switch-device']);

/** Menschenlesbare Beschriftungen je Node-Typ. */
const LABELS: Record<string, string> = {
  'entity-state-trigger': 'Entity-Trigger',
  'schedule-trigger': 'Zeitplan',
  'entity-condition': 'Bedingung',
  'delay': 'Verzögerung',
  'rate-limit': 'Drossel',
  'debug': 'Debug',
  'alexa-announce': 'Alexa-Ansage',
  'switch-device': 'Gerät schalten'
};

/** Ordnet einen Node-Typ seiner Kategorie zu (Trigger schlägt Aktion/Logik). */
export function nodeCategory(type: string, trigger: boolean): NodeCategory {
  if (trigger) {
    return 'trigger';
  }
  return ACTION_TYPES.has(type) ? 'action' : 'logic';
}

/** Menschenlesbares Label; fällt auf den technischen Typ zurück. */
export function nodeLabel(type: string): string {
  return LABELS[type] ?? type;
}
