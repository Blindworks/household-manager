import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { BlaulichtBriefing, BlaulichtCategory, BlaulichtEvent } from '../../models/blaulicht.model';
import { BlaulichtService } from '../../services/blaulicht.service';
import {
  BLAULICHT_CATEGORIES, categoryIcon, categoryLabel, formatEventTime
} from '../../shared/blaulicht-view.util';

/**
 * Dialog „Blaulicht Bad Vilbel“: Briefing oben, darunter die Meldungen der letzten 7 Tage mit
 * Kategoriefilter. Eigene Styles (die lumina-Dialogklassen sind im Dashboard gekapselt); Dialoge
 * sind in beiden Designs weiß.
 *
 * Links zur Quelle fehlen in der Tablet-Ansicht: das Wandtablet hätte von dort keinen Rückweg.
 */
@Component({
  selector: 'app-blaulicht-dialog',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './blaulicht-dialog.component.html',
  styleUrls: ['./blaulicht-dialog.component.scss']
})
export class BlaulichtDialogComponent implements OnInit {
  private readonly service = inject(BlaulichtService);

  @Input() briefing: BlaulichtBriefing | null = null;
  @Input() showLinks = true;
  @Output() closed = new EventEmitter<void>();

  readonly categories = BLAULICHT_CATEGORIES;
  events: BlaulichtEvent[] = [];
  filter: BlaulichtCategory | null = null;
  loading = true;
  error = false;
  private request?: Subscription;

  ngOnInit(): void {
    this.load();
  }

  get visibleEvents(): BlaulichtEvent[] {
    return this.filter ? this.events.filter(e => e.category === this.filter) : this.events;
  }

  get briefingText(): string | null {
    if (!this.briefing?.cardVisible) {
      return null;
    }
    return this.briefing.addendumVisible && this.briefing.addendumText
      ? `${this.briefing.text} ${this.briefing.addendumText}`
      : this.briefing.text;
  }

  setFilter(category: BlaulichtCategory | null): void {
    this.filter = this.filter === category ? null : category;
  }

  count(category: BlaulichtCategory): number {
    return this.events.filter(e => e.category === category).length;
  }

  icon(event: BlaulichtEvent): string {
    return categoryIcon(event.category);
  }

  label(event: BlaulichtEvent): string {
    return categoryLabel(event.category);
  }

  time(event: BlaulichtEvent): string {
    return formatEventTime(event);
  }

  trackEvent(_index: number, event: BlaulichtEvent): number {
    return event.id;
  }

  load(): void {
    this.request?.unsubscribe();
    this.loading = true;
    this.error = false;
    this.request = this.service.getEvents().subscribe({
      next: events => {
        this.events = events;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.error = true;
      }
    });
  }
}
