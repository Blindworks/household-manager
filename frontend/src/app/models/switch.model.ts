/**
 * Ein schaltbarer Eintrag: SmartDevice-Steckdose (Kasa/Tapo/Meross) oder
 * manueller Boolean-Helfer.
 */
export interface SwitchEntity {
  entityId: string;
  domain: 'SWITCH' | 'INPUT_BOOLEAN';
  source: string;
  displayName: string;
  /** "on", "off" oder "unavailable". */
  state: string;
  available: boolean;
  /** Material-Symbols-Name. */
  icon: string;
  /** Erfordert im Dashboard eine Bestätigung vor dem Schalten. */
  confirmRequired: boolean;
  toggleCount: number;
  lastToggledAt: string | null;
}
