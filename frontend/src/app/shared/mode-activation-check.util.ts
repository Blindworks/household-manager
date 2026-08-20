import { EntityState } from '../models/entity-state.model';
import { PowerConsumer } from '../models/power-consumer.model';

/**
 * Aktivierungs-Checks für die bewachten Haus-Modi („Toni allein", „Abwesend"):
 * pure Auswertung der bereits vorhandenen API-Antworten. Reiner UI-Schutz —
 * warnt, blockiert aber nie (Spec 2026-08-20-toni-allein-aktivierungs-checks).
 */

/** Schwelle, ab der ein Verbraucher als Großverbraucher gemeldet wird. */
export const HIGH_CONSUMPTION_THRESHOLD_WATTS = 50;

export type ActivationCheckStatus = 'loading' | 'ok' | 'warning';

/** Anzeigezustand eines Checks: bei 'ok' genau eine Sammelzeile, sonst eine Zeile je Problem. */
export interface ActivationCheck {
  status: ActivationCheckStatus;
  lines: string[];
}

export function loadingCheck(): ActivationCheck {
  return { status: 'loading', lines: [] };
}

/** Ergebnis eines fehlgeschlagenen Check-Requests — Warnung, kein OK und kein Blocker. */
export function failedCheck(): ActivationCheck {
  return { status: 'warning', lines: ['Prüfung fehlgeschlagen.'] };
}

/**
 * Fenster-&-Türen-Check über die Zigbee-Kontakte (deviceClass "door", on = offen).
 * `unavailable` zählt als Warnung — ein toter Sensor beweist nicht, dass zu ist.
 * Eine leere Kontaktliste (z. B. Zigbee-Ausfall) ist ebenfalls eine Warnung.
 */
export function buildContactCheck(entities: EntityState[]): ActivationCheck {
  const contacts = entities.filter(entity => entity.attributes?.['deviceClass'] === 'door');
  if (contacts.length === 0) {
    return { status: 'warning', lines: ['Keine Fenster-/Türkontakte gefunden.'] };
  }
  const problems: string[] = [];
  for (const entity of contacts) {
    if (entity.state === 'on') {
      problems.push(`${entity.displayName} ist offen.`);
    } else if (entity.state !== 'off') {
      problems.push(`${entity.displayName}: Zustand unbekannt.`);
    }
  }
  if (problems.length > 0) {
    return { status: 'warning', lines: problems };
  }
  const label = contacts.length === 1 ? 'Kontakt' : 'Kontakte';
  return {
    status: 'ok',
    lines: [`Alle Fenster und Türen geschlossen (${contacts.length} ${label}).`]
  };
}

/**
 * Großverbraucher-Check: alles ab {@link HIGH_CONSUMPTION_THRESHOLD_WATTS}.
 * Verbraucher ohne Messwert werden bewusst ignoriert — eine offline Steckdose
 * versorgt ihr Gerät im Normalfall gar nicht (siehe Spec).
 */
export function buildConsumerCheck(consumers: PowerConsumer[]): ActivationCheck {
  const active = consumers.filter(
    (consumer): consumer is PowerConsumer & { powerWatts: number } =>
      consumer.powerWatts != null && consumer.powerWatts >= HIGH_CONSUMPTION_THRESHOLD_WATTS
  );
  if (active.length === 0) {
    return { status: 'ok', lines: ['Keine Großverbraucher aktiv.'] };
  }
  return {
    status: 'warning',
    lines: active.map(consumer => `${consumer.displayName}: ${Math.round(consumer.powerWatts)} W`)
  };
}
