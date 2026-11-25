import { CommunicatorCard } from './communicator.model';

export type CommunicatorRequestStatus =
  'OK' | 'NOT_UNDERSTOOD' | 'SPEECH_ERROR' | 'MODEL_MISSING' | 'AI_ERROR' | 'INCOMPLETE' | 'UNAVAILABLE' | 'REJECTED';

export interface CommunicatorRequestRow {
  id: number;
  askedAt: string;
  actor: string | null;
  voice: boolean;
  status: CommunicatorRequestStatus;
  question: string | null;
  error: string | null;
  model: string | null;
  audioBytes: number | null;
  audioType: string | null;
  audioMs: number | null;
  speechMs: number | null;
  totalMs: number;
  aiCalls: number;
  toolCalls: number;
  toolFailures: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  problem: boolean;
  /** Geschätzt in US-Dollar; null bei unbekanntem Modell. */
  costUsd: number | null;
}

export interface CommunicatorRequestPage {
  content: CommunicatorRequestRow[];
  totalElements: number;
  page: number;
  size: number;
}

export interface CommunicatorStep {
  round: number;
  type: 'CLAUDE' | 'TOOL';
  name: string;
  input?: string;
  output?: string;
  outputChars?: number;
  ok: boolean;
  ms: number;
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
}

export interface CommunicatorRequestDetail {
  row: CommunicatorRequestRow;
  details: { steps: CommunicatorStep[]; cards: CommunicatorCard[] } | null;
}

export interface CommunicatorTokens {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
}

export interface CommunicatorCost {
  totalUsd: number;
  todayUsd: number;
  monthUsd: number | null;
  perRequestUsd: number | null;
  unpriced: number;
  models: { model: string; requests: number; costUsd: number }[];
}

export interface CommunicatorModelChoice {
  id: string;
  label: string;
  inputPrice: number;
  cacheWritePrice: number;
  cacheReadPrice: number;
  outputPrice: number;
}

export interface CommunicatorModelSettings {
  current: string;
  models: CommunicatorModelChoice[];
}

export interface CommunicatorCount {
  name: string;
  count: number;
}

export interface CommunicatorLogSummary {
  requests: number;
  problems: number;
  byStatus: Partial<Record<CommunicatorRequestStatus, number>>;
  tokens: CommunicatorTokens;
  cost: CommunicatorCost;
  medianTotalMs: number | null;
  maxTotalMs: number | null;
  averageAiCalls: number;
  toolCalls: number;
  toolFailures: number;
  speech: {
    recordings: number;
    notUnderstood: number;
    failures: number;
    medianMs: number | null;
    maxMs: number | null;
    realtimeFactor: number | null;
  };
  days: { date: string; requests: number; problems: number; tokens: CommunicatorTokens; costUsd: number }[];
  endpoints: CommunicatorCount[];
  errors: CommunicatorCount[];
}

export interface CommunicatorHealth {
  enabled: boolean;
  aiConfigured: boolean;
  aiModel: string;
  endpoints: number;
  catalogChars: number;
  speech: {
    configured: boolean;
    model: string;
    language: string;
    reachable: boolean;
    modelInstalled: boolean | null;
    responseMs: number | null;
    error: string | null;
    installing: boolean;
    installError: string | null;
  };
}
