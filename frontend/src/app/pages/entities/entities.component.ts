import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap } from 'rxjs';
import { EntityStateService } from '../../services/entity-state.service';
import { EntityState, EntityDomain } from '../../models/entity-state.model';

const REFRESH_INTERVAL_MS = 10000;

/**
 * Übersicht aller generischen Entitäten mit Live-Zustand (Polling).
 * Dient als Sichtbarmachung und Debug-Werkzeug der Entity-/State-Schicht.
 */
@Component({
  selector: 'app-entities',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './entities.component.html',
  styleUrl: './entities.component.scss'
})
export class EntitiesComponent implements OnInit {
  private readonly entityStateService = inject(EntityStateService);
  private readonly destroyRef = inject(DestroyRef);

  readonly entities = signal<EntityState[]>([]);
  readonly error = signal<string | null>(null);
  readonly expandedEntityId = signal<string | null>(null);

  domainFilter = signal<EntityDomain | ''>('');
  sourceFilter = signal<string>('');
  searchText = signal<string>('');

  readonly sources = computed(() =>
    [...new Set(this.entities().map(e => e.source))].sort()
  );

  readonly filteredEntities = computed(() => {
    const search = this.searchText().toLowerCase();
    return this.entities().filter(e =>
      (!this.domainFilter() || e.domain === this.domainFilter()) &&
      (!this.sourceFilter() || e.source === this.sourceFilter()) &&
      (!search ||
        e.friendlyName.toLowerCase().includes(search) ||
        e.entityId.toLowerCase().includes(search))
    );
  });

  ngOnInit(): void {
    interval(REFRESH_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.entityStateService.getEntities()),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: entities => {
        this.entities.set(entities);
        this.error.set(null);
      },
      error: err => this.error.set(err.message)
    });
  }

  toggleExpanded(entityId: string): void {
    this.expandedEntityId.set(this.expandedEntityId() === entityId ? null : entityId);
  }

  attributeEntries(entity: EntityState): { key: string; value: unknown }[] {
    return Object.entries(entity.attributes ?? {}).map(([key, value]) => ({ key, value }));
  }

  stateWithUnit(entity: EntityState): string {
    const unit = entity.attributes?.['unit'];
    if (entity.state === 'unavailable' || entity.state === 'unknown' || !unit) {
      return entity.state;
    }
    return `${entity.state} ${unit}`;
  }

  stateClass(entity: EntityState): string {
    if (entity.state === 'unavailable' || entity.state === 'unknown') {
      return 'state-badge--unavailable';
    }
    if (entity.state === 'on') {
      return 'state-badge--on';
    }
    if (entity.state === 'off') {
      return 'state-badge--off';
    }
    return 'state-badge--value';
  }

  relativeTime(isoDate: string): string {
    const diffMs = Date.now() - new Date(isoDate).getTime();
    const minutes = Math.floor(diffMs / 60000);
    if (minutes < 1) { return 'gerade eben'; }
    if (minutes < 60) { return `vor ${minutes} Min.`; }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) { return `vor ${hours} Std.`; }
    return `vor ${Math.floor(hours / 24)} Tagen`;
  }
}
