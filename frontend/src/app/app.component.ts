import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HeaderComponent } from './components/header/header.component';
import { ViewModeService } from './services/view-mode.service';

/**
 * Root component of the Household Manager application.
 * Provides the main layout structure with header and content area.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, HeaderComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  /** Application title */
  readonly title: string = 'Household Manager';

  /** Steuert das Ein-/Ausblenden des Headers je nach Ansichtsmodus. */
  readonly viewMode = inject(ViewModeService);
}
