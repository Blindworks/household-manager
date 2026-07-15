/**
 * Eine generische Entität mit aktuellem Zustand (Spiegel der Integrationen).
 */
export interface EntityState {
  entityId: string;
  domain: EntityDomain;
  source: string;
  sourceRef: string;
  friendlyName: string;
  customName?: string | null;
  displayName: string;
  state: string;
  attributes: Record<string, unknown>;
  lastChanged: string;
  lastUpdated: string;
}

export type EntityDomain =
  | 'SWITCH' | 'SENSOR' | 'BINARY_SENSOR'
  | 'INPUT_BOOLEAN' | 'INPUT_NUMBER' | 'INPUT_TEXT' | 'INPUT_SELECT';

/** Vom Benutzer anlegbare Helfer-Typen. */
export type ManualEntityType = 'INPUT_BOOLEAN' | 'INPUT_NUMBER' | 'INPUT_TEXT' | 'INPUT_SELECT';

/** Quelle für vom Benutzer selbst angelegte Entitäten. */
export const MANUAL_SOURCE = 'MANUAL';

/** Anlage einer manuellen Entität. Relevante Felder hängen vom {@link type} ab. */
export interface CreateManualEntityRequest {
  name: string;
  type: ManualEntityType;
  state?: string;
  icon?: string;
  options?: string[];
  min?: number;
  max?: number;
  step?: number;
  unit?: string;
}

/** Umbenennen einer manuellen Entität (Entity-ID bleibt stabil). */
export interface RenameManualEntityRequest {
  name: string;
  icon?: string;
}
