import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SwitchEntity } from '../../models/switch.model';

/**
 * Praesentationale Liste von Schalter-Zeilen (Icon, Name, Zustand, Umschalter).
 * Haelt keinen Zustand und ruft keine Services auf: Kachel und Dialog reichen
 * die Daten herein und behandeln das `toggled`-Ereignis.
 */
@Component({
  selector: 'app-switch-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './switch-list.component.html',
  styleUrl: './switch-list.component.scss'
})
export class SwitchListComponent {
  @Input({ required: true }) switches: SwitchEntity[] = [];

  /** Entity-IDs mit laufendem Schaltbefehl; deren Zeilen sind gesperrt. */
  @Input() pendingIds: ReadonlySet<string> = new Set<string>();

  /** Tonalitaet: dunkle Kachel oder heller Dialog. */
  @Input() variant: 'tile' | 'dialog' = 'tile';

  @Output() toggled = new EventEmitter<SwitchEntity>();

  isOn(entity: SwitchEntity): boolean {
    return entity.state === 'on';
  }

  isPending(entity: SwitchEntity): boolean {
    return this.pendingIds.has(entity.entityId);
  }

  /** Beschriftung des Schalterzustands. */
  stateLabel(entity: SwitchEntity): string {
    if (!entity.available) {
      return 'Nicht verfügbar';
    }
    return this.isOn(entity) ? 'An' : 'Aus';
  }
}
