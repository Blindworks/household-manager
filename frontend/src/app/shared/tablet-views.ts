/**
 * Die Unteransichten des Wandtablets.
 *
 * Im Tablet-Modus blendet die App den Header aus, deshalb ist die Leiste am
 * unteren Rand der einzige Weg zwischen den Ansichten. Diese Liste ist ihre
 * einzige Definition - Dashboard und Tablet-Shell lesen dieselbe Konstante,
 * damit die Leiste ueberall gleich aussieht.
 */
export interface TabletView {
  readonly route: string;
  /** Name eines Material-Symbols. */
  readonly icon: string;
  readonly label: string;
}

export const TABLET_VIEWS: readonly TabletView[] = [
  { route: '/tablet/temperatures', icon: 'thermostat', label: 'Temperaturen' },
  { route: '/tablet/air-quality', icon: 'air', label: 'Luftqualität' },
  { route: '/tablet/consumption', icon: 'electric_meter', label: 'Verbrauch' },
  { route: '/tablet/toni', icon: 'pets', label: 'Toni' }
];
