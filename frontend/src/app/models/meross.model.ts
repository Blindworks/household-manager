export interface MerossPlugResponse {
  deviceId: string;
  name: string;
  deviceType: string;
  on: boolean;
  onlineStatus: string;
}

export interface MerossCloudLoginResponse {
  apiStatus: number;
  sysStatus: number;
  message: string;
  token: string;
  userId: string;
  key: string;
  domain: string;
  mqttDomain: string;
  mfaLockExpire: number | null;
}

export interface MerossCloudDevice {
  uuid: string;
  devName: string;
  deviceType: string;
  onlineStatus: string;
  domain: string;
  reservedDomain: string;
}

export interface MerossCloudDevicesResponse {
  apiStatus: number;
  sysStatus: number;
  message: string;
  devices: MerossCloudDevice[];
}
