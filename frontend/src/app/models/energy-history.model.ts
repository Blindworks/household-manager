export type EnergyMetric =
  | 'BKW_ALT'
  | 'BKW_NEU'
  | 'PV_TOTAL'
  | 'HAUSVERBRAUCH'
  | 'GRID'
  | 'ANKER_PV'
  | 'AKKU';

export interface EnergyHistoryPoint {
  timestamp: string;
  value: number | null;
}

export interface EnergyMetricDefinition {
  metric: EnergyMetric;
  label: string;
  unit: 'W' | '%';
  color: string;
}

export const ENERGY_METRIC_DEFINITIONS: Record<EnergyMetric, EnergyMetricDefinition> = {
  BKW_ALT:       { metric: 'BKW_ALT',       label: 'BKW Alt',        unit: 'W', color: '#8b5cf6' },
  BKW_NEU:       { metric: 'BKW_NEU',       label: 'BKW Neu',        unit: 'W', color: '#a855f7' },
  PV_TOTAL:      { metric: 'PV_TOTAL',      label: 'PV gesamt',      unit: 'W', color: '#f59e0b' },
  HAUSVERBRAUCH: { metric: 'HAUSVERBRAUCH', label: 'Hausverbrauch',  unit: 'W', color: '#3b82f6' },
  GRID:          { metric: 'GRID',          label: 'Netz (Saldo)',   unit: 'W', color: '#ef4444' },
  ANKER_PV:      { metric: 'ANKER_PV',      label: 'Anker PV',       unit: 'W', color: '#b45309' },
  AKKU:          { metric: 'AKKU',          label: 'Akku',           unit: '%', color: '#10b981' }
};
