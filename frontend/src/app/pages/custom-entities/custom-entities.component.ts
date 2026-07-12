import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap } from 'rxjs';
import { EntityStateService } from '../../services/entity-state.service';
import { EntityState, MANUAL_SOURCE } from '../../models/entity-state.model';

const REFRESH_INTERVAL_MS = 10000;

/**
 * Seite zum Anlegen und Steuern eigener Boolean-Entitäten (Modi/Helfer wie
 * "Nachtmodus" oder "Haus abgeschlossen"). Manuelle Entitäten werden vom
 * Benutzer erstellt und direkt hier geschaltet, umbenannt oder gelöscht.
 */
@Component({
  selector: 'app-custom-entities',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './custom-entities.component.html',
  styleUrl: './custom-entities.component.scss'
})
export class CustomEntitiesComponent implements OnInit {
  private readonly entityStateService = inject(EntityStateService);
  private readonly destroyRef = inject(DestroyRef);

  readonly entities = signal<EntityState[]>([]);
  readonly error = signal<string | null>(null);
  readonly saving = signal<boolean>(false);

  readonly newName = signal<string>('');
  readonly newIcon = signal<string>('');

  readonly editingId = signal<string | null>(null);
  readonly editName = signal<string>('');
  readonly editIcon = signal<string>('');

  ngOnInit(): void {
    interval(REFRESH_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.entityStateService.getEntities('INPUT_BOOLEAN', MANUAL_SOURCE)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: entities => {
        this.entities.set(entities);
        this.error.set(null);
      },
      error: err => this.error.set(err.message)
    });
  }

  create(): void {
    const name = this.newName().trim();
    if (!name || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.entityStateService.createManualEntity({ name, icon: this.newIcon().trim() || undefined }).subscribe({
      next: entity => {
        this.entities.update(list => [...list, entity].sort((a, b) => a.entityId.localeCompare(b.entityId)));
        this.newName.set('');
        this.newIcon.set('');
        this.error.set(null);
        this.saving.set(false);
      },
      error: err => {
        this.error.set(err.message);
        this.saving.set(false);
      }
    });
  }

  toggle(entity: EntityState): void {
    this.entityStateService.toggleManualEntity(entity.entityId).subscribe({
      next: updated => this.replace(updated),
      error: err => this.error.set(err.message)
    });
  }

  startEdit(entity: EntityState): void {
    this.editingId.set(entity.entityId);
    this.editName.set(entity.friendlyName);
    this.editIcon.set(this.iconOf(entity));
  }

  cancelEdit(): void {
    this.editingId.set(null);
  }

  saveEdit(entity: EntityState): void {
    const name = this.editName().trim();
    if (!name) {
      return;
    }
    this.entityStateService.renameManualEntity(entity.entityId, {
      name,
      icon: this.editIcon().trim() || undefined
    }).subscribe({
      next: updated => {
        this.replace(updated);
        this.editingId.set(null);
      },
      error: err => this.error.set(err.message)
    });
  }

  remove(entity: EntityState): void {
    if (!confirm(`"${entity.friendlyName}" wirklich löschen?`)) {
      return;
    }
    this.entityStateService.deleteManualEntity(entity.entityId).subscribe({
      next: () => this.entities.update(list => list.filter(e => e.entityId !== entity.entityId)),
      error: err => this.error.set(err.message)
    });
  }

  isOn(entity: EntityState): boolean {
    return entity.state === 'on';
  }

  iconOf(entity: EntityState): string {
    const icon = entity.attributes?.['icon'];
    return typeof icon === 'string' ? icon : '';
  }

  private replace(updated: EntityState): void {
    this.entities.update(list => list.map(e => e.entityId === updated.entityId ? updated : e));
  }
}
