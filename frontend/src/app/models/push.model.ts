/** Ein beim Backend registriertes Push-Geraet (eigene Subscription). */
export interface PushDevice {
  id: number;
  deviceLabel: string;
  createdAt: string;
  lastUsedAt: string | null;
  endpoint: string;
}
