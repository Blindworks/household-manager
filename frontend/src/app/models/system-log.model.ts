export type SystemLogKind = 'FLOW_RUN' | 'JOB' | 'ENTITY' | 'LOG';

export type SystemLogStatus =
  | 'OK' | 'STOPPED' | 'RATE_LIMITED' | 'ERROR' | 'MISSED'
  | 'WAITING' | 'ABORTED' | 'INFO' | 'WARN' | 'RECOVERED';

export type JobState = 'OK' | 'FAILING' | 'INACTIVE';

/** Ein besuchter Node eines Flow-Laufs (Details eines FLOW_RUN-Eintrags ist eine Liste davon). */
export interface FlowRunStep {
  nodeId: string;
  type: string;
  label: string | null;
  outcome: string;
  error: string | null;
}

/** FLOW_RUN: Liste von Steps, LOG: Stacktrace als Text, sonst beliebiges JSON oder null. */
export type SystemLogDetails = FlowRunStep[] | string | Record<string, unknown> | null;

export interface SystemLogEntry {
  id: number;
  occurredAt: string;
  kind: SystemLogKind;
  status: SystemLogStatus;
  source: string;
  title: string;
  message: string | null;
  details: SystemLogDetails;
  flowId: number | null;
  runId: string | null;
  repeatCount: number;
}

export interface SystemLogPage {
  content: SystemLogEntry[];
  totalElements: number;
  page: number;
  size: number;
}

export interface SystemLogJob {
  jobKey: string;
  state: JobState;
  lastRunAt: string | null;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  failingSince: string | null;
  lastMessage: string | null;
}

export interface SystemLogFlowRef {
  id: number;
  name: string;
}

export interface SystemLogQuery {
  from?: string;
  to?: string;
  kinds?: SystemLogKind[];
  status?: SystemLogStatus;
  flowId?: number;
  q?: string;
  problemsOnly?: boolean;
  page?: number;
  size?: number;
}
