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

export type EntityDomain = 'SWITCH' | 'SENSOR' | 'BINARY_SENSOR' | 'INPUT_BOOLEAN';

/** Quelle für vom Benutzer selbst angelegte Entitäten. */
export const MANUAL_SOURCE = 'MANUAL';

/** Anlage einer manuellen Boolean-Entität (Modus/Helfer wie "Nachtmodus"). */
export interface CreateManualEntityRequest {
  name: string;
  state?: string;
  icon?: string;
}

/** Umbenennen einer manuellen Entität (Entity-ID bleibt stabil). */
export interface RenameManualEntityRequest {
  name: string;
  icon?: string;
}
