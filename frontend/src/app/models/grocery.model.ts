/** Typen der Supermarkt-Angebote-API (spiegeln die DTOs in backend/.../grocery/dto). */

export type GroceryBaseUnit = 'LITER' | 'KILOGRAM' | 'PIECE';
export type GroceryVerdict = 'MATCH' | 'MAYBE' | 'NO';
export type GroceryReasonKind = 'SORT_UNCLEAR' | 'PACKAGING_DIFFERS' | 'OTHER';
export type GroceryReferenceKind = 'USUAL' | 'OWN_NORMAL_PRICE' | 'LOWEST_30_DAYS';

/** Filter der Personenauswahl: Nutzer-Id, "household" oder "all". */
export type GroceryOwner = string;

export interface GroceryProduct {
  id: number;
  ownerUserId: number | null;
  ownerName: string | null;
  name: string;
  baseUnit: GroceryBaseUnit;
  searchWords: string[];
  mustWords: string[];
  excludeWords: string[];
  normalPricePerUnit: number | null;
  active: boolean;
  editable: boolean;
}

export interface GroceryProductRequest {
  name: string;
  household?: boolean | null;
  baseUnit?: GroceryBaseUnit | null;
  searchWords?: string[] | null;
  mustWords?: string[] | null;
  excludeWords?: string[] | null;
  normalPricePerUnit?: number | null;
  active?: boolean | null;
}

export interface GroceryDeal {
  productId: number;
  productName: string;
  ownerUserId: number | null;
  chain: string;
  chainName: string;
  marketName: string | null;
  title: string;
  packaging: string | null;
  price: number;
  basePrice: number | null;
  baseUnit: GroceryBaseUnit | null;
  reference: number | null;
  referenceKind: GroceryReferenceKind | null;
  savingPercent: number | null;
  appPrice: number | null;
  appBasePrice: number | null;
  appOnly: boolean;
  verdict: GroceryVerdict;
  reasonKind: GroceryReasonKind | null;
  reason: string | null;
  userDecided: boolean;
  warmingUp: boolean;
  deal: boolean;
  validFrom: string | null;
  validTo: string | null;
  imageUrl: string | null;
  fingerprint: string;
}

export interface GroceryDealsView {
  collectedAt: string | null;
  deals: GroceryDeal[];
}

export interface GroceryPriceObservation {
  chain: string;
  chainName: string;
  isoWeek: string;
  basePrice: number;
  observedAt: string;
}

export interface GrocerySummaryItem {
  productName: string;
  ownerUserId: number | null;
  ownerName: string | null;
  chain: string;
  chainName: string;
  basePrice: number;
  baseUnit: GroceryBaseUnit;
  savingPercent: number;
  /** Nur mit App-/Kundenkartenpreis ein Schnäppchen. */
  appOnly: boolean;
  /** Noch wenig Vergleichsdaten für den üblichen Preis. */
  warmingUp: boolean;
}

export interface GrocerySummary {
  items: GrocerySummaryItem[];
}

/** Spiegel der Admin-DTOs unter /v1/admin/groceries. */
export interface GrocerySettings {
  zip: string | null;
  lat: number | null;
  lng: number | null;
  dealThresholdPercent: number | null;
}

export interface GroceryMarket {
  id: number;
  chain: string;
  externalId: string;
  name: string;
  address: string | null;
  lat: number | null;
  lng: number | null;
  selected: boolean;
}

export interface GroceryMarketRefresh {
  markets: GroceryMarket[];
  failedChains: string[];
}

export interface GrocerySourceStatus {
  source: string;
  enabled: boolean;
  lastRunAt: string | null;
  offerCount: number | null;
  ok: boolean | null;
  reason: string | null;
}

export interface GroceryRunResult {
  offers: number;
  aiCalls: number;
  deferred: number;
  deals: number;
  aiFailures: number;
  sourceFailures: Record<string, string>;
}

/** Laufstand (GET /v1/admin/groceries/runs/status); Zeiten sind Serverzeit ohne Zone. */
export interface GroceryRunStatus {
  running: boolean;
  startedAt: string | null;
  finishedAt: string | null;
  lastResult: GroceryRunResult | null;
  lastError: string | null;
}

/** Antwort auf „Jetzt suchen“ (202): der Lauf läuft im Hintergrund. */
export interface GroceryRunStarted {
  status: string;
}

export interface GroceryChainStatus {
  chain: string;
  name: string;
  enabled: boolean;
}
