/** Vertrag der Sprach-App „Communicator“ (`/api/v1/communicator`): Karten aus allgemeinen Bausteinen. */

/** Eine Änderung, die erst der Tipp des Nutzers ausführt; `"$value"` im Body ersetzt der Regler. */
export interface CommunicatorAction {
  label: string;
  method: 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  /** Pfad ohne `/api`. */
  path: string;
  query?: Record<string, string>;
  body?: unknown;
}

export interface CommunicatorItem {
  title?: string;
  subtitle?: string;
  value?: string;
  unit?: string;
}

export interface CommunicatorPoint {
  label: string;
  value: number;
}

/** Welche Felder gesetzt sind, hängt an `type`; fehlende Werte fehlen im JSON. */
export interface CommunicatorBlock {
  type: 'text' | 'value' | 'list' | 'chart' | 'slider';
  label?: string;
  text?: string;
  value?: string;
  unit?: string;
  tone?: 'good' | 'warn' | 'bad';
  items?: CommunicatorItem[];
  points?: CommunicatorPoint[];
  number?: number;
  min?: number;
  max?: number;
  step?: number;
  action?: CommunicatorAction;
}

export interface CommunicatorCard {
  title: string;
  /** Material-Symbols-Name. */
  icon: string;
  blocks: CommunicatorBlock[];
  actions: CommunicatorAction[];
  /** Folgefragen als Knöpfe. */
  questions: string[];
}

export interface CommunicatorAnswer {
  /** Verstandener Text; fehlt, wenn die Spracherkennung nichts lieferte. */
  transcript?: string;
  cards: CommunicatorCard[];
}

export interface CommunicatorStatus {
  enabled: boolean;
  aiConfigured: boolean;
  speechConfigured: boolean;
}
