/**
 * Kuratierte "Aus"-Varianten von Material-Symbols-Icons (durchgestrichene Glyphen).
 * Nur verifizierte Paare: ein unbekannter Icon-Name wuerde von der Symbols-Font
 * nicht als Icon, sondern als Klartext gerendert — deshalb kein blindes "_off"-Anhaengen.
 * Icons ohne Eintrag behalten im Aus-Zustand ihr normales Symbol.
 */
const ICON_OFF_VARIANTS: Record<string, string> = {
  sensors: 'sensors_off',
  notifications: 'notifications_off',
  visibility: 'visibility_off',
  alarm: 'alarm_off',
  wifi: 'wifi_off',
  videocam: 'videocam_off',
  mic: 'mic_off',
  location_on: 'location_off',
  volume_up: 'volume_off',
  cloud: 'cloud_off',
  flash_on: 'flash_off',
  motion_photos_on: 'motion_photos_off',
  mode_fan: 'mode_fan_off',
  bedtime: 'bedtime_off',
  power: 'power_off',
  sync: 'sync_disabled',
  music_note: 'music_off',
  tv: 'tv_off',
  work: 'work_off',
  timer: 'timer_off'
};

/** Durchgestrichene Variante des Icons, falls es eine gibt — sonst das Icon selbst. */
export function iconOffVariant(icon: string): string {
  return ICON_OFF_VARIANTS[icon] ?? icon;
}
