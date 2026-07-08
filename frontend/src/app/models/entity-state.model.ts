/**
 * Eine generische Entität mit aktuellem Zustand (Spiegel der Integrationen).
 */
export interface EntityState {
  entityId: string;
  domain: EntityDomain;
  source: string;
  sourceRef: string;
  friendlyName: string;
  state: string;
  attributes: Record<string, unknown>;
  lastChanged: string;
  lastUpdated: string;
}

export type EntityDomain = 'SWITCH' | 'SENSOR' | 'BINARY_SENSOR';
