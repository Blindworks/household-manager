import { Component } from '@angular/core';
import { HeroComponent } from '../../components/hero/hero.component';
import { FeaturesComponent } from '../../components/features/features.component';

/**
 * Marketing page component - displays promotional content.
 * Shows hero section with call-to-action and features overview.
 * Previously the home page, now used for marketing purposes.
 */
@Component({
  selector: 'app-marketing',
  standalone: true,
  imports: [HeroComponent, FeaturesComponent],
  templateUrl: './marketing.component.html',
  styleUrl: './marketing.component.scss'
})
export class MarketingComponent {
  // No additional logic required for this component
}
