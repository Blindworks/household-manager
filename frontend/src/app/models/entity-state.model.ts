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
  /** Explizite Kachel-Regeln (tileKey → Regel); fehlender Eintrag = AUTO. */
  tileVisibility?: Record<string, TileVisibility>;
  lastChanged: string;
  lastUpdated: string;
}

export type EntityDomain =
  | 'SWITCH' | 'SENSOR' | 'BINARY_SENSOR' | 'EVENT'
  | 'INPUT_BOOLEAN' | 'INPUT_NUMBER' | 'INPUT_TEXT' | 'INPUT_SELECT';

/** Sichtbarkeitsregel einer Entität auf einer Dashboard-Kachel. */
export type TileVisibility = 'ALWAYS' | 'AUTO' | 'WHEN_ON' | 'NEVER';

/** Schlüssel der Schalter-Kachel des Dashboards. */
export const SWITCH_TILE_KEY = 'switches';

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
