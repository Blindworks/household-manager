export type UserRole = 'ADMIN' | 'MEMBER' | 'KIOSK';

export interface CurrentUser {
  /** null bei Anmeldung per Service-Token. */
  id: number | null;
  username: string;
  displayName: string;
  role: UserRole;
  /** true = Passwortwechsel erzwungen (z. B. Bootstrap-Admin mit "changeit") */
  mustChangePassword: boolean;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface AppUser {
  id: number;
  username: string;
  displayName: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
}

export interface ServiceTokenInfo {
  id: number;
  name: string;
  role: UserRole;
  enabled: boolean;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface CreatedServiceToken {
  info: ServiceTokenInfo;
  token: string;
}

export interface AuditEntry {
  id: number;
  timestamp: string;
  actorType: 'USER' | 'SERVICE' | 'SYSTEM' | 'TELEGRAM';
  actor: string;
  action: string;
  detail: string | null;
  /** Lesbare Fassung vom Backend (Flow-/Gerätenamen statt Ids); die Rohwerte stehen daneben. */
  actorLabel: string | null;
  actionLabel: string | null;
  detailLabel: string | null;
}

/** Aktor-Art des Audit-Filters (Backend `AuditActorKind`). */
export type AuditActorKind = 'PERSON' | 'SERVICE' | 'FLOW' | 'AGENT' | 'TELEGRAM' | 'SYSTEM';

export interface AuditQuery {
  from?: string;
  actorKinds?: AuditActorKind[];
  action?: string;
  q?: string;
}

export interface AuditPage {
  content: AuditEntry[];
  totalElements: number;
  page: number;
  size: number;
}

export interface AuditActionOption {
  action: string;
  label: string;
}
