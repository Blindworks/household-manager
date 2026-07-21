/** Nuki-Schloss, wie es GET /api/v1/nuki/locks liefert. */
export interface NukiLock {
  smartlockId: number;
  name: string;
  state: string;
  /** 'on' = Tür offen, 'off' = zu, null = kein Türsensor. */
  doorState: string | null;
  batteryCharge: number | null;
  batteryCritical: boolean;
}

export type NukiLockActionType = 'LOCK' | 'UNLOCK' | 'UNLATCH';
