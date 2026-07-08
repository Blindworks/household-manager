export type AlexaTtsMode = 'SPEAK' | 'ANNOUNCE';
export type AlexaLoginStatus = 'OK' | 'MFA_REQUIRED' | 'CAPTCHA_REQUIRED' | 'FAILED';

export interface AlexaLoginResponse {
  status: AlexaLoginStatus;
  captchaImageUrl?: string;
  message?: string;
}

export interface AlexaAuthStatus {
  loggedIn: boolean;
  accountName?: string;
  reauthRequired: boolean;
}

export interface AlexaDevice {
  serialNumber: string;
  name: string;
  deviceType?: string;
  ttsCapable: boolean;
}

export interface ScheduledAnnouncement {
  id?: number;
  text: string;
  timeOfDay: string;          // "HH:mm"
  weekdays: string[];         // z. B. ["MONDAY","TUESDAY"]
  serialNumbers: string[];
  mode: AlexaTtsMode;
  enabled: boolean;
  lastRun?: string;
  lastError?: string;
}
