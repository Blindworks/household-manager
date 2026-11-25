import { EntityState } from '../models/entity-state.model';
import { HubInsight } from './hub-insight.model';

/**
 * Hub-Karten fuer abgelaufene Anmeldungen bei externen Diensten (Tractive, Blink,
 * Alexa, Dirigera). Quelle sind die Entitaeten `binary_sensor.account_login_<dienst>`
 * (`AccountLoginMonitor` im Backend, einzige Definition von „abgelaufen“):
 * `off` ergibt eine rote Karte, `on` mit Status `expiring` (nur Tractive kennt das
 * Ablaufdatum) eine gelbe Vorwarnung. Jeder andere Zustand ergibt keine Karte —
 * `unavailable`/`unknown` sind kein Beweis fuer eine abgelaufene Anmeldung.
 *
 * <p>Das Neuanmelden geschieht unter Admin → Konten (`/admin`, Standardreiter).
 */
export function buildAccountLoginInsights(entities: EntityState[]): HubInsight[] {
  const insights: HubInsight[] = [];
  for (const entity of entities) {
    const service = serviceLabel(entity);
    if (entity.state === 'off') {
      insights.push({
        icon: 'key_off',
        tone: 'error',
        title: `${service}: nicht angemeldet`,
        text: 'Unter Admin → Konten neu anmelden.',
        link: '/admin'
      });
    } else if (entity.state === 'on' && entity.attributes?.['status'] === 'expiring') {
      insights.push({
        icon: 'key',
        tone: 'tertiary',
        title: `${service}: Anmeldung läuft ab`,
        text: expiringText(entity.attributes?.['expiresAt']),
        link: '/admin'
      });
    }
  }
  return insights;
}

function serviceLabel(entity: EntityState): string {
  const label = entity.attributes?.['service'];
  return typeof label === 'string' && label.length > 0 ? label : entity.displayName;
}

function expiringText(expiresAt: unknown): string {
  if (typeof expiresAt !== 'string') {
    return 'Bald unter Admin → Konten neu anmelden.';
  }
  const date = new Date(expiresAt);
  if (Number.isNaN(date.getTime())) {
    return 'Bald unter Admin → Konten neu anmelden.';
  }
  const day = date.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' });
  const time = date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  return `Gültig bis ${day}, ${time} Uhr – vorher unter Admin → Konten neu anmelden.`;
}
