export interface SmartDevice {
  id: number;
  deviceType: 'KASA' | 'TAPO' | 'MEROSS';
  externalDeviceId: string;
  deviceName: string;
  model: string | null;
  ipAddress: string | null;
  isOnline: boolean;
  isPoweredOn: boolean;
  capabilities: string[];
  metadata: Record<string, any> | null;
  createdAt: string;
  updatedAt: string;
}

export interface SmartDeviceScanRequest {
  deviceType: 'KASA' | 'TAPO' | 'MEROSS';
}

export interface SmartDeviceUpdateRequest {
  deviceName: string;
  metadata: Record<string, any>;
}

export interface KasaManualAddRequest {
  ip: string;
}

/**
 * Bekannte Werte des `capabilities`-Arrays eines Geraets. Bewusst nicht als eigener Typ auf
 * `SmartDevice.capabilities` verwendet - der Wert kommt roh vom Backend (`TapoCapabilityMapper`
 * kann jederzeit neue Werte liefern) und soll nicht an einer veralteten Frontend-Union scheitern.
 */
export type SmartDeviceCapability = 'SWITCH' | 'BRIGHTNESS' | 'COLOR' | 'COLOR_TEMP';

/**
 * Request-Body fuer `PUT /devices/{id}/light`. Alle Felder optional - nur gesetzte Felder werden
 * am Geraet angewendet. Farbe (hue/saturation) und Farbtemperatur (colorTemp) sind am Geraet
 * exklusive Modi und duerfen nicht gemeinsam in einem Request gesetzt werden.
 */
export interface LightStateRequest {
  /** 1-100. Erfordert die Faehigkeit BRIGHTNESS. */
  brightness?: number;
  /** 0-360 Grad. Erfordert die Faehigkeit COLOR, immer zusammen mit saturation gesetzt. */
  hue?: number;
  /** 0-100 Prozent. Erfordert die Faehigkeit COLOR, immer zusammen mit hue gesetzt. */
  saturation?: number;
  /** Kelvin, geraetespezifischer Bereich. Erfordert die Faehigkeit COLOR_TEMP. */
  colorTemp?: number;
}

/** Request-Body fuer `PUT /devices/{id}/address` (Tapo-Geraete, siehe KasaManualAddRequest). */
export interface TapoAddressRequest {
  ip: string;
}
