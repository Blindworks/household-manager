export type ActionClass = 'HEATING' | 'SWITCH' | 'MODE' | 'NOTIFY' | 'LOCK';
export type ActionPolicy = 'PROPOSE' | 'DIRECT';
export type NoteKind = 'OBSERVATION' | 'PREFERENCE';
export type ProposalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'EXECUTED' | 'FAILED';
export type ProposalDecision = 'APPROVE' | 'REJECT' | 'APPROVE_AND_REMEMBER';
export type RunTriggerType = 'SCHEDULED' | 'WAKE' | 'MANUAL';

export interface AgentSummary {
  pendingProposals: number;
  active: boolean;
  telegramConfigured: boolean;
}

export interface AgentSettings {
  active: boolean;
}

export interface AgentPolicy {
  actionClass: ActionClass;
  policy: ActionPolicy;
}

export interface AgentNote {
  id: number;
  kind: NoteKind;
  text: string;
  source: string;
  createdAt: string;
  expiresAt: string | null;
}

export interface AgentNoteRequest {
  kind: NoteKind;
  text: string;
  expiresAt: string | null;
}

export interface AgentProposal {
  id: number;
  actionClass: ActionClass;
  target: string;
  description: string;
  reason: string;
  urgent: boolean;
  status: ProposalStatus;
  decidedBy: string | null;
  decidedAt: string | null;
  deliveredAt: string | null;
  error: string | null;
  createdAt: string;
}

export interface AgentRun {
  id: number | null;
  triggerType: RunTriggerType;
  triggerDetail: string | null;
  startedAt: string;
  durationMs: number;
  model: string | null;
  inputTokens: number;
  outputTokens: number;
  toolCalls: number;
  summary: string | null;
  error: string | null;
}

export interface AgentRunsResponse {
  runs: AgentRun[];
  inputTokensToday: number;
  outputTokensToday: number;
}
