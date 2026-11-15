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
}

export interface GrocerySummary {
  items: GrocerySummaryItem[];
}
