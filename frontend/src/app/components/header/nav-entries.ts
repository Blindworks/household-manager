import { UserRole } from '../../models/auth.model';

export type MinRole = 'MEMBER' | 'ADMIN';

export const ROLE_LABELS: Record<UserRole, string> = {
  ADMIN: 'Administrator',
  MEMBER: 'Mitglied',
  KIOSK: 'Wandtablet'
};

/** Bis zu zwei Initialen aus dem Anzeigenamen („Benedikt Lind“ ⇒ „BL“, „Administrator“ ⇒ „A“). */
export function userInitials(displayName: string): string {
  const letters = displayName.trim().split(/\s+/).filter(Boolean).map(word => word[0]);
  const picked = letters.length > 1 ? [letters[0], letters[letters.length - 1]] : letters;
  return picked.join('').toUpperCase() || '?';
}

export interface NavLink {
  path: string;
  label: string;
  exact?: boolean;
  minRole?: MinRole;
  /** Zwischenüberschrift im Aufklappmenü; aufeinanderfolgende Einträge mit gleichem Wert bilden einen Abschnitt. */
  section?: string;
}

export interface NavGroup {
  /** Eindeutiger Schlüssel des Aufklappmenüs. */
  key: string;
  label: string;
  minRole?: MinRole;
  children: NavLink[];
}

export type NavEntry = NavLink | NavGroup;

/** Abschnitt eines Aufklappmenüs, wie ihn das Template rendert. */
export interface NavSection {
  title?: string;
  links: NavLink[];
}

export interface VisibleNavGroup {
  key: string;
  label: string;
  sections: NavSection[];
  /** Alle Pfade der Einträge – daran erkennt das Menü, ob die Gruppe aktiv ist. */
  paths: string[];
}

export type VisibleNavEntry =
  | { kind: 'link'; link: NavLink }
  | { kind: 'group'; group: VisibleNavGroup };

export function isNavGroup(entry: NavEntry): entry is NavGroup {
  return 'children' in entry;
}

/** Fasst aufeinanderfolgende Einträge mit gleichem `section` zu Abschnitten zusammen. */
export function toSections(links: NavLink[]): NavSection[] {
  const sections: NavSection[] = [];
  for (const link of links) {
    const last = sections[sections.length - 1];
    if (last && last.title === link.section) {
      last.links.push(link);
    } else {
      sections.push({ title: link.section, links: [link] });
    }
  }
  return sections;
}

/** Liegt die aktuelle URL auf einem der Pfade (oder darunter)? Query und Fragment zählen nicht. */
export function isPathInGroup(url: string, paths: string[]): boolean {
  const path = url.split(/[?#]/)[0];
  return paths.some(candidate => candidate === '/'
    ? path === '/'
    : path === candidate || path.startsWith(candidate + '/'));
}

/**
 * Hauptmenü, gegliedert nach Lebensbereich. Einstellungen und Integrationen stehen gesammelt
 * unter „Admin“, alle Logins im Admin-Reiter „Konten“. Einzige Definition der Navigation.
 */
export const NAV_ENTRIES: NavEntry[] = [
  { path: '/', label: 'Home', exact: true },
  {
    key: 'household',
    label: 'Haushalt',
    children: [
      { path: '/communicator', label: 'Communicator', minRole: 'MEMBER' },
      { path: '/calendar', label: 'Kalender' },
      { path: '/groceries', label: 'Angebote', minRole: 'MEMBER' },
      { path: '/waste-collection', label: 'Müllabfuhr' },
      { path: '/pets', label: 'Hundetracker' },
      { path: '/pet-food', label: 'Toni-Vorräte' },
      { path: '/announcements', label: 'Ansagen', minRole: 'MEMBER' }
    ]
  },
  {
    key: 'energy',
    label: 'Energie',
    children: [
      { path: '/energy', label: 'Übersicht', exact: true },
      { path: '/energy/battery-control', label: 'Akku-Steuerung' },
      { path: '/energy/history', label: 'Energieverlauf' },
      { path: '/meter-readings', label: 'Zählerstände' },
      { path: '/consumption', label: 'Verbrauch' }
    ]
  },
  {
    key: 'environment',
    label: 'Umwelt',
    children: [
      { path: '/temperatures', label: 'Temperaturen' },
      { path: '/air-quality', label: 'Luftqualität' },
      { path: '/weather', label: 'Wetter' }
    ]
  },
  {
    key: 'smart-home',
    label: 'Smart Home',
    children: [
      { path: '/tablet/house', label: 'Haus 3D' },
      { path: '/devices', label: 'Geräte' },
      { path: '/zigbee', label: 'Zigbee-Sensoren' },
      { path: '/cameras', label: 'Kameras' },
      { path: '/vision', label: 'Gesichtserkennung', minRole: 'ADMIN' },
      { path: '/charging', label: 'Ladesäulen' },
      { path: '/notifications', label: 'Benachrichtigungen' },
      { path: '/flows', label: 'Automatisierungen', minRole: 'ADMIN', section: 'Technik' },
      { path: '/entities', label: 'Entitäten', section: 'Technik' },
      { path: '/custom-entities', label: 'Eigene Helfer', section: 'Technik' }
    ]
  },
  {
    key: 'finance',
    label: 'Ausgaben',
    minRole: 'MEMBER',
    children: [
      { path: '/finance', label: 'Übersicht', exact: true },
      { path: '/finance/category-trend', label: 'Kategorien im Verlauf' },
      { path: '/finance/transactions', label: 'Transaktionen' },
      { path: '/finance/recurring', label: 'Wiederkehrende' },
      { path: '/finance/budgets', label: 'Budgets' },
      { path: '/finance/categories', label: 'Kategorien', section: 'Einstellungen' },
      { path: '/finance/rules', label: 'Regeln', section: 'Einstellungen' },
      { path: '/finance/accounts', label: 'Konten', section: 'Einstellungen' }
    ]
  },
  {
    key: 'admin',
    label: 'Admin',
    minRole: 'ADMIN',
    children: [
      { path: '/admin', label: 'Übersicht & Konten', exact: true, section: 'System' },
      { path: '/admin/users', label: 'Nutzer', section: 'System' },
      { path: '/admin/service-tokens', label: 'API-Tokens', section: 'System' },
      { path: '/admin/system-log', label: 'Systemprotokoll', section: 'System' },
      { path: '/admin/audit-log', label: 'Audit-Log', section: 'System' },
      { path: '/admin/communicator', label: 'Communicator', section: 'System' },
      { path: '/admin/dirigera', label: 'IKEA Dirigera', section: 'Integrationen' },
      { path: '/admin/ble-sensors', label: 'Bluetooth-Thermometer', section: 'Integrationen' },
      { path: '/admin/heating', label: 'Heizung', section: 'Integrationen' },
      { path: '/admin/network-devices', label: 'Netzwerk-Geräte', section: 'Integrationen' },
      { path: '/admin/presence', label: 'Anwesenheit', section: 'Integrationen' },
      { path: '/admin/tractive', label: 'Hundetracker-Zuhause', section: 'Integrationen' },
      { path: '/admin/charging', label: 'Ladesäulen', section: 'Integrationen' },
      { path: '/admin/groceries', label: 'Supermärkte', section: 'Integrationen' },
      { path: '/admin/blaulicht', label: 'Blaulicht', section: 'Integrationen' },
      { path: '/admin/tesla', label: 'Tesla', section: 'Integrationen' },
      { path: '/admin/house-devices', label: 'Geräteplätze', section: 'Haus & Dashboard' },
      { path: '/admin/mode-quick-access', label: 'Modus-Schnellzugriff', section: 'Haus & Dashboard' },
      { path: '/admin/calendar-categories', label: 'Kalender-Kategorien', section: 'Haus & Dashboard' },
      { path: '/admin/agent', label: 'Hausverstand', section: 'Haus & Dashboard' }
    ]
  }
];
