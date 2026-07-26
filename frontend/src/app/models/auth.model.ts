export type UserRole = 'ADMIN' | 'MEMBER' | 'KIOSK';

export interface CurrentUser {
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
}
