/** Blaulicht Bad Vilbel: Polizei- und NINA-Meldungen (Backend `/api/v1/blaulicht`). */
export type BlaulichtCategory = 'BRAND' | 'EINBRUCH' | 'UNFALL' | 'DIEBSTAHL' | 'WARNUNG' | 'SONSTIGES';

export interface BlaulichtEvent {
  readonly id: number;
  readonly source: 'PRESSEPORTAL' | 'NINA';
  readonly category: BlaulichtCategory;
  readonly locality: string;
  /** Nachbarort (Karben, Frankfurt-Berkersheim …), weniger relevant. */
  readonly nearby: boolean;
  readonly title: string;
  readonly summary: string | null;
  /** Lokale Haushaltszeit `yyyy-MM-ddTHH:mm[:ss]`; fehlt, wenn der Text keine Tatzeit nennt. */
  readonly occurredAt: string | null;
  readonly occurredUntil: string | null;
  readonly publishedAt: string;
  readonly url: string | null;
  readonly relevance: number;
  /** Veröffentlicht seit Beginn der laufenden bzw. letzten Nacht (20 Uhr). */
  readonly newSinceEvening: boolean;
}

export interface BlaulichtBriefing {
  readonly nightStart: string;
  readonly nightEnd: string;
  readonly text: string;
  readonly mainCount: number;
  readonly nearbyCount: number;
  readonly olderNewCount: number;
  readonly addendumText: string;
  readonly addendumCount: number;
  /** Zwischen Nachtende und Abend des Morgens nach der Nacht. */
  readonly cardVisible: boolean;
  readonly addendumVisible: boolean;
  readonly events: BlaulichtEvent[];
}

/** Fachliche Einstellungen der Admin-Seite „Blaulicht“ (`/v1/admin/blaulicht/settings`), Uhrzeiten `HH:mm`. */
export interface BlaulichtSettings {
  presseportalEnabled: boolean;
  titleFilter: string;
  ninaEnabled: boolean;
  dashboardArs: string;
  localArs: string;
  excludedProviders: string[];
  mainLocality: string;
  districts: string[];
  neighbors: string[];
  nightStart: string;
  nightEnd: string;
  addendumAt: string;
  cardUntil: string;
}
