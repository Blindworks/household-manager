/** Zustand der Waeschetrocknung im Keller, wie ihn GET /api/v1/laundry/state liefert. */
export interface LaundryState {
  status: 'idle' | 'asking' | 'drying' | 'maybe_dry';
  prompt: 'NONE' | 'HUNG' | 'DRY';
  /** Lokale Haushaltszeit als ISO-String ohne Zone. */
  startedAt: string | null;
  predictedDryAt: string | null;
  estimatePaused: boolean;
  requiredLoad: number;
}
