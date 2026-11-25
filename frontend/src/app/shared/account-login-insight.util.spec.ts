import { EntityState } from '../models/entity-state.model';
import { buildAccountLoginInsights } from './account-login-insight.util';

function login(service: string, state: string, attributes: Record<string, unknown> = {}): EntityState {
  return {
    entityId: `binary_sensor.account_login_${service.toLowerCase()}`,
    domain: 'BINARY_SENSOR',
    source: 'ACCOUNT_LOGIN',
    sourceRef: service.toLowerCase(),
    friendlyName: `${service} angemeldet`,
    displayName: `${service} angemeldet`,
    state,
    attributes: { service, ...attributes },
    lastChanged: '2026-11-25T12:00:00',
    lastUpdated: '2026-11-25T12:00:00'
  };
}

describe('buildAccountLoginInsights', () => {

  it('zeigt fuer eine abgelaufene Anmeldung eine rote Karte mit Link', () => {
    const insights = buildAccountLoginInsights([login('Tractive', 'off', { status: 'expired' })]);

    expect(insights.length).toBe(1);
    expect(insights[0].title).toBe('Tractive: nicht angemeldet');
    expect(insights[0].tone).toBe('error');
    expect(insights[0].link).toBe('/admin');
  });

  it('warnt vor dem Ablauf mit Datum', () => {
    const insights = buildAccountLoginInsights([
      login('Tractive', 'on', { status: 'expiring', expiresAt: '2026-11-27T09:30:00' })
    ]);

    expect(insights[0].tone).toBe('tertiary');
    expect(insights[0].text).toContain('27.11.');
    expect(insights[0].text).toContain('09:30');
  });

  it('zeigt nichts fuer angemeldete oder unbekannte Zustaende', () => {
    expect(buildAccountLoginInsights([
      login('Blink', 'on', { status: 'ok' }),
      login('Alexa', 'unavailable'),
      login('Dirigera', 'unknown')
    ])).toEqual([]);
  });
});
