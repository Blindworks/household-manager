import { Component } from '@angular/core';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { TeslaCardComponent } from '../../components/tesla-card/tesla-card.component';

/**
 * Tesla view for the wall tablet. The tile lives here instead of on the dashboard,
 * where it took too much space; the card itself loads and refreshes its own data.
 */
@Component({
  selector: 'app-tablet-tesla',
  standalone: true,
  imports: [TabletShellComponent, TeslaCardComponent],
  templateUrl: './tablet-tesla.component.html',
  styleUrl: './tablet-tesla.component.scss'
})
export class TabletTeslaComponent {}
