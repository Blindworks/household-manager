export type AlexaTtsMode = 'SPEAK' | 'ANNOUNCE';

export interface AlexaProxyStartResponse {
  proxyUrl: string;
}

export interface AlexaAuthStatus {
  loggedIn: boolean;
  accountName?: string;
  reauthRequired: boolean;
  loginError?: string;
}

export interface AlexaDevice {
  serialNumber: string;
  name: string;
  deviceType?: string;
  ttsCapable: boolean;
}
